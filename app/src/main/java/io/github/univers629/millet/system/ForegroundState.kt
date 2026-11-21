package io.github.univers629.millet.system

import android.app.ActivityManager
import android.content.Context
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import io.github.univers629.millet.core.Logger

/**
 * 前台/可见进程的无锁快照。
 *
 * 钩子会在持有平台锁的线程上执行（例如焦点切换时持有 `WindowManagerGlobalLock`，
 * 广播投递时持有 `ActivityManagerService` 锁）。这些线程里再调用
 * `ActivityManager.getRunningAppProcesses()` 或 `getPackageImportance()` 会去抢
 * `ActivityManagerProcLock`，与 AMS 线程「持 ProcLock 等 WindowManagerGlobalLock」形成环路，
 * 最终触发 system_server watchdog 重启。
 *
 * 因此前台状态改由独立线程周期采样，钩子只读取一个不可变快照；快照过期或尚未建立时
 * 返回 `null`，调用方按「可能在前台」处理（放行、不拦截），宁可少冻也不参与锁等待。
 */
object ForegroundState {

    /** 采样周期。 */
    private const val REFRESH_INTERVAL_MS = 2_000L

    /** 快照有效期，超过后视为不可用。 */
    private const val SNAPSHOT_TTL_MS = 8_000L

    private const val THREAD_NAME = "XMillet-Foreground"

    class Snapshot(
        val updatedAtElapsed: Long,
        val visiblePackages: Set<String>,
        val visibleUids: Set<Int>,
        val processNames: Map<Int, String>,
    )

    @Volatile
    private var snapshot: Snapshot? = null

    @Volatile
    private var refreshHandler: Handler? = null

    @Volatile
    private var refreshThread: HandlerThread? = null

    private val lock = Any()

    /** 测试注入的时钟，默认使用 `elapsedRealtime`。 */
    internal var nowProvider: () -> Long = { SystemClock.elapsedRealtime() }

    private val refreshTask = object : Runnable {
        override fun run() {
            runCatching { refresh() }.onFailure { Logger.error("刷新前台快照失败", it) }
            refreshHandler?.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    /** 启动采样线程；重复调用无副作用。 */
    fun start(context: Context?) {
        if (context == null) return
        synchronized(lock) {
            if (refreshHandler != null) return
            val thread = try {
                HandlerThread(THREAD_NAME).apply { start() }
            } catch (t: Throwable) {
                Logger.error("启动前台采样线程失败", t)
                return
            }
            val handler = Handler(thread.looper)
            refreshThread = thread
            refreshHandler = handler
            handler.post(refreshTask)
        }
    }

    /** 热重载前回收采样线程。 */
    fun stop() {
        synchronized(lock) {
            refreshHandler?.removeCallbacksAndMessages(null)
            refreshThread?.quitSafely()
            refreshHandler = null
            refreshThread = null
        }
    }

    /**
     * 包名当前是否有可见进程。
     *
     * @return true 可见；false 明确不可见；null 快照不可用（调用方按「可能在前台」处理）
     */
    fun isForeground(packageName: String?): Boolean? {
        if (packageName.isNullOrEmpty()) return null
        val fresh = freshSnapshot()
        if (fresh != null) return packageName in fresh.visiblePackages
        // 快照缺失时顺带重试启动采样线程（systemContext 可能晚于服务重新获取才就绪）。
        start(SystemState.systemContext)
        return null
    }

    /**
     * UID 当前是否有可见进程。
     *
     * @return true 可见；false 明确不可见；null 快照不可用（调用方按「可能在前台」保守处理）
     */
    fun isUidVisible(uid: Int): Boolean? {
        val fresh = freshSnapshot() ?: run {
            start(SystemState.systemContext)
            return null
        }
        return uid in fresh.visibleUids
    }

    /** 进程名查询；快照不可用时返回 null。 */
    fun processNameOf(pid: Int): String? = freshSnapshot()?.processNames?.get(pid)

    internal fun freshSnapshot(): Snapshot? {
        val current = snapshot ?: return null
        val age = nowProvider() - current.updatedAtElapsed
        return if (age in 0..SNAPSHOT_TTL_MS) current else null
    }

    /** 仅测试使用。 */
    internal fun installForTest(value: Snapshot?) {
        snapshot = value
    }

    private fun refresh() {
        val context = SystemState.systemContext ?: return
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val token = Binder.clearCallingIdentity()
        val processes = try {
            manager.runningAppProcesses.orEmpty()
        } catch (t: Throwable) {
            Logger.error("读取运行进程失败", t)
            emptyList()
        } finally {
            Binder.restoreCallingIdentity(token)
        }
        val visiblePackages = HashSet<String>()
        val visibleUids = HashSet<Int>()
        val processNames = HashMap<Int, String>()
        for (process in processes) {
            processNames[process.pid] = process.processName
            if (process.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) {
                visibleUids += process.uid
                process.pkgList?.forEach { visiblePackages += it }
            }
        }
        snapshot = Snapshot(nowProvider(), visiblePackages, visibleUids, processNames)
    }
}
