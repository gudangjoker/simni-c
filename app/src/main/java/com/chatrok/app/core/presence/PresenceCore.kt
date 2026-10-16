package com.chatrok.app.core.presence

import com.chatrok.app.core.common.DateTimeUtils
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Logika inti kehadiran (Presence) dan indikator mengetik (Typing) untuk SIMNI-C (CHATROK ENGINE).
 *
 * Mengacu langsung pada js/chat-presence-core.mjs dan Master Context Addendum A8:
 * - HEARTBEAT_INTERVAL_MS: 30000 (30 detik — interval update chatPresence/{uid})
 * - HEARTBEAT_TIMEOUT_MS:  65000 (65 detik — user dianggap offline jika heartbeat melebihi batas ini)
 * - TYPING_DEBOUNCE_MS:     2500 (debounce ketikan pengguna -> set typing=true)
 * - TYPING_THROTTLE_MS:     3000 (throttle penulisan Firestore max 1 kali per 3 detik)
 * - TYPING_EXPIRE_MS:       5000 (auto-clear typing=false jika idle melebihi 5 detik)
 */
object PresenceCore {

    const val HEARTBEAT_INTERVAL_MS: Long = 30_000L
    const val HEARTBEAT_TIMEOUT_MS: Long = 65_000L
    const val TYPING_DEBOUNCE_MS: Long = 2_500L
    const val TYPING_THROTTLE_MS: Long = 3_000L
    const val TYPING_EXPIRE_MS: Long = 5_000L

    /**
     * Evaluasi status kehadiran online/offline factoring in heartbeat stale detection.
     *
     * Aturan evaluasi:
     * 1. Jika online != true -> offline, lastSeen dari dokumen
     * 2. Jika online == true DAN updatedAt ada DAN (now - updatedAt) > 65000 -> offline (stale heartbeat)
     * 3. Jika online == true DAN (tidak ada updatedAt ATAU (now - updatedAt) <= 65000) -> online
     *
     * Mengembalikan Pair<isOnline, lastSeenTimestamp>.
     */
    fun computePresenceState(
        online: Boolean,
        lastSeenMs: Long?,
        updatedAtMs: Long?,
        nowMs: Long = System.currentTimeMillis()
    ): Pair<Boolean, Long> {
        val fallbackLastSeen = lastSeenMs ?: updatedAtMs ?: nowMs
        if (!online) {
            return Pair(false, fallbackLastSeen)
        }

        if (updatedAtMs != null && (nowMs - updatedAtMs) > HEARTBEAT_TIMEOUT_MS) {
            // Heartbeat usang / terputus (stale) -> anggap offline
            return Pair(false, updatedAtMs)
        }

        return Pair(true, fallbackLastSeen)
    }

    /**
     * Evaluasi status mengetik (typing).
     *
     * Aturan evaluasi:
     * 1. Jika typing != true -> false
     * 2. Jika typing == true DAN updatedAt ada DAN (now - updatedAt) > 5000 -> false (expired typing)
     * 3. Sebaliknya -> true
     */
    fun computeTypingState(
        typing: Boolean,
        updatedAtMs: Long?,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (!typing) return false
        if (updatedAtMs != null && (nowMs - updatedAtMs) > TYPING_EXPIRE_MS) {
            return false
        }
        return true
    }

    /**
     * Mengecek apakah diperbolehkan mengirim heartbeat mengetik (throttling 3 detik).
     */
    fun shouldSendTypingHeartbeat(
        lastSentAtMs: Long?,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (lastSentAtMs == null) return true
        return (nowMs - lastSentAtMs) >= TYPING_THROTTLE_MS
    }

    /**
     * Format status "terakhir dilihat" dalam bahasa Indonesia natural gaya WhatsApp.
     *
     * Contoh:
     * - "terakhir dilihat hari ini pukul 14.30"
     * - "terakhir dilihat kemarin pukul 09.15"
     * - "terakhir dilihat 12 Sep pukul 20.00"
     * - "terakhir dilihat 12 Sep 2025 pukul 20.00"
     */
    fun formatLastSeen(
        lastSeenMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): String {
        if (lastSeenMs <= 0L) return "Offline"

        val lastSeenZdt = DateTimeUtils.toZonedDateTime(lastSeenMs)
        val nowZdt = DateTimeUtils.toZonedDateTime(nowMs)

        val lastSeenDate = lastSeenZdt.toLocalDate()
        val today = nowZdt.toLocalDate()

        val timeStr = lastSeenZdt.format(DateTimeFormatter.ofPattern("HH:mm", DateTimeUtils.INDONESIA_LOCALE))

        return when {
            lastSeenDate.isEqual(today) -> {
                "terakhir dilihat hari ini pukul $timeStr"
            }
            lastSeenDate.isEqual(today.minusDays(1)) -> {
                "terakhir dilihat kemarin pukul $timeStr"
            }
            lastSeenDate.year == today.year -> {
                val dateStr = lastSeenZdt.format(DateTimeFormatter.ofPattern("d MMM", DateTimeUtils.INDONESIA_LOCALE))
                "terakhir dilihat $dateStr pukul $timeStr"
            }
            else -> {
                val dateStr = lastSeenZdt.format(DateTimeFormatter.ofPattern("d MMM yyyy", DateTimeUtils.INDONESIA_LOCALE))
                "terakhir dilihat $dateStr pukul $timeStr"
            }
        }
    }
}
