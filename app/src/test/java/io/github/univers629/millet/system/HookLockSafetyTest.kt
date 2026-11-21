package io.github.univers629.millet.system

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 钩子代码里禁止同步调用会被平台锁阻塞的查询接口。
 *
 * system_server 的钩子可能运行在持有 AMS / WindowManagerGlobalLock / Greeze 锁的线程上；
 * 在这些线程里再查询运行进程会反向争抢 ActivityManagerProcLock，形成锁环并触发 watchdog 重启
 * （见 2026-11-21 15:46 的 system_server_watchdog）。前台状态只能从 [ForegroundState]
 * 的异步快照读取，进程名从 procfs 读取。
 */
class HookLockSafetyTest {

    private val moduleRoot: File by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/java/io/github/univers629/millet").isDirectory) return@lazy dir
            if (File(dir, "app/src/main/java/io/github/univers629/millet").isDirectory) return@lazy dir
            dir = dir.parentFile
        }
        throw IllegalStateException("无法定位模块目录，工作目录=" + File(".").absolutePath)
    }

    private fun moduleRelative(path: String): File {
        val root = moduleRoot
        val direct = File(root, path)
        return if (direct.exists()) direct else File(File(root, "app"), path)
    }

    /** 唯一允许直接读取运行进程清单的文件：采样线程在无平台锁的上下文执行。 */
    private val allowedReaders = setOf("ForegroundState.kt", "ProcessSnapshotProvider.kt")

    private val scannedPackages = listOf("system", "powerkeeper")

    private val banned = listOf("runningAppProcesses", "getRunningAppProcesses(", "getPackageImportance")

    private fun violations(): List<String> {
        val hits = ArrayList<String>()
        for (pkg in scannedPackages) {
            val dir = moduleRelative("src/main/java/io/github/univers629/millet/" + pkg)
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".kt") } ?: continue
            for (file in files) {
                if (file.name in allowedReaders) continue
                val code = stripComments(file.readText())
                code.lineSequence().forEachIndexed { index, line ->
                    for (pattern in banned) {
                        if (line.contains(pattern)) hits += pkg + "/" + file.name + ":" + (index + 1) + ": " + pattern
                    }
                }
            }
        }
        return hits
    }

    /** 去掉行注释与块注释，避免 KDoc 中的示例代码被误判。 */
    private fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var index = 0
        var blockDepth = 0
        while (index < source.length) {
            val c = source[index]
            val next = if (index + 1 < source.length) source[index + 1] else '\u0000'
            when {
                blockDepth > 0 -> {
                    if (c == '*' && next == '/') {
                        blockDepth--
                        index++
                    } else if (c == '/' && next == '*') {
                        blockDepth++
                        index++
                    } else if (c == '\n') {
                        out.append('\n')
                    }
                }
                c == '/' && next == '*' -> {
                    blockDepth++
                    index++
                }
                c == '/' && next == '/' -> {
                    while (index < source.length && source[index] != '\n') index++
                    continue
                }
                else -> out.append(c)
            }
            index++
        }
        return out.toString()
    }

    @Test
    fun hooksDoNotQueryRunningProcessesSynchronously() {
        val hits = violations()
        assertTrue(hits.joinToString("\n"), hits.isEmpty())
    }
}