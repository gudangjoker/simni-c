package com.chatrok.app.core.common

import java.security.SecureRandom

object MessageIdUtils {

    private const val MESSAGE_ID_EPOCH_CEILING = 9_999_999_999_999L
    private val random = SecureRandom()

    /**
     * Menghasilkan ID pesan terurut (sortable descending).
     * Sesuai Master Context Addendum A7 dan js/chat-receipts-core.mjs:
     * Format: !{invertedTimestamp}_{randomHex24}
     */
    fun generateMessageId(nowMs: Long = System.currentTimeMillis()): String {
        val inverted = MESSAGE_ID_EPOCH_CEILING - nowMs
        val bytes = ByteArray(12)
        random.nextBytes(bytes)
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return "!${inverted}_$hex"
    }

    /**
     * Mengekstrak timestamp (milliseconds) dari format ID pesan.
     */
    fun timestampFromMessageId(messageId: String): Long? {
        val pattern = Regex("""^!(\d{13})_[0-9a-f]{24}$""")
        val match = pattern.matchEntire(messageId) ?: return null
        val inverted = match.groupValues[1].toLongOrNull() ?: return null
        val timestamp = MESSAGE_ID_EPOCH_CEILING - inverted
        return if (timestamp >= 0) timestamp else null
    }
}
