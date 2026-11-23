package io.github.univers629.millet.system

import android.content.pm.ApplicationInfo
import io.github.libxposed.api.XposedInterface
import io.github.univers629.millet.core.HookMode
import io.github.univers629.millet.core.HookSpec
import io.github.univers629.millet.core.Logger
import io.github.univers629.millet.core.ModernHookInstaller
import io.github.univers629.millet.core.PrefReader
import io.github.univers629.millet.core.UnifiedHookParam
import io.github.univers629.millet.core.XReflect
import java.lang.reflect.Method
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/**
 * 进程生命周期缓存（S4 / S15 / S16）与 ANR 双保险全屏蔽（S5 / S19）。
 *
 * 覆盖主线：
 * 3. ANR 全屏蔽（S5 ProcessErrorStateRecord#appNotResponding + S19 AnrHelper 全部 void 同名方法）
 *
 * 修正缺陷 #2：
 * 真机 `services.jar` 中 `ProcessErrorStateRecord#appNotResponding` 签名为 **10 参**：
 * `(String, ApplicationInfo, String, WindowProcessController, boolean, TimeoutRecord, ExecutorService, boolean, boolean, Future)`
 * 原模块回退表仅写了 7 参，一旦反射失败则无法挂载。
 * 本实现采用「运行时反射取声明参数 + 真机 10 参硬编码回退」双路保障。
 */
object AnrAndProcessHooks {

    private const val CLASS_PROCESS_LIST = "com.android.server.am.ProcessList"
    private const val CLASS_PROCESS_RECORD = "com.android.server.am.ProcessRecord"
    private const val CLASS_PROCESS_ERROR_STATE_RECORD = "com.android.server.am.ProcessErrorStateRecord"
    private const val CLASS_ANR_HELPER = "com.android.server.am.AnrHelper"
    private const val CLASS_ANR_RECORD = "com.android.server.am.AnrHelper\$AnrRecord"
    private const val CLASS_WINDOW_PROCESS_CONTROLLER = "com.android.server.wm.WindowProcessController"
    private const val CLASS_TIMEOUT_RECORD = "com.android.internal.os.TimeoutRecord"

    /**
     * S4（PA(13)）：ProcessList#addProcessNameLocked(ProcessRecord)
     * 在进程创建时缓存非系统应用（或处于强制冻结集合中的应用）的 ProcessRecord 快照，供 ANR 屏蔽反查。
     */
    fun buildS4AddProcessNameHook(): HookSpec = HookSpec(
        id = "S4",
        name = "ProcessList#addProcessNameLocked",
        targetClass = CLASS_PROCESS_LIST,
        methodName = "addProcessNameLocked",
        paramTypes = listOf(CLASS_PROCESS_RECORD),
        mode = HookMode.BEFORE,
        successLog = "监听进程新增成功",
        before = { param ->
            val procRecord = param.args.getOrNull(0) ?: return@HookSpec
            val state = SystemState.extractProcessStateOrNull(procRecord) ?: return@HookSpec
            val forceFreezeSet = PrefReader.systemPrefs.forceFreezeSet

            if (!state.isSystemApp || state.packageName in forceFreezeSet) {
                SystemState.cacheProcessRecord(state)
            }
        }
    )

    /**
     * S15（MSH(2)）：ProcessList#removeProcessNameLocked(String, int, ProcessRecord)
     * 无条件安装：进程退出时清理进程快照缓存与 PID 记账表。
     */
    fun buildS15RemoveProcessNameHook(): HookSpec = HookSpec(
        id = "S15",
        name = "ProcessList#removeProcessNameLocked",
        targetClass = CLASS_PROCESS_LIST,
        methodName = "removeProcessNameLocked",
        paramTypes = listOf(
            String::class.java,
            Int::class.javaPrimitiveType!!,
            CLASS_PROCESS_RECORD
        ),
        mode = HookMode.AFTER,
        successLog = "监听进程移除成功",
        after = { param ->
            val argProcessName = param.args.getOrNull(0) as? String
            val argUid = (param.args.getOrNull(1) as? Int) ?: -1
            val expectingRecord = param.args.getOrNull(2)

            val extracted = SystemState.extractProcessStateOrNull(expectingRecord)
            val processName = extracted?.processName ?: argProcessName
            val appUid = extracted?.uid ?: argUid
            if (appUid <= 0) return@HookSpec

            val removed = SystemState.removeCachedProcessRecord(processName, appUid)

            if (PrefReader.systemPrefs.simplifyLog && !(extracted?.isSystemApp ?: SystemState.isSystemAppUid(appUid))) {
                val targetRecord = expectingRecord ?: removed?.processRecord
                val pid = if (targetRecord != null) {
                    XReflect.getIntFieldOr(targetRecord, "mPid", "pid")
                } else {
                    -1
                }
                if (pid > 0) {
                    Logger.postIoTask {
                        SystemState.removePidFromFreezeTable(appUid, pid)
                    }
                }
            }
        }
    )

    /**
     * S16（MSH(3)）：ProcessList#handleProcessStartedLocked(ProcessRecord, int, boolean, long, boolean)
     * 受 `simplifyLog` 装配期门控：进程启动后补记 PID 并标记为已解冻，输出墓碑日志。
     */
    fun buildS16HandleProcessStartedHook(): HookSpec = HookSpec(
        id = "S16",
        name = "ProcessList#handleProcessStartedLocked",
        targetClass = CLASS_PROCESS_LIST,
        methodName = "handleProcessStartedLocked",
        paramTypes = listOf(
            CLASS_PROCESS_RECORD,
            Int::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!,
            Long::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!
        ),
        mode = HookMode.AFTER,
        gate = { PrefReader.systemPrefs.simplifyLog },
        successLog = "监听进程启动成功",
        after = { param ->
            if (!PrefReader.systemPrefs.simplifyLog) return@HookSpec
            val started = (param.result as? Boolean) ?: false
            if (!started) return@HookSpec

            val procRecord = param.args.getOrNull(0) ?: return@HookSpec
            val pid = (param.args.getOrNull(1) as? Int) ?: return@HookSpec
            val state = SystemState.extractProcessStateOrNull(procRecord) ?: return@HookSpec

            if (state.isSystemApp || state.processName.contains("com.google.android.webview")) {
                return@HookSpec
            }

            Logger.postIoTask {
                val alreadyTracked = SystemState.isPidTracked(state.uid, pid)
                SystemState.recordPidFreezeState(
                    uid = state.uid,
                    pid = pid,
                    frozen = false,
                    reason = "XMillet ProcessAdd"
                )
                if (!alreadyTracked) {
                    val (frozenCount, totalCount, mapSnapshot) = SystemState.getUidFreezeSummary(state.uid)
                    Logger.alwaysInfo(
                        "进程启动由于[XMillet ProcessAdd],解冻进程[${state.processName}],进程冻结情况:$frozenCount/$totalCount,$mapSnapshot"
                    )
                }
            }
        }
    )

    /**
     * S5（PA(7)）：ProcessErrorStateRecord#appNotResponding(...)
     * 【ANR 屏蔽第一层（进程级）】：
     * 运行时先反射取声明方法参数；若反射受限则回退至真机核验的 **10 参数签名**（修正缺陷 #2）。
     */
    fun buildS5ProcessErrorAnrHook(): HookSpec = HookSpec(
        id = "S5",
        name = "ProcessErrorStateRecord#appNotResponding",
        targetClass = CLASS_PROCESS_ERROR_STATE_RECORD,
        methodName = "appNotResponding",
        paramTypes = listOf(
            String::class.java,
            ApplicationInfo::class.java,
            String::class.java,
            CLASS_WINDOW_PROCESS_CONTROLLER,
            Boolean::class.javaPrimitiveType!!,
            CLASS_TIMEOUT_RECORD,
            ExecutorService::class.java,
            Boolean::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!,
            Future::class.java
        ),
        paramResolver = { _, clazz ->
            val reflected = clazz.declaredMethods
                .firstOrNull { it.name == "appNotResponding" }
                ?.parameterTypes
                ?.toList()
            reflected
        },
        mode = HookMode.BEFORE,
        successLog = "特殊应用无响应拦截成功",
        failureHint = "特殊应用无响应屏蔽出错，将导致冻结应用被杀",
        before = { param ->
            val errRecord = param.thisObject ?: return@HookSpec
            val processRecord = try {
                XReflect.getObjectField(errRecord, "mApp")
            } catch (_: Throwable) {
                null
            } ?: return@HookSpec

            suppressAnrIfCached(param, processRecord)
        }
    )

    private fun buildS19HookBuilder(): (Method) -> ((UnifiedHookParam) -> Unit)? {
        return { method ->
            val paramNames = method.parameterTypes.map { it.name }
            val processRecordIdx = paramNames.indexOf(CLASS_PROCESS_RECORD)
            val anrRecordIdx = paramNames.indexOf(CLASS_ANR_RECORD)

            when {
                processRecordIdx >= 0 -> { param ->
                    val procRecord = param.args.getOrNull(processRecordIdx)
                    suppressAnrIfCached(param, procRecord)
                }

                anrRecordIdx >= 0 -> { param ->
                    val anrRecord = param.args.getOrNull(anrRecordIdx)
                    val procRecord = if (anrRecord != null) {
                        try {
                            XReflect.getObjectField(anrRecord, "mApp")
                        } catch (_: Throwable) {
                            null
                        }
                    } else {
                        null
                    }
                    suppressAnrIfCached(param, procRecord)
                }

                else -> null
            }
        }
    }

    /**
     * S19（Modern LibXposed API 102 模式）：动态扫描并挂载所有 AnrHelper appNotResponding 拦截。
     */
    fun installS19AnrHelperHooksModern(xposed: XposedInterface, classLoader: ClassLoader) {
        val count = ModernHookInstaller.installDeclaredMethods(
            xposed = xposed,
            id = "S19",
            name = "AnrHelper#appNotResponding/deferAppNotResponding",
            classLoader = classLoader,
            targetClassName = CLASS_ANR_HELPER,
            methodFilter = { method ->
                (method.name == "appNotResponding" || method.name == "deferAppNotResponding") &&
                    method.returnType == Void.TYPE
            },
            hookBuilder = buildS19HookBuilder()
        )

        if (count > 0) {
            Logger.alwaysWarn("共屏蔽${count}处应用无响应")
        } else {
            Logger.debug("应用无响应方法未找到")
        }
    }

    /**
     * S5 与 S19 共享的 ANR 吞噬逻辑（对应原模块 AbstractC0207.handleFreezeResult）：
     * 只要该进程在 `SystemState` 中有缓存记录，即执行 `param.result = null` 屏蔽 ANR。
     */
    private fun suppressAnrIfCached(
        param: UnifiedHookParam,
        processRecord: Any?
    ) {
        val cached = SystemState.findCachedProcess(processRecord) ?: return
        if (PrefReader.systemPrefs.isLog) {
            if (SystemState.isUidFrozen(cached.uid)) {
                Logger.info("进程 ${cached.packageName} 冻结期间屏蔽应用无响应")
            } else {
                Logger.debug("进程 ${cached.packageName} 应用无响应，已屏蔽")
            }
        }
        param.result = null
    }
}
