package io.github.univers629.millet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreezeStatsCodecTest {

    @Test
    fun `round trip snapshot preserves all fields and special characters`() {
        val specialReason = "reason|with\ttabs\nnewlines\rCR,semi;eq=col:pct%25 and spaces!"
        val specialSourceNote = "降级说明|包含\t制表符\n换行%7C测试"

        val snapshot = FreezeStatsSnapshotData(
            window = StatsWindow.WEEK,
            enabled = true,
            sinceMillis = 1_700_000_000_000L,
            generatedAtMillis = 1_700_086_400_000L,
            freezeCount = 42,
            wakeByCategory = mapOf(
                WakeCategory.USER_OPEN to 5,
                WakeCategory.NETWORK to 18,
                WakeCategory.BINDER to 9,
            ),
            screenOffWakeCount = 14,
            screenOffMillis = 28_800_000L,
            interceptByRule = mapOf(
                InterceptRule.IGNORE_NETWORK to 11,
                InterceptRule.NO_CHARGE_THAW to 3,
            ),
            protectedCount = 7,
            killBlockedCount = 2,
            frozenMillisTotal = 54_000_000L,
            buckets = listOf(
                StatsTimeBucket("周一", screenOnWakes = 10, screenOffWakes = 4),
                StatsTimeBucket("周二|特殊", screenOnWakes = 8, screenOffWakes = 6),
            ),
            apps = listOf(
                AppFreezeStatsData(
                    uid = 10123,
                    packageName = "com.example.chat",
                    freezeCount = 20,
                    wakeByCategory = mapOf(
                        WakeCategory.USER_OPEN to 2,
                        WakeCategory.NETWORK to 12,
                    ),
                    interceptByRule = mapOf(InterceptRule.IGNORE_NETWORK to 6),
                    frozenMillis = 30_000_000L,
                    observedMillis = 86_400_000L,
                    protectedCount = 3,
                    killBlockedCount = 1,
                    topRawReasons = listOf(
                        specialReason to 9,
                        " Activity Start!" to 2,
                        "PACKET" to 1,
                    ),
                ),
            ),
            recent = listOf(
                StatsRecentEventData(
                    timeMillis = 1_700_086_399_000L,
                    uid = 10123,
                    packageName = "com.example.chat",
                    kind = StatsEventKind.THAW,
                    category = WakeCategory.NETWORK,
                    rule = null,
                    rawReason = specialReason,
                ),
                StatsRecentEventData(
                    timeMillis = 1_700_086_300_000L,
                    uid = 1000,
                    packageName = "android",
                    kind = StatsEventKind.INTERCEPT,
                    category = null,
                    rule = InterceptRule.NO_CHARGE_THAW,
                    rawReason = "battery charging\n|test",
                ),
            ),
            memoryBytes = 32768L,
            sourceNote = specialSourceNote,
        )

        val encoded = FreezeStatsCodec.encode(snapshot)
        val decoded = FreezeStatsCodec.decode(encoded)

        assertNotNull(decoded)
        assertEquals(snapshot, decoded)
    }

    @Test
    fun `round trip disabled empty snapshot with null sourceNote`() {
        val snapshot = FreezeStatsSnapshotData(
            window = StatsWindow.BOOT,
            enabled = false,
            sinceMillis = 1_700_000_000_000L,
            generatedAtMillis = 1_700_000_123_000L,
            freezeCount = 0,
            wakeByCategory = emptyMap(),
            screenOffWakeCount = 0,
            screenOffMillis = 0L,
            interceptByRule = emptyMap(),
            protectedCount = 0,
            killBlockedCount = 0,
            frozenMillisTotal = 0L,
            buckets = emptyList(),
            apps = emptyList(),
            recent = emptyList(),
            memoryBytes = 4096L,
            sourceNote = null,
        )

        val decoded = FreezeStatsCodec.decode(FreezeStatsCodec.encode(snapshot))
        assertNotNull(decoded)
        assertFalse(decoded!!.enabled)
        assertNull(decoded.sourceNote)
        assertTrue(decoded.isEmpty)
        assertEquals(snapshot, decoded)
    }

    @Test
    fun `decode returns null on null blank wrong version or missing meta`() {
        assertNull(FreezeStatsCodec.decode(null))
        assertNull(FreezeStatsCodec.decode(""))
        assertNull(FreezeStatsCodec.decode("   \n  "))
        assertNull(FreezeStatsCodec.decode("v2\nM|TODAY|1|0|0|0|0|0|0|0|0|0|0|||\n"))
        assertNull(FreezeStatsCodec.decode("v1\nB|00|1|2\n"))
        assertNull(FreezeStatsCodec.decode("v1\nM|BAD_WINDOW|1|0|0|0|0|0|0|0|0|0|0|||\n"))
        assertNull(FreezeStatsCodec.decode("v1\nM|TODAY|bad_bool|0|0|0|0|0|0|0|0|0|0|||\n"))
    }

    @Test
    fun `decode skips malformed bucket app and recent lines while preserving valid ones`() {
        val valid = FreezeStatsSnapshotData(
            window = StatsWindow.TODAY,
            enabled = true,
            sinceMillis = 1000L,
            generatedAtMillis = 2000L,
            freezeCount = 3,
            wakeByCategory = mapOf(WakeCategory.NETWORK to 2),
            screenOffWakeCount = 1,
            screenOffMillis = 500L,
            interceptByRule = mapOf(InterceptRule.BLOCK_THAW to 1),
            protectedCount = 1,
            killBlockedCount = 0,
            frozenMillisTotal = 900L,
            buckets = listOf(StatsTimeBucket("00", 1, 1)),
            apps = listOf(
                AppFreezeStatsData(
                    uid = 10100,
                    packageName = "com.valid.app",
                    freezeCount = 3,
                    wakeByCategory = mapOf(WakeCategory.NETWORK to 2),
                    interceptByRule = emptyMap(),
                    frozenMillis = 900L,
                    observedMillis = 1000L,
                    topRawReasons = listOf("PACKET" to 2),
                )
            ),
            recent = listOf(
                StatsRecentEventData(1500L, 10100, "com.valid.app", StatsEventKind.FREEZE, rawReason = "IMMOBULUS")
            ),
            memoryBytes = 8192L,
            sourceNote = "",
        )

        val payload = buildString {
            append(FreezeStatsCodec.encode(valid))
            append("UNKNOWN|ignored|line\n")
            append("B|broken_bucket|not_an_int|3\n")
            append("B|short_bucket\n")
            append("A|not_uid|pkg|1|2|3|4|5|||\n")
            append("R|bad_time|10100|pkg|FREEZE|||\n")
            append("R|1600|10100|pkg|UNKNOWN_KIND|||\n")
        }

        val decoded = FreezeStatsCodec.decode(payload)
        assertNotNull(decoded)
        assertEquals("", decoded!!.sourceNote)
        assertEquals(1, decoded.buckets.size)
        assertEquals(1, decoded.apps.size)
        assertEquals(1, decoded.recent.size)
        assertEquals("com.valid.app", decoded.apps[0].packageName)
    }

    @Test
    fun `unescape handles malformed percent sequences without throwing`() {
        assertEquals("%ZZ%1%", FreezeStatsCodec.unescape("%ZZ%1%"))
        assertEquals("abc|def\t%25", FreezeStatsCodec.unescape(FreezeStatsCodec.escape("abc|def\t%25")))
    }

    @Test
    fun `storage codec round trips persisted state and tolerates malformed lines`() {
        val hourlyOn = IntArray(24) { if (it == 8) 5 else 0 }
        val hourlyOff = IntArray(24) { if (it == 2) 3 else 0 }
        val state = FreezeStatsCodec.PersistedStatsState(
            baselineSinceMillis = 1_700_000_000_000L,
            bootSinceMillis = 1_700_050_000_000L,
            bootWallClockMillis = 1_700_049_000_000L,
            bootElapsedMillis = 12_345L,
            savedAtMillis = 1_700_060_000_000L,
            bootId = "boot-uuid-123",
            boot = FreezeStatsCodec.PersistedBootData(
                freezeCount = 4,
                screenOffWakeCount = 1,
                screenOffMillis = 2_000L,
                protectedCount = 2,
                killBlockedCount = 1,
                frozenMillisTotal = 15_000L,
                wakeByCategory = mapOf(WakeCategory.BINDER to 2),
                interceptByRule = mapOf(InterceptRule.FORBID_SYNC_BINDER to 1),
                apps = listOf(
                    FreezeStatsCodec.PersistedAppData(
                        uid = 10200,
                        packageName = "com.test.app",
                        freezeCount = 4,
                        frozenMillis = 15_000L,
                        protectedCount = 2,
                        killBlockedCount = 1,
                        wakeByCategory = mapOf(WakeCategory.BINDER to 2),
                        interceptByRule = mapOf(InterceptRule.FORBID_SYNC_BINDER to 1),
                        rawReasons = mapOf("Sync Binder4|x" to 3),
                    )
                ),
            ),
            days = listOf(
                FreezeStatsCodec.PersistedDayData(
                    epochDay = 19_678L,
                    freezeCount = 10,
                    screenOffWakeCount = 3,
                    screenOffMillis = 30_000L,
                    protectedCount = 2,
                    killBlockedCount = 1,
                    frozenMillisTotal = 60_000L,
                    wakeByCategory = mapOf(WakeCategory.USER_OPEN to 5, WakeCategory.NETWORK to 3),
                    interceptByRule = mapOf(InterceptRule.IGNORE_NETWORK to 2),
                    hourlyScreenOnWakes = hourlyOn,
                    hourlyScreenOffWakes = hourlyOff,
                    apps = listOf(
                        FreezeStatsCodec.PersistedAppData(
                            uid = 10200,
                            packageName = "com.test.app",
                            freezeCount = 10,
                            frozenMillis = 60_000L,
                            protectedCount = 2,
                            killBlockedCount = 1,
                            wakeByCategory = mapOf(WakeCategory.USER_OPEN to 5, WakeCategory.NETWORK to 3),
                            interceptByRule = mapOf(InterceptRule.IGNORE_NETWORK to 2),
                            rawReasons = mapOf("PACKET" to 3, " Activity Start!" to 5),
                        )
                    ),
                )
            ),
            activeFrozenSinceByUid = mapOf(10200 to 1_700_059_500_000L),
            recent = listOf(
                StatsRecentEventData(
                    timeMillis = 1_700_059_900_000L,
                    uid = 10200,
                    packageName = "com.test.app",
                    kind = StatsEventKind.FREEZE,
                    rawReason = "IMMOBULUS",
                )
            ),
        )

        val encoded = FreezeStatsCodec.encodeStorage(state) + "D|bad_day|x\nDA|19678|bad_uid\nF|bad|time\n"
        val decoded = FreezeStatsCodec.decodeStorage(encoded)

        assertNotNull(decoded)
        assertEquals(state.baselineSinceMillis, decoded!!.baselineSinceMillis)
        assertEquals(state.bootId, decoded.bootId)
        assertEquals(state.activeFrozenSinceByUid, decoded.activeFrozenSinceByUid)
        assertEquals(state.recent, decoded.recent)
        assertEquals(1, decoded.days.size)
        assertEquals(5, decoded.days[0].hourlyScreenOnWakes[8])
        assertEquals(3, decoded.days[0].hourlyScreenOffWakes[2])
        assertEquals(3, decoded.boot!!.apps[0].rawReasons["Sync Binder4|x"])
        assertNull(FreezeStatsCodec.decodeStorage("v99\n"))
        assertNull(FreezeStatsCodec.decodeStorage("v1\nBT|1|0|0|0|0|0||\n"))
        assertEquals("v2", FreezeStatsCodec.STORAGE_VERSION)
        assertEquals("v2", encoded.lineSequence().first())
    }
}
