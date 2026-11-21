package io.github.univers629.millet.core

/**
 * 墓碑统计跨进程快照与本地持久化（`v1` 行协议）编解码器。
 *
 * 协议结构（仿照 [ProcessSnapshotCodec]）：
 * 1. IPC 快照协议：
 * ```
 * v1
 * M|window|enabled|sinceMillis|generatedAtMillis|freezeCount|screenOffWakeCount|screenOffMillis|protectedCount|killBlockedCount|frozenMillisTotal|memoryBytes|hasSourceNote|sourceNote|wakeMap|interceptMap
 * B|label|screenOnWakes|screenOffWakes
 * A|uid|packageName|freezeCount|frozenMillis|observedMillis|protectedCount|killBlockedCount|wakeMap|interceptMap|topRawReasons
 * R|timeMillis|uid|packageName|kind|category|rule|rawReason
 * ```
 * 2. 磁盘持久化协议（`/data/system/XMillet/stats.v1`）：
 * ```
 * v1
 * S|baselineSinceMillis|bootSinceMillis|bootWallClockMillis|bootElapsedMillis|savedAtMillis|bootId
 * BT|freezeCount|screenOffWakeCount|screenOffMillis|protectedCount|killBlockedCount|frozenMillisTotal|wakeMap|interceptMap
 * BA|uid|packageName|freezeCount|frozenMillis|protectedCount|killBlockedCount|wakeMap|interceptMap|rawReasonMap
 * D|epochDay|freezeCount|screenOffWakeCount|screenOffMillis|protectedCount|killBlockedCount|frozenMillisTotal|wakeMap|interceptMap|hourlyOn24|hourlyOff24
 * DA|epochDay|uid|packageName|freezeCount|frozenMillis|protectedCount|killBlockedCount|wakeMap|interceptMap|rawReasonMap
 * F|uid|frozenSinceMillis
 * R|timeMillis|uid|packageName|kind|category|rule|rawReason
 * ```
 *
 * 所有用户/系统文本字段均经过可逆转义（`%`, `|`, `\t`, `\n`, `\r`, `,`, `;`, `=`, `:`），
 * 确保任意原始理由字符串都不会破坏行/列边界。
 */
object FreezeStatsCodec {

    const val VERSION = "v1"

    /**
     * 磁盘持久化协议版本。1.2.1 及更早版本的冻结/唤醒口径与当前实现不同（存在重复计数），
     * v2 起旧文件会被整体视为无效并重新开始，避免把旧口径的历史带进新版本。
     */
    const val STORAGE_VERSION = "v2"

    private const val MIN_META_FIELDS = 16
    private const val MIN_BUCKET_FIELDS = 4
    private const val MIN_APP_FIELDS = 11
    private const val MIN_RECENT_FIELDS = 8

    private const val MIN_STORAGE_META_FIELDS = 7
    private const val MIN_STORAGE_BOOT_TOTAL_FIELDS = 9
    private const val MIN_STORAGE_BOOT_APP_FIELDS = 10
    private const val MIN_STORAGE_DAY_FIELDS = 12
    private const val MIN_STORAGE_DAY_APP_FIELDS = 11
    private const val MIN_STORAGE_FROZEN_FIELDS = 3

    // ==================== 持久化中间模型 ====================

    data class PersistedAppData(
        val uid: Int,
        val packageName: String,
        val freezeCount: Int,
        val frozenMillis: Long,
        val protectedCount: Int,
        val killBlockedCount: Int,
        val wakeByCategory: Map<WakeCategory, Int>,
        val interceptByRule: Map<InterceptRule, Int>,
        val rawReasons: Map<String, Int>,
    )

    data class PersistedBootData(
        val freezeCount: Int,
        val screenOffWakeCount: Int,
        val screenOffMillis: Long,
        val protectedCount: Int,
        val killBlockedCount: Int,
        val frozenMillisTotal: Long,
        val wakeByCategory: Map<WakeCategory, Int>,
        val interceptByRule: Map<InterceptRule, Int>,
        val apps: List<PersistedAppData>,
    )

    data class PersistedDayData(
        val epochDay: Long,
        val freezeCount: Int,
        val screenOffWakeCount: Int,
        val screenOffMillis: Long,
        val protectedCount: Int,
        val killBlockedCount: Int,
        val frozenMillisTotal: Long,
        val wakeByCategory: Map<WakeCategory, Int>,
        val interceptByRule: Map<InterceptRule, Int>,
        val hourlyScreenOnWakes: IntArray,
        val hourlyScreenOffWakes: IntArray,
        val apps: List<PersistedAppData>,
    )

    data class PersistedStatsState(
        val baselineSinceMillis: Long,
        val bootSinceMillis: Long,
        val bootWallClockMillis: Long,
        val bootElapsedMillis: Long,
        val savedAtMillis: Long,
        val bootId: String,
        val boot: PersistedBootData?,
        val days: List<PersistedDayData>,
        val activeFrozenSinceByUid: Map<Int, Long>,
        val recent: List<StatsRecentEventData>,
    )

    // ==================== IPC 快照编解码 ====================

    fun encode(snapshot: FreezeStatsSnapshotData): String {
        val sb = StringBuilder(4096)
        sb.append(VERSION).append('\n')
        sb.append("M|")
            .append(snapshot.window.name).append('|')
            .append(if (snapshot.enabled) '1' else '0').append('|')
            .append(snapshot.sinceMillis).append('|')
            .append(snapshot.generatedAtMillis).append('|')
            .append(snapshot.freezeCount).append('|')
            .append(snapshot.screenOffWakeCount).append('|')
            .append(snapshot.screenOffMillis).append('|')
            .append(snapshot.protectedCount).append('|')
            .append(snapshot.killBlockedCount).append('|')
            .append(snapshot.frozenMillisTotal).append('|')
            .append(snapshot.memoryBytes).append('|')
            .append(if (snapshot.sourceNote != null) '1' else '0').append('|')
            .append(escape(snapshot.sourceNote.orEmpty())).append('|')
            .append(encodeWakeMap(snapshot.wakeByCategory)).append('|')
            .append(encodeInterceptMap(snapshot.interceptByRule))
            .append('\n')

        for (bucket in snapshot.buckets) {
            sb.append("B|")
                .append(escape(bucket.label)).append('|')
                .append(bucket.screenOnWakes).append('|')
                .append(bucket.screenOffWakes)
                .append('\n')
        }

        for (app in snapshot.apps) {
            sb.append("A|")
                .append(app.uid).append('|')
                .append(escape(app.packageName)).append('|')
                .append(app.freezeCount).append('|')
                .append(app.frozenMillis).append('|')
                .append(app.observedMillis).append('|')
                .append(app.protectedCount).append('|')
                .append(app.killBlockedCount).append('|')
                .append(encodeWakeMap(app.wakeByCategory)).append('|')
                .append(encodeInterceptMap(app.interceptByRule)).append('|')
                .append(encodeReasonList(app.topRawReasons))
                .append('\n')
        }

        for (event in snapshot.recent) {
            appendRecentLine(sb, event)
        }

        return sb.toString()
    }

    fun decode(payload: String?): FreezeStatsSnapshotData? {
        if (payload.isNullOrBlank()) return null
        val lines = payload.lineSequence().iterator()
        var header: String? = null
        while (lines.hasNext()) {
            val candidate = lines.next().trim()
            if (candidate.isNotEmpty()) {
                header = candidate
                break
            }
        }
        if (header != VERSION) return null

        var meta: MetaHeader? = null
        val buckets = ArrayList<StatsTimeBucket>()
        val apps = ArrayList<AppFreezeStatsData>()
        val recent = ArrayList<StatsRecentEventData>()

        while (lines.hasNext()) {
            val line = lines.next()
            if (line.isBlank()) continue
            val parts = line.split('|')
            when (parts.firstOrNull()) {
                "M" -> if (meta == null) {
                    meta = parseMetaLine(parts)
                }
                "B" -> parseBucketLine(parts)?.let { buckets.add(it) }
                "A" -> parseAppLine(parts)?.let { apps.add(it) }
                "R" -> parseRecentLine(parts)?.let { recent.add(it) }
            }
        }

        val parsedMeta = meta ?: return null
        return FreezeStatsSnapshotData(
            window = parsedMeta.window,
            enabled = parsedMeta.enabled,
            sinceMillis = parsedMeta.sinceMillis,
            generatedAtMillis = parsedMeta.generatedAtMillis,
            freezeCount = parsedMeta.freezeCount,
            wakeByCategory = parsedMeta.wakeByCategory,
            screenOffWakeCount = parsedMeta.screenOffWakeCount,
            screenOffMillis = parsedMeta.screenOffMillis,
            interceptByRule = parsedMeta.interceptByRule,
            protectedCount = parsedMeta.protectedCount,
            killBlockedCount = parsedMeta.killBlockedCount,
            frozenMillisTotal = parsedMeta.frozenMillisTotal,
            buckets = buckets,
            apps = apps,
            recent = recent,
            memoryBytes = parsedMeta.memoryBytes,
            sourceNote = parsedMeta.sourceNote,
        )
    }

    // ==================== 本地持久化编解码 ====================

    fun encodeStorage(state: PersistedStatsState): String {
        val sb = StringBuilder(8192)
        sb.append(STORAGE_VERSION).append('\n')
        sb.append("S|")
            .append(state.baselineSinceMillis).append('|')
            .append(state.bootSinceMillis).append('|')
            .append(state.bootWallClockMillis).append('|')
            .append(state.bootElapsedMillis).append('|')
            .append(state.savedAtMillis).append('|')
            .append(escape(state.bootId))
            .append('\n')

        val boot = state.boot
        if (boot != null) {
            sb.append("BT|")
                .append(boot.freezeCount).append('|')
                .append(boot.screenOffWakeCount).append('|')
                .append(boot.screenOffMillis).append('|')
                .append(boot.protectedCount).append('|')
                .append(boot.killBlockedCount).append('|')
                .append(boot.frozenMillisTotal).append('|')
                .append(encodeWakeMap(boot.wakeByCategory)).append('|')
                .append(encodeInterceptMap(boot.interceptByRule))
                .append('\n')

            for (app in boot.apps) {
                sb.append("BA|")
                    .append(app.uid).append('|')
                    .append(escape(app.packageName)).append('|')
                    .append(app.freezeCount).append('|')
                    .append(app.frozenMillis).append('|')
                    .append(app.protectedCount).append('|')
                    .append(app.killBlockedCount).append('|')
                    .append(encodeWakeMap(app.wakeByCategory)).append('|')
                    .append(encodeInterceptMap(app.interceptByRule)).append('|')
                    .append(encodeStringCountMap(app.rawReasons))
                    .append('\n')
            }
        }

        for (day in state.days) {
            sb.append("D|")
                .append(day.epochDay).append('|')
                .append(day.freezeCount).append('|')
                .append(day.screenOffWakeCount).append('|')
                .append(day.screenOffMillis).append('|')
                .append(day.protectedCount).append('|')
                .append(day.killBlockedCount).append('|')
                .append(day.frozenMillisTotal).append('|')
                .append(encodeWakeMap(day.wakeByCategory)).append('|')
                .append(encodeInterceptMap(day.interceptByRule)).append('|')
                .append(encodeHourly24(day.hourlyScreenOnWakes)).append('|')
                .append(encodeHourly24(day.hourlyScreenOffWakes))
                .append('\n')

            for (app in day.apps) {
                sb.append("DA|")
                    .append(day.epochDay).append('|')
                    .append(app.uid).append('|')
                    .append(escape(app.packageName)).append('|')
                    .append(app.freezeCount).append('|')
                    .append(app.frozenMillis).append('|')
                    .append(app.protectedCount).append('|')
                    .append(app.killBlockedCount).append('|')
                    .append(encodeWakeMap(app.wakeByCategory)).append('|')
                    .append(encodeInterceptMap(app.interceptByRule)).append('|')
                    .append(encodeStringCountMap(app.rawReasons))
                    .append('\n')
            }
        }

        for ((uid, sinceMs) in state.activeFrozenSinceByUid) {
            sb.append("F|")
                .append(uid).append('|')
                .append(sinceMs)
                .append('\n')
        }

        for (event in state.recent) {
            appendRecentLine(sb, event)
        }

        return sb.toString()
    }

    fun decodeStorage(payload: String?): PersistedStatsState? {
        if (payload.isNullOrBlank()) return null
        val lines = payload.lineSequence().iterator()
        var header: String? = null
        while (lines.hasNext()) {
            val candidate = lines.next().trim()
            if (candidate.isNotEmpty()) {
                header = candidate
                break
            }
        }
        if (header != STORAGE_VERSION) return null

        var storageMeta: StorageMeta? = null
        var bootTotal: BootTotalParts? = null
        val bootApps = ArrayList<PersistedAppData>()
        val dayHeaders = LinkedHashMap<Long, DayHeaderParts>()
        val dayAppsByDay = LinkedHashMap<Long, MutableList<PersistedAppData>>()
        val activeFrozen = LinkedHashMap<Int, Long>()
        val recent = ArrayList<StatsRecentEventData>()

        while (lines.hasNext()) {
            val line = lines.next()
            if (line.isBlank()) continue
            val parts = line.split('|')
            when (parts.firstOrNull()) {
                "S" -> if (storageMeta == null) {
                    storageMeta = parseStorageMeta(parts)
                }
                "BT" -> if (bootTotal == null) {
                    bootTotal = parseBootTotal(parts)
                }
                "BA" -> parseStorageBootApp(parts)?.let { bootApps.add(it) }
                "D" -> parseStorageDayHeader(parts)?.let { dh ->
                    dayHeaders[dh.epochDay] = dh
                }
                "DA" -> parseStorageDayApp(parts)?.let { (epochDay, app) ->
                    dayAppsByDay.getOrPut(epochDay) { ArrayList() }.add(app)
                }
                "F" -> {
                    if (parts.size >= MIN_STORAGE_FROZEN_FIELDS) {
                        val uid = parts[1].toIntOrNull()
                        val since = parts[2].toLongOrNull()
                        if (uid != null && uid > 0 && since != null && since >= 0L) {
                            activeFrozen[uid] = since
                        }
                    }
                }
                "R" -> parseRecentLine(parts)?.let { recent.add(it) }
            }
        }

        val meta = storageMeta ?: return null
        val boot = bootTotal?.let { bt ->
            PersistedBootData(
                freezeCount = bt.freezeCount,
                screenOffWakeCount = bt.screenOffWakeCount,
                screenOffMillis = bt.screenOffMillis,
                protectedCount = bt.protectedCount,
                killBlockedCount = bt.killBlockedCount,
                frozenMillisTotal = bt.frozenMillisTotal,
                wakeByCategory = bt.wakeByCategory,
                interceptByRule = bt.interceptByRule,
                apps = bootApps,
            )
        }

        val days = dayHeaders.values.map { dh ->
            PersistedDayData(
                epochDay = dh.epochDay,
                freezeCount = dh.freezeCount,
                screenOffWakeCount = dh.screenOffWakeCount,
                screenOffMillis = dh.screenOffMillis,
                protectedCount = dh.protectedCount,
                killBlockedCount = dh.killBlockedCount,
                frozenMillisTotal = dh.frozenMillisTotal,
                wakeByCategory = dh.wakeByCategory,
                interceptByRule = dh.interceptByRule,
                hourlyScreenOnWakes = dh.hourlyOn,
                hourlyScreenOffWakes = dh.hourlyOff,
                apps = dayAppsByDay[dh.epochDay].orEmpty(),
            )
        }

        return PersistedStatsState(
            baselineSinceMillis = meta.baselineSinceMillis,
            bootSinceMillis = meta.bootSinceMillis,
            bootWallClockMillis = meta.bootWallClockMillis,
            bootElapsedMillis = meta.bootElapsedMillis,
            savedAtMillis = meta.savedAtMillis,
            bootId = meta.bootId,
            boot = boot,
            days = days,
            activeFrozenSinceByUid = activeFrozen,
            recent = recent,
        )
    }

    // ==================== 内部解析与转义工具 ====================

    private data class MetaHeader(
        val window: StatsWindow,
        val enabled: Boolean,
        val sinceMillis: Long,
        val generatedAtMillis: Long,
        val freezeCount: Int,
        val screenOffWakeCount: Int,
        val screenOffMillis: Long,
        val protectedCount: Int,
        val killBlockedCount: Int,
        val frozenMillisTotal: Long,
        val memoryBytes: Long,
        val sourceNote: String?,
        val wakeByCategory: Map<WakeCategory, Int>,
        val interceptByRule: Map<InterceptRule, Int>,
    )

    private data class StorageMeta(
        val baselineSinceMillis: Long,
        val bootSinceMillis: Long,
        val bootWallClockMillis: Long,
        val bootElapsedMillis: Long,
        val savedAtMillis: Long,
        val bootId: String,
    )

    private data class BootTotalParts(
        val freezeCount: Int,
        val screenOffWakeCount: Int,
        val screenOffMillis: Long,
        val protectedCount: Int,
        val killBlockedCount: Int,
        val frozenMillisTotal: Long,
        val wakeByCategory: Map<WakeCategory, Int>,
        val interceptByRule: Map<InterceptRule, Int>,
    )

    private data class DayHeaderParts(
        val epochDay: Long,
        val freezeCount: Int,
        val screenOffWakeCount: Int,
        val screenOffMillis: Long,
        val protectedCount: Int,
        val killBlockedCount: Int,
        val frozenMillisTotal: Long,
        val wakeByCategory: Map<WakeCategory, Int>,
        val interceptByRule: Map<InterceptRule, Int>,
        val hourlyOn: IntArray,
        val hourlyOff: IntArray,
    )

    private fun parseMetaLine(parts: List<String>): MetaHeader? {
        if (parts.size < MIN_META_FIELDS) return null
        val window = parseEnumOrNull<StatsWindow>(parts[1]) ?: return null
        val enabled = when (parts[2]) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        val sinceMillis = parts[3].toLongOrNull() ?: return null
        val generatedAtMillis = parts[4].toLongOrNull() ?: return null
        val freezeCount = (parts[5].toIntOrNull() ?: return null).coerceAtLeast(0)
        val screenOffWakeCount = (parts[6].toIntOrNull() ?: return null).coerceAtLeast(0)
        val screenOffMillis = (parts[7].toLongOrNull() ?: return null).coerceAtLeast(0L)
        val protectedCount = (parts[8].toIntOrNull() ?: return null).coerceAtLeast(0)
        val killBlockedCount = (parts[9].toIntOrNull() ?: return null).coerceAtLeast(0)
        val frozenMillisTotal = (parts[10].toLongOrNull() ?: return null).coerceAtLeast(0L)
        val memoryBytes = (parts[11].toLongOrNull() ?: return null).coerceAtLeast(0L)
        val sourceNote = when (parts[12]) {
            "1" -> unescape(parts[13])
            "0" -> null
            else -> return null
        }
        val wakeMap = decodeWakeMap(parts[14])
        val interceptMap = decodeInterceptMap(parts[15])
        return MetaHeader(
            window = window,
            enabled = enabled,
            sinceMillis = sinceMillis,
            generatedAtMillis = generatedAtMillis,
            freezeCount = freezeCount,
            screenOffWakeCount = screenOffWakeCount,
            screenOffMillis = screenOffMillis,
            protectedCount = protectedCount,
            killBlockedCount = killBlockedCount,
            frozenMillisTotal = frozenMillisTotal,
            memoryBytes = memoryBytes,
            sourceNote = sourceNote,
            wakeByCategory = wakeMap,
            interceptByRule = interceptMap,
        )
    }

    private fun parseBucketLine(parts: List<String>): StatsTimeBucket? {
        if (parts.size < MIN_BUCKET_FIELDS) return null
        val label = unescape(parts[1])
        val on = parts[2].toIntOrNull()?.coerceAtLeast(0) ?: return null
        val off = parts[3].toIntOrNull()?.coerceAtLeast(0) ?: return null
        return StatsTimeBucket(label = label, screenOnWakes = on, screenOffWakes = off)
    }

    private fun parseAppLine(parts: List<String>): AppFreezeStatsData? {
        if (parts.size < MIN_APP_FIELDS) return null
        val uid = parts[1].toIntOrNull() ?: return null
        val pkg = unescape(parts[2])
        val freezeCount = parts[3].toIntOrNull()?.coerceAtLeast(0) ?: return null
        val frozenMillis = parts[4].toLongOrNull()?.coerceAtLeast(0L) ?: return null
        val observedMillis = parts[5].toLongOrNull()?.coerceAtLeast(0L) ?: return null
        val protectedCount = parts[6].toIntOrNull()?.coerceAtLeast(0) ?: return null
        val killBlockedCount = parts[7].toIntOrNull()?.coerceAtLeast(0) ?: return null
        val wakeMap = decodeWakeMap(parts[8])
        val interceptMap = decodeInterceptMap(parts[9])
        val reasons = decodeReasonList(parts[10])
        return AppFreezeStatsData(
            uid = uid,
            packageName = pkg,
            freezeCount = freezeCount,
            wakeByCategory = wakeMap,
            interceptByRule = interceptMap,
            frozenMillis = frozenMillis,
            observedMillis = observedMillis,
            protectedCount = protectedCount,
            killBlockedCount = killBlockedCount,
            topRawReasons = reasons,
        )
    }

    private fun parseRecentLine(parts: List<String>): StatsRecentEventData? {
        if (parts.size < MIN_RECENT_FIELDS) return null
        val timeMillis = parts[1].toLongOrNull() ?: return null
        val uid = parts[2].toIntOrNull() ?: return null
        val pkg = unescape(parts[3])
        val kind = parseEnumOrNull<StatsEventKind>(parts[4]) ?: return null
        val category = if (parts[5].isEmpty()) null else parseEnumOrNull<WakeCategory>(parts[5])
        val rule = if (parts[6].isEmpty()) null else parseEnumOrNull<InterceptRule>(parts[6])
        val rawReason = unescape(parts[7])
        return StatsRecentEventData(
            timeMillis = timeMillis,
            uid = uid,
            packageName = pkg,
            kind = kind,
            category = category,
            rule = rule,
            rawReason = rawReason,
            origin = parts.getOrNull(8)?.let { parseEnumOrNull<StatsEventOrigin>(it) } ?: StatsEventOrigin.UNKNOWN,
        )
    }

    private fun parseStorageMeta(parts: List<String>): StorageMeta? {
        if (parts.size < MIN_STORAGE_META_FIELDS) return null
        val baselineSinceMillis = parts[1].toLongOrNull() ?: return null
        val bootSinceMillis = parts[2].toLongOrNull() ?: return null
        val bootWallClockMillis = parts[3].toLongOrNull() ?: return null
        val bootElapsedMillis = parts[4].toLongOrNull() ?: return null
        val savedAtMillis = parts[5].toLongOrNull() ?: return null
        val bootId = unescape(parts[6])
        return StorageMeta(
            baselineSinceMillis = baselineSinceMillis,
            bootSinceMillis = bootSinceMillis,
            bootWallClockMillis = bootWallClockMillis,
            bootElapsedMillis = bootElapsedMillis,
            savedAtMillis = savedAtMillis,
            bootId = bootId,
        )
    }

    private fun parseBootTotal(parts: List<String>): BootTotalParts? {
        if (parts.size < MIN_STORAGE_BOOT_TOTAL_FIELDS) return null
        return BootTotalParts(
            freezeCount = parts[1].toIntOrNull()?.coerceAtLeast(0) ?: return null,
            screenOffWakeCount = parts[2].toIntOrNull()?.coerceAtLeast(0) ?: return null,
            screenOffMillis = parts[3].toLongOrNull()?.coerceAtLeast(0L) ?: return null,
            protectedCount = parts[4].toIntOrNull()?.coerceAtLeast(0) ?: return null,
            killBlockedCount = parts[5].toIntOrNull()?.coerceAtLeast(0) ?: return null,
            frozenMillisTotal = parts[6].toLongOrNull()?.coerceAtLeast(0L) ?: return null,
            wakeByCategory = decodeWakeMap(parts[7]),
            interceptByRule = decodeInterceptMap(parts[8]),
        )
    }

    private fun parseStorageBootApp(parts: List<String>): PersistedAppData? {
        if (parts.size < MIN_STORAGE_BOOT_APP_FIELDS) return null
        val uid = parts[1].toIntOrNull() ?: return null
        val pkg = unescape(parts[2])
        val freezeCount = parts[3].toIntOrNull()?.coerceAtLeast(0) ?: return null
        val frozenMillis = parts[4].toLongOrNull()?.coerceAtLeast(0L) ?: return null
        val protectedCount = parts[5].toIntOrNull()?.coerceAtLeast(0) ?: return null
        val killBlockedCount = parts[6].toIntOrNull()?.coerceAtLeast(0) ?: return null
        val wakeMap = decodeWakeMap(parts[7])
        val interceptMap = decodeInterceptMap(parts[8])
        val rawReasons = decodeStringCountMap(parts[9])
        return PersistedAppData(
            uid = uid,
            packageName = pkg,
            freezeCount = freezeCount,
            frozenMillis = frozenMillis,
            protectedCount = protectedCount,
            killBlockedCount = killBlockedCount,
            wakeByCategory = wakeMap,
            interceptByRule = interceptMap,
            rawReasons = rawReasons,
        )
    }

    private fun parseStorageDayHeader(parts: List<String>): DayHeaderParts? {
        if (parts.size < MIN_STORAGE_DAY_FIELDS) return null
        val epochDay = parts[1].toLongOrNull() ?: return null
        val freezeCount = parts[2].toIntOrNull()?.coerceAtLeast(0) ?: return null
        val screenOffWakeCount = parts[3].toIntOrNull()?.coerceAtLeast(0) ?: return null
        val screenOffMillis = parts[4].toLongOrNull()?.coerceAtLeast(0L) ?: return null
        val protectedCount = parts[5].toIntOrNull()?.coerceAtLeast(0) ?: return null
        val killBlockedCount = parts[6].toIntOrNull()?.coerceAtLeast(0) ?: return null
        val frozenMillisTotal = parts[7].toLongOrNull()?.coerceAtLeast(0L) ?: return null
        val wakeMap = decodeWakeMap(parts[8])
        val interceptMap = decodeInterceptMap(parts[9])
        val hourlyOn = decodeHourly24(parts[10]) ?: return null
        val hourlyOff = decodeHourly24(parts[11]) ?: return null
        return DayHeaderParts(
            epochDay = epochDay,
            freezeCount = freezeCount,
            screenOffWakeCount = screenOffWakeCount,
            screenOffMillis = screenOffMillis,
            protectedCount = protectedCount,
            killBlockedCount = killBlockedCount,
            frozenMillisTotal = frozenMillisTotal,
            wakeByCategory = wakeMap,
            interceptByRule = interceptMap,
            hourlyOn = hourlyOn,
            hourlyOff = hourlyOff,
        )
    }

    private fun parseStorageDayApp(parts: List<String>): Pair<Long, PersistedAppData>? {
        if (parts.size < MIN_STORAGE_DAY_APP_FIELDS) return null
        val epochDay = parts[1].toLongOrNull() ?: return null
        val uid = parts[2].toIntOrNull() ?: return null
        val pkg = unescape(parts[3])
        val freezeCount = parts[4].toIntOrNull()?.coerceAtLeast(0) ?: return null
        val frozenMillis = parts[5].toLongOrNull()?.coerceAtLeast(0L) ?: return null
        val protectedCount = parts[6].toIntOrNull()?.coerceAtLeast(0) ?: return null
        val killBlockedCount = parts[7].toIntOrNull()?.coerceAtLeast(0) ?: return null
        val wakeMap = decodeWakeMap(parts[8])
        val interceptMap = decodeInterceptMap(parts[9])
        val rawReasons = decodeStringCountMap(parts[10])
        return epochDay to PersistedAppData(
            uid = uid,
            packageName = pkg,
            freezeCount = freezeCount,
            frozenMillis = frozenMillis,
            protectedCount = protectedCount,
            killBlockedCount = killBlockedCount,
            wakeByCategory = wakeMap,
            interceptByRule = interceptMap,
            rawReasons = rawReasons,
        )
    }

    private fun appendRecentLine(sb: StringBuilder, event: StatsRecentEventData) {
        sb.append("R|")
            .append(event.timeMillis).append('|')
            .append(event.uid).append('|')
            .append(escape(event.packageName)).append('|')
            .append(event.kind.name).append('|')
            .append(event.category?.name.orEmpty()).append('|')
            .append(event.rule?.name.orEmpty()).append('|')
            .append(escape(event.rawReason))
            .append('|').append(event.origin.name)
            .append('\n')
    }



    private inline fun <reified E : Enum<E>> decodeEnumCounts(raw: String): Map<E, Int> =
        raw.split(',').mapNotNull { segment ->
            val fields = segment.split('=', limit = 2)
            if (fields.size != 2) return@mapNotNull null
            val key = parseEnumOrNull<E>(fields[0]) ?: return@mapNotNull null
            val count = fields[1].toIntOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
            key to count
        }.toMap()

    private fun encodeWakeMap(map: Map<WakeCategory, Int>): String {
        if (map.isEmpty()) return ""
        return map.entries.joinToString(",") { "${it.key.name}=${it.value}" }
    }

    private fun decodeWakeMap(raw: String): Map<WakeCategory, Int> {
        if (raw.isEmpty()) return emptyMap()
        val result = LinkedHashMap<WakeCategory, Int>()
        for (segment in raw.split(',')) {
            val eq = segment.indexOf('=')
            if (eq <= 0) continue
            val cat = parseEnumOrNull<WakeCategory>(segment.substring(0, eq)) ?: continue
            val count = segment.substring(eq + 1).toIntOrNull()?.coerceAtLeast(0) ?: continue
            result[cat] = count
        }
        return result
    }

    private fun encodeInterceptMap(map: Map<InterceptRule, Int>): String {
        if (map.isEmpty()) return ""
        return map.entries.joinToString(",") { "${it.key.name}=${it.value}" }
    }

    private fun decodeInterceptMap(raw: String): Map<InterceptRule, Int> {
        if (raw.isEmpty()) return emptyMap()
        val result = LinkedHashMap<InterceptRule, Int>()
        for (segment in raw.split(',')) {
            val eq = segment.indexOf('=')
            if (eq <= 0) continue
            val rule = parseEnumOrNull<InterceptRule>(segment.substring(0, eq)) ?: continue
            val count = segment.substring(eq + 1).toIntOrNull()?.coerceAtLeast(0) ?: continue
            result[rule] = count
        }
        return result
    }

    private fun encodeReasonList(list: List<Pair<String, Int>>): String {
        if (list.isEmpty()) return ""
        return list.joinToString(";") { "${escape(it.first)}=${it.second}" }
    }

    private fun decodeReasonList(raw: String): List<Pair<String, Int>> {
        if (raw.isEmpty()) return emptyList()
        val result = ArrayList<Pair<String, Int>>()
        for (segment in raw.split(';')) {
            val eq = segment.indexOf('=')
            if (eq <= 0) continue
            val reason = unescape(segment.substring(0, eq))
            val count = segment.substring(eq + 1).toIntOrNull()?.coerceAtLeast(0) ?: continue
            result.add(reason to count)
        }
        return result
    }

    private fun encodeStringCountMap(map: Map<String, Int>): String {
        if (map.isEmpty()) return ""
        return map.entries.joinToString(";") { "${escape(it.key)}=${it.value}" }
    }

    private fun decodeStringCountMap(raw: String): Map<String, Int> {
        if (raw.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, Int>()
        for (segment in raw.split(';')) {
            val eq = segment.indexOf('=')
            if (eq <= 0) continue
            val key = unescape(segment.substring(0, eq))
            val count = segment.substring(eq + 1).toIntOrNull()?.coerceAtLeast(0) ?: continue
            result[key] = count
        }
        return result
    }

    private fun encodeHourly24(values: IntArray): String {
        val sb = StringBuilder(64)
        for (h in 0 until 24) {
            if (h > 0) sb.append(',')
            sb.append(values.getOrElse(h) { 0 })
        }
        return sb.toString()
    }

    private fun decodeHourly24(raw: String): IntArray? {
        if (raw.isEmpty()) return IntArray(24)
        val parts = raw.split(',')
        if (parts.size != 24) return null
        val arr = IntArray(24)
        for (i in 0 until 24) {
            arr[i] = parts[i].toIntOrNull()?.coerceAtLeast(0) ?: return null
        }
        return arr
    }

    private inline fun <reified T : Enum<T>> parseEnumOrNull(name: String): T? {
        return try {
            enumValueOf<T>(name)
        } catch (_: Throwable) {
            null
        }
    }

    internal fun escape(raw: String): String {
        if (raw.isEmpty()) return ""
        var needsEscape = false
        for (ch in raw) {
            if (ch == '%' || ch == '|' || ch == '\t' || ch == '\n' || ch == '\r' ||
                ch == ',' || ch == ';' || ch == '=' || ch == ':'
            ) {
                needsEscape = true
                break
            }
        }
        if (!needsEscape) return raw
        val sb = StringBuilder(raw.length + 8)
        for (ch in raw) {
            when (ch) {
                '%' -> sb.append("%25")
                '|' -> sb.append("%7C")
                '\t' -> sb.append("%09")
                '\n' -> sb.append("%0A")
                '\r' -> sb.append("%0D")
                ',' -> sb.append("%2C")
                ';' -> sb.append("%3B")
                '=' -> sb.append("%3D")
                ':' -> sb.append("%3A")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    internal fun unescape(encoded: String): String {
        if (!encoded.contains('%')) return encoded
        val sb = StringBuilder(encoded.length)
        var i = 0
        while (i < encoded.length) {
            val ch = encoded[i]
            if (ch == '%' && i + 2 < encoded.length) {
                val hex = encoded.substring(i + 1, i + 3)
                val code = hex.toIntOrNull(16)
                if (code != null && code in 0..0xFF) {
                    sb.append(code.toChar())
                    i += 3
                    continue
                }
            }
            sb.append(ch)
            i++
        }
        return sb.toString()
    }
}
