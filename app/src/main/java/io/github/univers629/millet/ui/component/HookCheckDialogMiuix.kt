package io.github.univers629.millet.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.univers629.millet.core.HookInstallRegistry
import io.github.univers629.millet.core.RootHelper
import io.github.univers629.millet.core.VersionGate
import io.github.univers629.millet.core.XposedPrefsBridge
import io.github.univers629.millet.ipc.HookCoverage
import io.github.univers629.millet.ipc.PowerKeeperBinderClient
import io.github.univers629.millet.ui.theme.isInDarkTheme
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * Hook 点位与系统环境状态等级。
 */
enum class HookDiagnosticLevel {
    PASSED,
    WARNING,
    FAILED
}

/**
 * 单个 Hook 点位或运行环境的检测条目模型。
 */
data class HookDiagnosticEntry(
    val id: String,
    val title: String,
    val targetClassOrMethod: String,
    val level: HookDiagnosticLevel,
    val summaryText: String,
    val detailText: String,
    val suggestion: String? = null,
    /**
     * 是否属于辅助信息。可选项不计入顶部通过数与整体状态；
     * 官方策略链路、安装账本与真实冻结证据不能作为可选项排除。
     */
    val optional: Boolean = false
)

/**
 * 依据当前运行时状态快照与系统环境构建诊断条目列表。
 */
fun generateHookDiagnostics(
    runtimeStatus: PowerKeeperBinderClient.RuntimeStatusSnapshot,
    kernelFreezer: RootHelper.KernelFreezer = RootHelper.KernelFreezer.UNKNOWN,
    frameworkState: XposedPrefsBridge.XposedFrameworkState = XposedPrefsBridge.XposedFrameworkState(),
    kernelEvidenceConfirmed: Boolean = false,
    kernelEvidenceSource: String = "UNCONFIRMED",
    milletBackendState: String = "UNCONFIRMED"
): List<HookDiagnosticEntry> {
    val entries = ArrayList<HookDiagnosticEntry>()
    val coverage = runtimeStatus.hookCoverage
    // 注册结果与宿主类能力位分开；旧模块未上报时保持未确认。
    val greezeRegistered = coverage.capability("greeze_registered")
    val milletRegistered = coverage.capability("millet_registered")
    val controllerRegistered = coverage.capability("controller_registered")
    val bothSidesReported = hasPositiveCoverage(coverage.sys) && hasPositiveCoverage(coverage.pk)

    // 0. 运行环境：宿主 ROM 决定了哪些点位本来就存在，必须先摆出来，
    //    否则用户会把「本版本没有这个子系统」误读成「模块装坏了」。
    entries.add(
        HookDiagnosticEntry(
            id = "run_env",
            title = "运行环境",
            targetClassOrMethod = "ro.mi.os.version.name / ro.miui.ui.version.name",
            level = if (VersionGate.romTier == null) {
                HookDiagnosticLevel.WARNING
            } else {
                HookDiagnosticLevel.PASSED
            },
            summaryText = VersionGate.describeRom(),
            detailText = when (VersionGate.romTier) {
                VersionGate.RomTier.MIUI14 ->
                    "MIUI 14 按旧版 Millet 路径适配，P4 云名单热更新 Hook 使用与澎湃 OS1 相同的版本门控。" +
                        "双开相关 notifyExcuteServices 与 updateTargetList Hook 按宿主方法和能力条件安装；" +
                        "各点位的安装结果见覆盖情况。"
                VersionGate.RomTier.OS1, VersionGate.RomTier.OS2 ->
                    "澎湃 OS 早期版本按 com.miui.powerkeeper.millet 子系统适配云控白名单、灭屏清理与无限制名单；本机注册结果见下方检测项。"
                VersionGate.RomTier.OS3 ->
                    "澎湃 OS3 的适配路径使用 KillProcessController 等新入口，不要求已移除的 com.miui.powerkeeper.millet 与 com.miui.process 旧点位；" +
                        "具体宿主能力与注册结果仍以本机上报为准。"
                null ->
                    "未能读取 ro.mi.os.version.name 与 ro.miui.ui.version.name，无法判断宿主版本；下面的条目按运行时探测结果展示。"
            } + "版本属性不能证明官方冻结链路已注册或内核冻结已完成。"
        )
    )

    // 0.5 LibXposed 框架服务能力、作用域与热重载状态
    if (frameworkState.isServiceConnected) {
        val missingScopes = frameworkState.missingScopes
        val staleTargets = frameworkState.staleTargets
        val fwLevel = when {
            !frameworkState.supportsRemotePrefs -> HookDiagnosticLevel.FAILED
            missingScopes.isNotEmpty() || staleTargets.isNotEmpty() -> HookDiagnosticLevel.WARNING
            else -> HookDiagnosticLevel.PASSED
        }
        val capsSummary = buildString {
            append("${frameworkState.frameworkName} ${frameworkState.frameworkVersion} (API ${frameworkState.apiVersion})")
            append(" | 远程配置=${if (frameworkState.supportsRemotePrefs) "支持" else "不支持"}")
            append(", 系统作用域=${if (frameworkState.supportsSystemHook) "支持" else "不支持"}")
            append(", 作用域=${if (frameworkState.scope.isEmpty()) "空" else frameworkState.scope.joinToString(",")}")
            if (frameworkState.runningTargets.isNotEmpty()) {
                val targetDesc = frameworkState.runningTargets.joinToString(", ") { t ->
                    "${t.processName}:${t.state.name}(v${t.loadedVersionCode})"
                }
                append(" | 目标状态: $targetDesc")
            }
        }
        val fwSuggestion = when {
            !frameworkState.supportsRemotePrefs -> "更新 LSPosed 到支持 RemotePreferences 的版本后点「重新检测」"
            missingScopes.isNotEmpty() -> "作用域缺少 ${missingScopes.joinToString("、")}，可点击下方「授权作用域」"
            staleTargets.isNotEmpty() -> "检测到 ${staleTargets.size} 个目标进程仍运行旧代代码，可点击下方「热重载」无需重启更新"
            else -> null
        }
        entries.add(
            HookDiagnosticEntry(
                id = "libxposed_service",
                title = "LibXposed 框架服务",
                targetClassOrMethod = "XposedService (API ${frameworkState.apiVersion})",
                level = fwLevel,
                summaryText = when {
                    !frameworkState.supportsRemotePrefs -> "缺少远程配置能力"
                    missingScopes.isNotEmpty() -> "作用域缺失 ${missingScopes.size} 项"
                    staleTargets.isNotEmpty() -> "${staleTargets.size} 个进程待热重载"
                    else -> "能力与作用域正常"
                },
                detailText = capsSummary,
                suggestion = fwSuggestion,
                optional = fwLevel != HookDiagnosticLevel.FAILED
            )
        )
    }

    // 1. System Server 核心服务
    val sysLevel = when {
        runtimeStatus.binderAlive && runtimeStatus.greezeReady && greezeRegistered == true &&
            hasPositiveCoverage(coverage.sys) -> HookDiagnosticLevel.PASSED
        runtimeStatus.binderAlive -> HookDiagnosticLevel.WARNING
        else -> HookDiagnosticLevel.FAILED
    }
    entries.add(
        HookDiagnosticEntry(
            id = "system_server",
            title = "System Server 核心服务",
            targetClassOrMethod = "android / ActivityManagerService & GreezeManagerService",
            level = sysLevel,
            summaryText = when (sysLevel) {
                HookDiagnosticLevel.PASSED -> "服务与官方 Hook 已注册"
                HookDiagnosticLevel.WARNING -> "Binder 在线，官方链路待确认"
                HookDiagnosticLevel.FAILED -> "未连接系统服务"
            },
            detailText = when (sysLevel) {
                HookDiagnosticLevel.PASSED -> "SystemBinderService 在线，Greeze 实例与关键 Hook 注册结果已上报；本项不证明冻结成功。"
                HookDiagnosticLevel.WARNING -> "SystemBinderService 已建立通信通道，但 Greeze 实例或关键 Hook 注册结果未确认。Binder 在线不等同于冻结链路可用。"
                HookDiagnosticLevel.FAILED -> when {
                    frameworkState.isServiceConnected && !frameworkState.hasSystemScope ->
                        "XposedService 报告作用域尚未勾选「system（系统框架）」。点击下方「授权作用域」勾选即可；系统框架只在下次重启时注入，这一步由 LSPosed 决定，模块侧没有额外操作。"
                    !frameworkState.isServiceConnected ->
                        "未能连接 XposedService：确认 LSPosed 已启用、模块未被停用且未处于安全模式。启用后 system_server 会在下次启动时注入。"
                    else ->
                        "作用域已包含系统框架，但 system_server 尚未完成注入。点下方「热重载」可立即重装钩子（无需重启）；若仍失败，查看 /data/system/XMillet/log 中的注入错误。"
                }
            },
            suggestion = if (sysLevel == HookDiagnosticLevel.FAILED) {
                when {
                    frameworkState.isServiceConnected && !frameworkState.hasSystemScope ->
                        "点下方「授权作用域」，勾选后由 LSPosed 在下次重启时完成注入"
                    !frameworkState.isServiceConnected ->
                        "确认 LSPosed 与模块均处于启用状态"
                    frameworkState.staleTargets.isNotEmpty() ->
                        "点下方「热重载」重装 system_server 钩子，无需重启"
                    else ->
                        "查 /data/system/XMillet/log 中「Hook 安装结果」一行定位失败点位"
                }
            } else null
        )
    )

    // 2. 电量与性能 PowerKeeper 策略
    //    目标子系统随 ROM 版本不同：OS3 起 millet 已移除，仍宣称 MilletPolicy 会误导用户。
    val capMillet = coverage.capability(HookCoverage.CAP_MILLET)
    val capController = coverage.capability(HookCoverage.CAP_CONTROLLER)
    val policyRegistered = when {
        milletRegistered == true || controllerRegistered == true -> true
        milletRegistered == false && controllerRegistered == false -> false
        else -> null
    }
    val officialChainConfirmed = bothSidesReported && greezeRegistered == true &&
        policyRegistered == true && runtimeStatus.greezeReady && runtimeStatus.powerKeeperConnected
    val pkTarget = when {
        milletRegistered == true -> "com.miui.powerkeeper.millet.MilletPolicy & FreezeBinder"
        controllerRegistered == true -> "com.miui.powerkeeper.controller.FrozenAppController"
        capMillet == true -> "com.miui.powerkeeper.millet.MilletPolicy & FreezeBinder"
        capMillet == false && capController == true ->
            "com.miui.powerkeeper.controller.KillProcessController & FrozenAppController"
        capMillet == false -> "com.miui.powerkeeper（本版本无 millet / KillProcessController 点位）"
        else -> "com.miui.powerkeeper（宿主能力位未上报）"
    }
    val pkLevel = when {
        runtimeStatus.binderAlive && officialChainConfirmed -> HookDiagnosticLevel.PASSED
        runtimeStatus.powerKeeperConnected -> HookDiagnosticLevel.WARNING
        runtimeStatus.binderAlive -> HookDiagnosticLevel.WARNING
        else -> HookDiagnosticLevel.FAILED
    }
    entries.add(
        HookDiagnosticEntry(
            id = "powerkeeper",
            title = "电量与性能 PowerKeeper",
            targetClassOrMethod = pkTarget,
            level = pkLevel,
            summaryText = when (pkLevel) {
                HookDiagnosticLevel.PASSED -> "官方策略链路已注册"
                HookDiagnosticLevel.WARNING -> if (runtimeStatus.powerKeeperConnected) "已握手，官方链路未确认" else "等待双向握手"
                HookDiagnosticLevel.FAILED -> "未连接"
            },
            detailText = when (pkLevel) {
                HookDiagnosticLevel.PASSED -> when {
                    milletRegistered == true ->
                        "Millet 关键 Hook 注册结果与两侧安装账本已上报，PowerKeeper 已握手；冻结行为仍须由真实进程证据确认。"
                    controllerRegistered == true ->
                        "controller 替代路径的关键 Hook 注册结果与两侧账本已上报。此处不要求已移除的旧 Millet 点位，也不证明冻结成功。"
                    else ->
                        "已与「电量与性能」建立双向 Binder 回调；具体接管了哪些点位见下方安装点覆盖。"
                }
                HookDiagnosticLevel.WARNING -> if (runtimeStatus.powerKeeperConnected) {
                    when {
                        !bothSidesReported -> "PowerKeeper 已握手，但两侧有效安装账本未齐备，不能确认官方冻结链路。"
                        greezeRegistered == false || policyRegistered == false ->
                            "PowerKeeper 已握手，但关键官方 Hook 未注册或不受当前宿主支持。缺失与版本门控原因见安装点覆盖；类存在和握手不能替代注册结果。"
                        else -> "PowerKeeper 已握手，但官方 Greeze / 策略链路的注册结果或实例尚未确认；旧模块缺少注册字段时不按通过处理。"
                    }
                } else if (frameworkState.isServiceConnected && !frameworkState.hasPowerKeeperScope) {
                    "XposedService 报告作用域尚未勾选「com.miui.powerkeeper」，授权后强行停止电量与性能即可接入。"
                } else {
                    "系统服务已就绪，但「电量与性能」尚未完成双向 Binder 回调注册。亮灭屏一次或强行停止电量与性能即可自动握手。"
                }
                HookDiagnosticLevel.FAILED ->
                    "未能连接至 com.miui.powerkeeper。请在 LSPosed 中确认勾选「电量与性能」作用域。"
            },
            suggestion = when (pkLevel) {
                HookDiagnosticLevel.WARNING -> if (runtimeStatus.powerKeeperConnected) {
                    "点「重新检测」；仍为未确认时用「热重载」重装两侧钩子（无需重启），或亮灭屏一次等待双向回调重建"
                } else if (frameworkState.isServiceConnected && !frameworkState.hasPowerKeeperScope) {
                    "可点击下方「授权作用域」"
                } else {
                    "请按电源键灭屏并亮屏一次，或在设置中结束「电量与性能」"
                }
                HookDiagnosticLevel.FAILED -> "请在 LSPosed 作用域中勾选「电量与性能」"
                else -> null
            }
        )
    )

    // 3. Hook 安装点覆盖：两侧进程各自记账、汇总后展示真实数量
    entries.add(buildCoverageEntry(runtimeStatus))

    // 4. 小米 Greeze 墓碑驱动服务
    val greezeLevel = when {
        runtimeStatus.greezeReady && greezeRegistered == true && hasPositiveCoverage(coverage.sys) -> HookDiagnosticLevel.PASSED
        runtimeStatus.binderAlive -> HookDiagnosticLevel.WARNING
        else -> HookDiagnosticLevel.FAILED
    }
    entries.add(
        HookDiagnosticEntry(
            id = "greeze_service",
            title = "小米 Greeze 墓碑驱动服务",
            targetClassOrMethod = "com.miui.server.greeze.GreezeManagerService",
            level = greezeLevel,
            summaryText = if (greezeLevel == HookDiagnosticLevel.PASSED) "句柄与 Hook 已注册" else "官方服务未确认",
            detailText = if (greezeLevel == HookDiagnosticLevel.PASSED)
                "GreezeManagerService 实例已捕获，关键 Hook 注册结果已上报；句柄存在不能证明驱动冻结成功，也不说明具体内核机制。"
            else
                "GreezeManagerService 实例或关键 Hook 注册结果尚未确认。先点「重新检测」；仍为未确认时用「热重载」重装 system_server 钩子（无需重启）。不依据第三方内核名称或 ROM 属性判定冻结能力。"
        )
    )

    // 5. Aurogon 神隐模式监听
    val aurogonLevel = if (runtimeStatus.aurogonReady) {
        HookDiagnosticLevel.PASSED
    } else if (runtimeStatus.binderAlive) {
        HookDiagnosticLevel.WARNING
    } else {
        HookDiagnosticLevel.FAILED
    }
    entries.add(
        HookDiagnosticEntry(
            id = "aurogon_mode",
            title = "Aurogon 神隐模式 (前台/音视频感知)",
            targetClassOrMethod = "com.miui.server.greeze.AurogonImmobulusMode",
            level = aurogonLevel,
            summaryText = if (aurogonLevel == HookDiagnosticLevel.PASSED) "已就绪" else "待捕获",
            detailText = if (aurogonLevel == HookDiagnosticLevel.PASSED)
                "Aurogon 实例已捕获；本项仅反映句柄就绪，不证明前台感知、豁免或冻结行为已验证。"
            else
                "Aurogon 实例尚未捕获；本项未验证音视频与前台服务免冻行为。"
        )
    )

    // 6. Doze 深度睡眠状态机与省电白名单
    val dozeLevel = if (runtimeStatus.dozeReady) {
        HookDiagnosticLevel.PASSED
    } else if (runtimeStatus.binderAlive) {
        HookDiagnosticLevel.WARNING
    } else {
        HookDiagnosticLevel.FAILED
    }
    entries.add(
        HookDiagnosticEntry(
            id = "doze_controller",
            title = "Doze 状态机与省电优化白名单",
            targetClassOrMethod = "com.android.server.DeviceIdleController",
            level = dozeLevel,
            summaryText = if (dozeLevel == HookDiagnosticLevel.PASSED) "实例已捕获" else "待捕获",
            detailText = if (dozeLevel == HookDiagnosticLevel.PASSED)
                "DeviceIdleController 实例已捕获；本项不证明状态迁移或名单同步行为已验证，也不能替代冻结证据。"
            else
                "DeviceIdleController 待捕获。",
            suggestion = if (dozeLevel == HookDiagnosticLevel.PASSED) null
                else "点「重新检测」；仍待捕获时用「热重载」重装 system_server 钩子，无需重启"
        )
    )

    // 后端运行状态独立于模块 Hook 安装结果和启动加速冻结证据，始终是必要项。
    entries.add(
        HookDiagnosticEntry(
            id = "millet_backend",
            title = "官方 Millet 后端运行状态",
            targetClassOrMethod = "GreezeManagerService / Millet 配置与 monitor 注册",
            level = when (milletBackendState) {
                "READY" -> HookDiagnosticLevel.PASSED
                "DISABLED", "MONITORS_UNREADY" -> HookDiagnosticLevel.FAILED
                else -> HookDiagnosticLevel.WARNING
            },
            summaryText = when (milletBackendState) {
                "READY" -> "官方冻结路径已启用，所需 monitors 已注册"
                "DISABLED" -> "官方 Millet 后端未启用"
                "MONITORS_UNREADY" -> "所需 monitors 尚未完整注册"
                else -> "官方 Millet 后端运行状态未确认"
            },
            detailText = "按需读取 ROM 的 Millet 总开关、PowerMillet/传统 Greeze 独立路径开关及 mRegisteredMonitor 与 MILLET_MONITOR_ALL 掩码。" +
                "确认路径要求 Millet 与 PowerMillet 启用且所需 monitors 完整注册；不要求传统 Greeze 开关同时开启。" +
                "只有传统 Greeze 开关开启不能证明官方后台策略已运行，未核实的替代路径保持未确认。" +
                "缺字段、类型不符、旧端未上报或读取失败均不能通过；模块 Hook 安装、服务实例存在或启动加速能够冻结不能替代本项。" +
                "monitor 注册是用户空间运行状态，不宣称内核握手成功或后续调度必然生效；真实冻结能力仍须另项确认。",

            suggestion = when (milletBackendState) {
                "DISABLED" -> "在系统设置的省电与电池里开启官方省电策略后点「重新检测」"
                "MONITORS_UNREADY" -> "点「重新检测」；仍不齐备时结束「电量与性能」进程，让系统重新注册 monitors"
                else -> null
            }
        )
    )

    // 7. 内核证据须由检测器关联官方记账中的同一应用 PID；类型字段与注册结果不能替代证据。
    val confirmedKernelFreezer = kernelEvidenceConfirmed && when (kernelFreezer) {
        RootHelper.KernelFreezer.CGROUP_V2 -> kernelEvidenceSource in setOf("CGROUP_V2_FROZEN", "WCHAN_V2")
        RootHelper.KernelFreezer.CGROUP_V1 -> kernelEvidenceSource in setOf("CGROUP_V1_FROZEN", "WCHAN_V1")
        else -> false
    }
    val cgroupLevel = if (confirmedKernelFreezer) HookDiagnosticLevel.PASSED else HookDiagnosticLevel.WARNING
    entries.add(
        HookDiagnosticEntry(
            id = "kernel_freezer",
            title = "官方记账关联的内核冻结证据",
            targetClassOrMethod = when (kernelFreezer) {
                RootHelper.KernelFreezer.CGROUP_V2 -> "cgroup.events:frozen / do_freezer_trap"
                RootHelper.KernelFreezer.CGROUP_V1 -> "freezer.state:FROZEN / __refrigerator"
                else -> "成员 cgroup 完成状态 / 精确 freezer wchan"
            },
            level = cgroupLevel,
            summaryText = when {
                confirmedKernelFreezer -> "已读取官方记账关联的冻结证据 ($kernelEvidenceSource)"
                kernelFreezer == RootHelper.KernelFreezer.NONE -> "未发现接口，冻结能力未确认"
                else -> "真实冻结能力未确认"
            },
            detailText = when {
                confirmedKernelFreezer ->
                    "模块只读 Greeze 当前冻结记账中的应用 UID，核对同一 PID 的 UID、启动时刻与成员 cgroup；来源=$kernelEvidenceSource。" +
                        "这仅确认官方记账与该进程真实冻结状态共现，不证明冻结由 Millet 触发、后续会自动调度或所有目标应用已冻结；未进行主动冻结测试。"
                kernelFreezer == RootHelper.KernelFreezer.NONE ->
                    "类型报告未发现接口，但不能据此证明内核不支持或 Greeze 存在替代机制。本项没有可确认的真实冻结证据。"
                else ->
                    "本次未收到与 Greeze 官方冻结记账 UID 及同一应用 PID 关联的完成冻结状态或精确 freezer wchan。" +
                        "无关联样本、读取权限受限、采样未覆盖与旧模块缺少证据字段均保持未确认；不相关 AOSP 缓存应用冻结或内核线程不能替代关联证据；" +
                        "目录、控制文件存在及 cgroup.freeze=1 请求值不算冻结成功，也不能判定内核必然不支持。"
            },
            suggestion = if (confirmedKernelFreezer) null else "保持后台省电策略运行，出现官方记账中的下一次冻结后点「重新检测」；本项只读现有记账，不做主动冻结，也不必为此重启"
        )
    )

    // 8. 当前墓碑冻结 UID 监测
    val frozenCount = runtimeStatus.frozenUids.distinct().size
    entries.add(
        HookDiagnosticEntry(
            id = "frozen_monitor",
            title = "Greeze 冻结记账",
            targetClassOrMethod = "GreezeManagerService#getFrozenUids",
            level = if (runtimeStatus.greezeReady) HookDiagnosticLevel.PASSED else HookDiagnosticLevel.WARNING,
            summaryText = "$frozenCount 个 UID 在记账中",
            detailText = if (frozenCount > 0)
                "Greeze 返回 $frozenCount 个记账 UID，不等同于已确认冻结的应用数；本项未测量 CPU。实际进程状态以快照为准。"
            else
                "当前记账列表为空；不能据此判断后台进程未冻结或系统冻结功能异常。"
        )
    )

    return entries
}

private fun hasPositiveCoverage(wire: HookInstallRegistry.Wire): Boolean {
    val uninstalled = wire.unsupported + wire.gated + wire.failed
    return wire.hasData && wire.installed > 0 && wire.total >= wire.installed &&
        uninstalled.size == uninstalled.toSet().size &&
        wire.installed.toLong() + uninstalled.size == wire.total.toLong()
}

/**
 * 生成「Hook 安装点覆盖」条目。
 *
 * 数量来自两侧进程的真实账本（[io.github.univers629.millet.core.HookInstallRegistry]），
 * 而不是写死的常量。要求模块与 App 都较新才会两者齐备：缺数据时显示「未上报」，
 * 不把 0 当作结果。
 */
private fun buildCoverageEntry(
    runtimeStatus: PowerKeeperBinderClient.RuntimeStatusSnapshot
): HookDiagnosticEntry {
    val coverage = runtimeStatus.hookCoverage
    if (!coverage.hasData) {
        return HookDiagnosticEntry(
            id = "hook_coverage",
            title = "Hook 安装点覆盖",
            targetClassOrMethod = "HookInstallRegistry (system_server + powerkeeper)",
            level = HookDiagnosticLevel.WARNING,
            summaryText = "未上报",
            detailText = "当前连接的模块尚未回传安装点账本（该字段随模块升级加入）。" +
                "这里保持未知而不是显示 0，避免把「查不到」误报成「一个都没装上」。",
            suggestion = "更新模块后点「重新检测」；目标进程仍在跑旧代代码时点「热重载」，无需重启"
        )
    }

    val total = coverage.totalCount
    val installed = coverage.installedCount
    val failed = coverage.failedIds
    val bothSidesReported = hasPositiveCoverage(coverage.sys) && hasPositiveCoverage(coverage.pk)
    val level = if (bothSidesReported && failed.isEmpty()) HookDiagnosticLevel.PASSED else HookDiagnosticLevel.WARNING

    val detail = buildString {
        if (coverage.sys.hasData) {
            append("系统侧 ").append(coverage.sys.installed).append('/').append(coverage.sys.total)
        } else {
            append("系统侧 未上报")
        }
        append("，电量与性能侧 ")
        if (coverage.pk.hasData) {
            append(coverage.pk.installed).append('/').append(coverage.pk.total)
        } else {
            append("未上报")
        }
        val unsupported = coverage.unsupportedIds
        if (unsupported.isNotEmpty()) {
            append("。本版本没有对应宿主类/方法而跳过：").append(unsupported.joinToString("、"))
        }
        val gated = coverage.gatedIds
        if (gated.isNotEmpty()) {
            append("。版本门控不满足而跳过：").append(gated.joinToString("、"))
        }
        if (failed.isNotEmpty()) {
            append("。安装时报错：").append(failed.joinToString("、"))
        }
        if (bothSidesReported && installed == total && unsupported.isEmpty() && gated.isEmpty() && failed.isEmpty()) {
            append("。上报点位均已注册，尚未验证各项运行行为。")
        }
        if (!bothSidesReported) {
            append("。两侧账本未齐备、数量无效或有效注册数为零，不能按全部通过处理。")
        }
    }

    return HookDiagnosticEntry(
        id = "hook_coverage",
        title = "Hook 安装点覆盖",
        targetClassOrMethod = "HookInstallRegistry (system_server + powerkeeper)",
        level = level,
        summaryText = "$installed/$total 个安装点已注册",
        detailText = detail,
        suggestion = when {
            failed.isNotEmpty() -> "查看本地日志 /data/system/XMillet/log 中对应点位的失败原因"
            !bothSidesReported -> "核对系统侧与电量与性能侧的完整安装账本"
            else -> null
        }
    )
}

/**
 * 依据 Droidspaces 规范设计、遵循 Miuix 规范的 Hook 点位与运行环境检测弹窗。
 */
@Composable
fun HookCheckDialogMiuix(
    show: Boolean,
    runtimeStatus: PowerKeeperBinderClient.RuntimeStatusSnapshot,
    kernelFreezer: RootHelper.KernelFreezer = RootHelper.KernelFreezer.UNKNOWN,
    frameworkState: XposedPrefsBridge.XposedFrameworkState = XposedPrefsBridge.XposedFrameworkState(),
    onRequestScope: (() -> Unit)? = null,
    onHotReloadStale: (() -> Unit)? = null,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    kernelEvidenceConfirmed: Boolean = false,
    kernelEvidenceSource: String = "UNCONFIRMED",
    milletBackendState: String = "UNCONFIRMED"
) {
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    // 居中宽屏/横屏弹窗（如平板横屏）可用高度明显小于竖屏底部弹窗，须以容器上限 + weight(1f)
    // 优先保证头部总览与底部操作栏不被裁剪或与 LazyColumn 重叠。
    val isCompactOrWideWindow =
        configuration.screenHeightDp < 500 || configuration.screenWidthDp > configuration.screenHeightDp
    val maxDialogBodyHeight = if (isCompactOrWideWindow) {
        (screenHeight * 0.56f).coerceIn(200.dp, 460.dp)
    } else {
        (screenHeight * 0.68f).coerceIn(260.dp, 620.dp)
    }
    val diagnostics = remember(runtimeStatus, kernelFreezer, frameworkState, kernelEvidenceConfirmed, kernelEvidenceSource, milletBackendState) {
        generateHookDiagnostics(runtimeStatus, kernelFreezer, frameworkState, kernelEvidenceConfirmed, kernelEvidenceSource, milletBackendState)
    }
    // 仅辅助框架信息可选；官方策略链路与真实冻结证据始终参与总览。
    val requiredDiagnostics = diagnostics.filterNot { it.optional }
    val passedCount = requiredDiagnostics.count { it.level == HookDiagnosticLevel.PASSED }
    val totalCount = requiredDiagnostics.size

    val overallLevel = when {
        requiredDiagnostics.all { it.level == HookDiagnosticLevel.PASSED } -> HookDiagnosticLevel.PASSED
        requiredDiagnostics.any { it.level == HookDiagnosticLevel.FAILED } -> HookDiagnosticLevel.FAILED
        else -> HookDiagnosticLevel.WARNING
    }

    WindowDialog(
        show = show,
        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top)),
        title = "运行环境与服务检测",
        onDismissRequest = onDismiss,
        content = {
            val dismissState = LocalDismissState.current

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxDialogBodyHeight)
                    .padding(top = 4.dp)
            ) {
                // 1. 顶部总览状态卡片 (对齐 Droidspaces 状态头)
                OverallStatusHeader(
                    level = overallLevel,
                    passedCount = passedCount,
                    totalCount = totalCount
                )

                Spacer(Modifier.height(12.dp))

                // 2. 诊断项目列表（占剩余高度，避免挤压底部按钮）
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(diagnostics, key = { it.id }) { item ->
                        DiagnosticItemCard(item = item)
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 2.5 按需显示 LibXposed 快捷修复操作（缺失作用域授权 / 过期目标进程热重载）
                val showRequestScopeAction = onRequestScope != null &&
                    frameworkState.isServiceConnected &&
                    frameworkState.missingScopes.isNotEmpty()
                val showHotReloadAction = onHotReloadStale != null &&
                    frameworkState.isServiceConnected &&
                    frameworkState.staleTargets.isNotEmpty()
                if (showRequestScopeAction || showHotReloadAction) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (showRequestScopeAction) {
                            TextButton(
                                text = "授权作用域",
                                onClick = onRequestScope,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (showHotReloadAction) {
                            TextButton(
                                text = "热重载 (${frameworkState.staleTargets.size})",
                                onClick = onHotReloadStale,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                // 3. 底部操作栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        text = "重新检测",
                        onClick = onRefresh,
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(Modifier.width(12.dp))

                    TextButton(
                        text = "完成",
                        onClick = {
                            onDismiss()
                            dismissState?.invoke()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    )
}

@Composable
private fun OverallStatusHeader(
    level: HookDiagnosticLevel,
    passedCount: Int,
    totalCount: Int
) {
    val dark = isInDarkTheme()
    val containerColor = when (level) {
        HookDiagnosticLevel.PASSED -> if (dark) Color(0xFF1B3B24) else Color(0xFFE4F8E9)
        HookDiagnosticLevel.WARNING -> if (dark) Color(0xFF3E2F1B) else Color(0xFFFFF2DC)
        HookDiagnosticLevel.FAILED -> if (dark) Color(0xFF381515) else Color(0xFFFDE4E4)
    }

    val iconColor = when (level) {
        HookDiagnosticLevel.PASSED -> Color(0xFF34C759)
        HookDiagnosticLevel.WARNING -> Color(0xFFFF9500)
        HookDiagnosticLevel.FAILED -> Color(0xFFFF3B30)
    }

    // 浅色模式下深色卡片会显得突兀，正文改用与底色配套的深色调。
    val titleColor = if (dark) {
        Color.White
    } else {
        when (level) {
            HookDiagnosticLevel.PASSED -> Color(0xFF164A29)
            HookDiagnosticLevel.WARNING -> Color(0xFF7A4A00)
            HookDiagnosticLevel.FAILED -> Color(0xFF8C1D18)
        }
    }
    val subtitleColor = titleColor.copy(alpha = 0.75f)

    val titleText = when (level) {
        HookDiagnosticLevel.PASSED -> "必要检测项均已确认"
        HookDiagnosticLevel.WARNING -> "存在未确认或未支持项"
        HookDiagnosticLevel.FAILED -> "存在检测失败项"
    }

    val tagText = when (level) {
        HookDiagnosticLevel.PASSED -> "检测通过"
        HookDiagnosticLevel.WARNING -> "待确认"
        HookDiagnosticLevel.FAILED -> "需核对"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor)
            .border(1.dp, iconColor.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (level) {
                    HookDiagnosticLevel.PASSED -> Icons.Rounded.CheckCircle
                    HookDiagnosticLevel.WARNING -> Icons.Rounded.Warning
                    HookDiagnosticLevel.FAILED -> Icons.Rounded.Cancel
                },
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(32.dp)
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = titleText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = titleColor
                    )
                    StatusTagMiuix(
                        label = tagText,
                        backgroundColor = iconColor.copy(alpha = 0.25f),
                        contentColor = iconColor
                    )
                }

                Spacer(Modifier.height(2.dp))

                Text(
                    text = "$totalCount 项必要检测中通过 $passedCount 项；辅助项单独列示，不代表所有应用已冻结",
                    fontSize = 12.sp,
                    color = subtitleColor
                )
            }
        }
    }
}

@Composable
private fun DiagnosticItemCard(item: HookDiagnosticEntry) {
    val statusColor = when (item.level) {
        HookDiagnosticLevel.PASSED -> Color(0xFF34C759)
        HookDiagnosticLevel.WARNING -> Color(0xFFFF9500)
        HookDiagnosticLevel.FAILED -> Color(0xFFFF3B30)
    }

    val tagLabel = when (item.level) {
        HookDiagnosticLevel.PASSED -> "PASS"
        HookDiagnosticLevel.WARNING -> "WARN"
        HookDiagnosticLevel.FAILED -> "FAIL"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = when (item.level) {
                        HookDiagnosticLevel.PASSED -> Icons.Rounded.CheckCircle
                        HookDiagnosticLevel.WARNING -> Icons.Rounded.Warning
                        HookDiagnosticLevel.FAILED -> Icons.Rounded.Cancel
                    },
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(20.dp)
                )

                Spacer(Modifier.width(10.dp))

                Text(
                    text = item.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )

                StatusTagMiuix(
                    label = tagLabel,
                    backgroundColor = statusColor.copy(alpha = 0.18f),
                    contentColor = statusColor
                )
            }

            Spacer(Modifier.height(6.dp))

            // 目标类或挂载点代码签名
            Text(
                text = item.targetClassOrMethod,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(colorScheme.surface.copy(alpha = 0.5f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )

            Spacer(Modifier.height(6.dp))

            // 状态描述
            Text(
                text = item.detailText,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = colorScheme.onSurfaceVariantSummary
            )

            // 操作建议提示
            if (item.suggestion != null) {
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(statusColor.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = MiuixIcons.Info,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = item.suggestion,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = statusColor
                    )
                }
            }
        }
    }
}
