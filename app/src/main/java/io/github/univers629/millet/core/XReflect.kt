package io.github.univers629.millet.core

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 高性能、无遗留 Xposed 依赖的纯 Java/Kotlin 反射工具箱。
 *
 * 核心特性：
 * 1. 递归沿继承链（superclass）向下扫描字段与方法，与原 XposedHelpers 语义一致；
 * 2. 字段与方法反射对象并发线程安全缓存（ConcurrentHashMap），降低热点路径开销；
 * 3. 统一 setAccessible(true)，并在各操作处提供安全 try/catch 兜底，防止宿主崩溃。
 */
object XReflect {

    private val classCache = ConcurrentHashMap<String, Class<*>>()
    private val fieldCache = ConcurrentHashMap<String, Field>()
    private val methodCache = ConcurrentHashMap<String, Method>()
    private val constructorCache = ConcurrentHashMap<String, Constructor<*>>()

    /**
     * 查找类，未找到时抛出 ClassNotFoundException。
     */
    fun findClass(className: String, classLoader: ClassLoader?): Class<*> {
        val loader = classLoader ?: ClassLoader.getSystemClassLoader()
        val cacheKey = "${System.identityHashCode(loader)}:$className"
        return classCache.computeIfAbsent(cacheKey) {
            Class.forName(className, false, loader)
        }
    }

    /**
     * 查找类，未找到时安全返回 null。
     */
    fun findClassIfExists(className: String, classLoader: ClassLoader?): Class<*>? {
        return try {
            findClass(className, classLoader)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 沿继承树查找 Field，并自动置 accessible。
     */
    fun findField(clazz: Class<*>, fieldName: String): Field {
        val cacheKey = "${clazz.name}#$fieldName"
        return fieldCache.computeIfAbsent(cacheKey) {
            var current: Class<*>? = clazz
            while (current != null && current != Any::class.java) {
                try {
                    val field = current.getDeclaredField(fieldName)
                    field.isAccessible = true
                    return@computeIfAbsent field
                } catch (_: NoSuchFieldException) {
                    current = current.superclass
                }
            }
            throw NoSuchFieldException("Field $fieldName not found in hierarchy of ${clazz.name}")
        }
    }

    /** 负缓存：记录确认不存在的字段，避免热路径上反复遍历继承链并构造 NoSuchFieldException。 */
    private val missingFieldCache: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * 沿继承树查找 Field，不存在时返回 null（结果正负均缓存，不抛异常）。
     */
    fun findFieldOrNull(clazz: Class<*>, fieldName: String): Field? {
        val cacheKey = "${clazz.name}#$fieldName"
        fieldCache[cacheKey]?.let { return it }
        if (missingFieldCache.contains(cacheKey)) return null
        return try {
            findField(clazz, fieldName)
        } catch (_: NoSuchFieldException) {
            missingFieldCache.add(cacheKey)
            null
        }
    }

    /**
     * 依次尝试多个候选字段名读取整型字段；对象为 null、字段均不存在或读取失败时返回 [default]。
     * 用于替代热路径上的 `runCatching { getIntField(o, "mUid") }` + 回退写法。
     */
    fun getIntFieldOr(obj: Any?, vararg fieldNames: String, default: Int = -1): Int {
        if (obj == null) return default
        val clazz = obj.javaClass
        for (name in fieldNames) {
            val field = findFieldOrNull(clazz, name) ?: continue
            return try {
                field.getInt(obj)
            } catch (_: Throwable) {
                default
            }
        }
        return default
    }

    /**
     * 读取对象实例字段。
     */
    fun getObjectField(obj: Any?, fieldName: String): Any? {
        if (obj == null) return null
        return findField(obj.javaClass, fieldName).get(obj)
    }

    /**
     * 设置对象实例字段。
     */
    fun setObjectField(obj: Any?, fieldName: String, value: Any?) {
        if (obj == null) return
        findField(obj.javaClass, fieldName).set(obj, value)
    }

    /**
     * 读取整型字段。
     */
    fun getIntField(obj: Any?, fieldName: String): Int {
        if (obj == null) return -1
        return findField(obj.javaClass, fieldName).getInt(obj)
    }

    /**
     * 写入整型字段。
     */
    fun setIntField(obj: Any?, fieldName: String, value: Int) {
        if (obj == null) return
        findField(obj.javaClass, fieldName).setInt(obj, value)
    }

    /**
     * 写入布尔字段。
     */
    fun setBooleanField(obj: Any?, fieldName: String, value: Boolean) {
        if (obj == null) return
        findField(obj.javaClass, fieldName).setBoolean(obj, value)
    }

    /**
     * 读取类静态对象字段。
     */
    fun getStaticObjectField(clazz: Class<*>, fieldName: String): Any? {
        return findField(clazz, fieldName).get(null)
    }

    /**
     * 写入类静态对象字段。
     */
    fun setStaticObjectField(clazz: Class<*>, fieldName: String, value: Any?) {
        findField(clazz, fieldName).set(null, value)
    }

    /**
     * 读取类静态布尔字段。
     */
    fun getStaticBooleanField(clazz: Class<*>, fieldName: String): Boolean {
        return findField(clazz, fieldName).getBoolean(null)
    }

    /**
     * 精确查找构造器。
     */
    fun findConstructorExact(clazz: Class<*>, vararg parameterTypes: Class<*>): Constructor<*> {
        val sig = buildMethodSignature(clazz.name, "<init>", parameterTypes)
        return constructorCache.computeIfAbsent(sig) {
            val ctor = clazz.getDeclaredConstructor(*parameterTypes)
            ctor.isAccessible = true
            ctor
        }
    }

    /**
     * 创建新实例。
     */
    fun newInstance(clazz: Class<*>, vararg args: Any?): Any {
        for (ctor in clazz.declaredConstructors) {
            if (isParametersMatch(ctor.parameterTypes, args)) {
                ctor.isAccessible = true
                return ctor.newInstance(*args)
            }
        }
        throw NoSuchMethodException("No matching constructor for ${clazz.name} with args count ${args.size}")
    }

    /**
     * 沿继承树精确查找指定名称和参数类型的方法。
     */
    fun findMethodExact(clazz: Class<*>, methodName: String, vararg parameterTypes: Class<*>): Method {
        val sig = buildMethodSignature(clazz.name, methodName, parameterTypes)
        return methodCache.computeIfAbsent(sig) {
            var current: Class<*>? = clazz
            while (current != null && current != Any::class.java) {
                try {
                    val method = current.getDeclaredMethod(methodName, *parameterTypes)
                    method.isAccessible = true
                    return@computeIfAbsent method
                } catch (_: NoSuchMethodException) {
                    current = current.superclass
                }
            }
            throw NoSuchMethodException("Method $methodName not found in hierarchy of ${clazz.name}")
        }
    }

    /**
     * 查找方法（若参数类型未严格匹配，按参数名与数量/类型兼容匹配）。
     */
    fun findMethodBestMatch(clazz: Class<*>, methodName: String, vararg parameterTypes: Class<*>): Method {
        return try {
            findMethodExact(clazz, methodName, *parameterTypes)
        } catch (_: NoSuchMethodException) {
            var current: Class<*>? = clazz
            while (current != null && current != Any::class.java) {
                for (m in current.declaredMethods) {
                    if (m.name == methodName && isTypesMatch(m.parameterTypes, parameterTypes)) {
                        m.isAccessible = true
                        return m
                    }
                }
                current = current.superclass
            }
            throw NoSuchMethodException("No compatible method $methodName in ${clazz.name}")
        }
    }

    /**
     * 调用实例方法。
     */
    fun callMethod(obj: Any?, methodName: String, vararg args: Any?): Any? {
        if (obj == null) return null
        val clazz = obj.javaClass
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            for (m in current.declaredMethods) {
                if (m.name == methodName && isParametersMatch(m.parameterTypes, args)) {
                    m.isAccessible = true
                    return m.invoke(obj, *args)
                }
            }
            current = current.superclass
        }
        throw NoSuchMethodException("No method $methodName on ${clazz.name} matching given args")
    }

    /**
     * 调用静态方法。
     */
    fun callStaticMethod(clazz: Class<*>, methodName: String, vararg args: Any?): Any? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            for (m in current.declaredMethods) {
                if (m.name == methodName && isParametersMatch(m.parameterTypes, args)) {
                    m.isAccessible = true
                    return m.invoke(null, *args)
                }
            }
            current = current.superclass
        }
        throw NoSuchMethodException("No static method $methodName on ${clazz.name} matching given args")
    }

    private fun isTypesMatch(actual: Array<Class<*>>, expected: Array<out Class<*>>): Boolean {
        if (actual.size != expected.size) return false
        for (i in actual.indices) {
            if (!isAssignable(actual[i], expected[i])) return false
        }
        return true
    }

    private fun isParametersMatch(paramTypes: Array<Class<*>>, args: Array<out Any?>): Boolean {
        if (paramTypes.size != args.size) return false
        for (i in paramTypes.indices) {
            val arg = args[i]
            if (arg == null) {
                if (paramTypes[i].isPrimitive) return false
            } else {
                if (!isAssignable(paramTypes[i], arg.javaClass)) return false
            }
        }
        return true
    }

    private fun isAssignable(target: Class<*>, source: Class<*>): Boolean {
        if (target.isAssignableFrom(source)) return true
        if (target.isPrimitive) {
            return when (target) {
                Boolean::class.javaPrimitiveType -> source == Boolean::class.javaObjectType
                Byte::class.javaPrimitiveType -> source == Byte::class.javaObjectType
                Char::class.javaPrimitiveType -> source == Char::class.javaObjectType
                Short::class.javaPrimitiveType -> source == Short::class.javaObjectType
                Int::class.javaPrimitiveType -> source == Int::class.javaObjectType
                Long::class.javaPrimitiveType -> source == Long::class.javaObjectType
                Float::class.javaPrimitiveType -> source == Float::class.javaObjectType
                Double::class.javaPrimitiveType -> source == Double::class.javaObjectType
                else -> false
            }
        }
        return false
    }

    private fun buildMethodSignature(className: String, methodName: String, parameterTypes: Array<out Class<*>>): String {
        val sb = StringBuilder(className).append('#').append(methodName).append('(')
        for (i in parameterTypes.indices) {
            if (i > 0) sb.append(',')
            sb.append(parameterTypes[i].name)
        }
        sb.append(')')
        return sb.toString()
    }
}
