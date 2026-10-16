package com.chatrok.app.core.presence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class PresenceCoreTest {

    @Test
    fun testComputePresenceOnlineWithFreshHeartbeat() {
        val now = 1700000100000L
        val updatedAt = now - 20_000L // 20s ago, well under 65s timeout

        val (isOnline, lastSeen) = PresenceCore.computePresenceState(
            online = true,
            lastSeenMs = now - 1000L,
            updatedAtMs = updatedAt,
            nowMs = now
        )

        assertTrue(isOnline)
        assertEquals(now - 1000L, lastSeen)
    }

    @Test
    fun testComputePresenceOnlineWithStaleHeartbeatBecomesOffline() {
        val now = 1700000100000L
        val updatedAt = now - 70_000L // 70s ago, exceeds 65s HEARTBEAT_TIMEOUT_MS

        val (isOnline, lastSeen) = PresenceCore.computePresenceState(
            online = true,
            lastSeenMs = now - 1000L,
            updatedAtMs = updatedAt,
            nowMs = now
        )

        assertFalse(isOnline)
        assertEquals(updatedAt, lastSeen)
    }

    @Test
    fun testComputePresenceOfflineExplicit() {
        val now = 1700000100000L
        val lastSeenDoc = now - 5000L

        val (isOnline, lastSeen) = PresenceCore.computePresenceState(
            online = false,
            lastSeenMs = lastSeenDoc,
            updatedAtMs = now - 2000L,
            nowMs = now
        )

        assertFalse(isOnline)
        assertEquals(lastSeenDoc, lastSeen)
    }

    @Test
    fun testComputeTypingState() {
        val now = 1700000100000L

        // Typing true with recent update (2s ago)
        assertTrue(PresenceCore.computeTypingState(typing = true, updatedAtMs = now - 2000L, nowMs = now))

        // Typing true with expired update (> 5s ago)
        assertFalse(PresenceCore.computeTypingState(typing = true, updatedAtMs = now - 6000L, nowMs = now))

        // Typing false
        assertFalse(PresenceCore.computeTypingState(typing = false, updatedAtMs = now - 1000L, nowMs = now))
    }

    @Test
    fun testTypingHeartbeatThrottling() {
        val now = 1700000100000L

        // First heartbeat (no prior sent)
        assertTrue(PresenceCore.shouldSendTypingHeartbeat(lastSentAtMs = null, nowMs = now))

        // Sent 1 second ago -> throttled (< 3s)
        assertFalse(PresenceCore.shouldSendTypingHeartbeat(lastSentAtMs = now - 1000L, nowMs = now))

        // Sent 3.5 seconds ago -> allowed (>= 3s)
        assertTrue(PresenceCore.shouldSendTypingHeartbeat(lastSentAtMs = now - 3500L, nowMs = now))
    }

    @Test
    fun testFormatLastSeenIndonesianPhrasing() {
        val zone = ZoneId.of("Asia/Jakarta")
        val fixedNow = ZonedDateTime.of(2025, 9, 15, 14, 30, 0, 0, zone).toInstant().toEpochMilli()

        // Same day: 2 hours ago
        val twoHoursAgo = ZonedDateTime.of(2025, 9, 15, 12, 15, 0, 0, zone).toInstant().toEpochMilli()
        val formattedToday = PresenceCore.formatLastSeen(twoHoursAgo, fixedNow)
        assertTrue(formattedToday.startsWith("terakhir dilihat hari ini pukul"))
        assertTrue(formattedToday.endsWith("12:15"))

        // Yesterday
        val yesterday = ZonedDateTime.of(2025, 9, 14, 9, 45, 0, 0, zone).toInstant().toEpochMilli()
        val formattedYesterday = PresenceCore.formatLastSeen(yesterday, fixedNow)
        assertTrue(formattedYesterday.startsWith("terakhir dilihat kemarin pukul"))
        assertTrue(formattedYesterday.endsWith("09:45"))
    }
}
