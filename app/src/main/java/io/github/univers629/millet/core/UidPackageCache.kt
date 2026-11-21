package io.github.univers629.millet.core

import android.content.Context
import android.os.Binder
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * UID → 包名的无锁缓存（供 system_server 钩子使用）。
 *
 * 钩子可能运行在持有 AMS / WindowManagerGlobalLock / Greeze 锁的线程上；直接从这些线程调用
 * PackageManager 会产生跨进程同步调用并反向争抢平台锁（曾导致 AMS↔WMS 的 ABBA 死锁而被
 * watchdog 重启）。因此钩子路径只读缓存，未命中时投递后台解析并返回 null（调用方按放行处理）。
 */
object UidPackageCache {

    /** 解析失败（无对应包）后的重试间隔。 */
    private const val NEGATIVE_RETRY_MS = 10 * 60_000L

    private val cache = ConcurrentHashMap<Int, String>()
    private val resolvedAt = ConcurrentHashMap<Int, Long>()
    private val inFlight = ConcurrentHashMap.newKeySet<Int>()

    @Volatile
    private var executor: ExecutorService? = null

    private val executorLock = Any()

    /** 仅读缓存；未命中时投递后台解析并返回 null。 */
    fun lookup(uid: Int): String? {
        if (uid <= 0) return null
        val cached = cache[uid] ?: return null
        return cached.takeIf { it.isNotEmpty() }
    }

    /** 未命中且超过重试间隔时投递一次后台解析；context 为空时跳过。 */
    fun requestResolve(uid: Int, context: Context?) {
        if (uid <= 0) return
        val ctx = context ?: return
        val now = SystemClock.elapsedRealtime()
        val last = resolvedAt[uid]
        if (last != null && now - last in 0 until NEGATIVE_RETRY_MS) return
        if (!inFlight.add(uid)) return
        executor().execute {
            try {
                val pkg = query(ctx, uid)
                cache[uid] = pkg ?: ""
                resolvedAt[uid] = SystemClock.elapsedRealtime()
            } catch (_: Throwable) {
            } finally {
                inFlight.remove(uid)
            }
        }
    }

    /**
     * 同步解析；只允许在没有持有平台锁的调用路径使用（UI/IPC、启动窗口、遥测导出）。
     */
    fun resolveBlocking(context: Context?, uid: Int): String? {
        if (uid <= 0) return null
        val ctx = context ?: return null
        val pkg = query(ctx, uid)
        cache[uid] = pkg ?: ""
        resolvedAt[uid] = SystemClock.elapsedRealtime()
        return pkg
    }

    fun clear() {
        cache.clear()
        resolvedAt.clear()
        inFlight.clear()
    }

    private fun query(context: Context, uid: Int): String? {
        val token = Binder.clearCallingIdentity()
        return try {
            context.packageManager.getPackagesForUid(uid)?.firstOrNull()
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun executor(): ExecutorService {
        executor?.let { return it }
        synchronized(executorLock) {
            executor?.let { return it }
            val created = Executors.newSingleThreadExecutor { task ->
                Thread(task, "XMillet-UidPackage").apply { isDaemon = true }
            }
            executor = created
            return created
        }
    }

    internal fun putForTest(uid: Int, pkg: String?) {
        cache[uid] = pkg ?: ""
        resolvedAt[uid] = 0L
    }
}