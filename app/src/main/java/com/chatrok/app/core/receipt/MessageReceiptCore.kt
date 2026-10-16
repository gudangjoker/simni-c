package com.chatrok.app.core.receipt

import com.chatrok.app.core.common.MessageIdUtils
import com.chatrok.app.core.model.Message
import com.chatrok.app.core.model.MessageStatus

/**
 * Data model untuk dokumen receipt di Firestore:
 * chatConversations/{conversationId}/receipts/{receiptUid}
 *
 * Sesuai Master Context Addendum A3.7 dan js/chat-receipts-core.mjs.
 */
data class ReceiptData(
    val uid: String,
    val conversationId: String,
    val lastDeliveredMessageId: String? = null,
    val lastDeliveredAt: Long? = null,
    val lastReadMessageId: String? = null,
    val lastReadAt: Long? = null,
    val updatedAt: Long? = null
)

/**
 * Logika inti tanda terima (Receipts Core) untuk SIMNI-C (CHATROK ENGINE).
 *
 * Menerapkan mesin status monotonik (tidak boleh regresi):
 * FAILED (0) / SENDING (1) -> SENT (2) -> DELIVERED (3) -> READ (4)
 *
 * Mengacu pada js/chat-receipts-core.mjs dan Master Context Addendum A7 & A3.7.
 */
object MessageReceiptCore {

    /**
     * Bobot urutan status pesan.
     * Status yang lebih tinggi mendominasi status yang lebih rendah.
     */
    fun statusRank(status: MessageStatus): Int = when (status) {
        MessageStatus.FAILED -> 0
        MessageStatus.SENDING -> 1
        MessageStatus.SENT -> 2
        MessageStatus.DELIVERED -> 3
        MessageStatus.READ -> 4
    }

    /**
     * Memverifikasi apakah status pesan boleh maju ke target status.
     * Regresi dilarang keras (misalnya READ tidak boleh kembali ke DELIVERED/SENT).
     */
    fun canAdvanceStatus(currentStatus: MessageStatus?, nextStatus: MessageStatus): Boolean {
        if (currentStatus == null) return true
        if (currentStatus == nextStatus) return true
        if (nextStatus == MessageStatus.FAILED) {
            // FAILED hanya diizinkan saat masih dalam proses SENDING
            return currentStatus == MessageStatus.SENDING
        }
        return statusRank(nextStatus) >= statusRank(currentStatus)
    }

    /**
     * Mengecek apakah messageId berada pada atau sebelum ackMessageId.
     *
     * Di CHATROK, ID pesan menggunakan format !{invertedTimestamp}_{randomHex24}.
     * Semakin besar invertedTimestamp, semakin lampau pesannya (timestamp lebih kecil).
     *
     * Jika ID bertipe !{invertedTimestamp}_{randomHex24}:
     * messageA <= messageB jika timestampA <= timestampB.
     * Dalam format inverted: invertedA >= invertedB.
     */
    fun isMessageAtOrBefore(
        messageId: String,
        ackMessageId: String?,
        messageTimestamp: Long? = null,
        ackTimestamp: Long? = null
    ): Boolean {
        if (ackMessageId.isNullOrBlank()) return false
        if (messageId == ackMessageId) return true

        // Ekstrak timestamp numerik jika ada
        val msgTs = messageTimestamp ?: MessageIdUtils.timestampFromMessageId(messageId)
        val ackTs = ackTimestamp ?: MessageIdUtils.timestampFromMessageId(ackMessageId)

        if (msgTs != null && ackTs != null) {
            return msgTs <= ackTs
        }

        // Jika kedua ID sesuai format CHATROK sortable:
        // !{invertedTimestamp}_{hex}: nilai alfabetis yang lebih besar berarti timestamp lebih kecil (lebih lama)
        if (messageId.startsWith("!") && ackMessageId.startsWith("!")) {
            // Lexicographical comparison: inverted timestamp lebih besar = lebih lama
            return messageId >= ackMessageId
        }

        return false
    }

    /**
     * Menyelesaikan status pesan outgoing berdasarkan tanda terima (receipt) dari lawan bicara (peer).
     */
    fun resolveMessageStatus(
        message: Message,
        peerReceipt: ReceiptData?,
        localStatus: MessageStatus? = null
    ): MessageStatus {
        // Jika status lokal adalah FAILED, pertahankan FAILED
        if (localStatus == MessageStatus.FAILED || message.status == MessageStatus.FAILED) {
            return MessageStatus.FAILED
        }

        // Jika status lokal adalah SENDING, pertahankan SENDING sampai ada konfirmasi server
        if (localStatus == MessageStatus.SENDING && message.status == MessageStatus.SENDING) {
            return MessageStatus.SENDING
        }

        // Pesan masuk tidak memiliki centang pengiriman di bubble, default READ/SENT
        if (!message.isOutgoing) {
            return message.status
        }

        if (peerReceipt == null) {
            return if (canAdvanceStatus(message.status, MessageStatus.SENT)) {
                maxOf(message.status, MessageStatus.SENT, compareBy { statusRank(it) })
            } else {
                message.status
            }
        }

        // 1. Periksa apakah sudah DIBACA (READ) oleh peer
        val isRead = isMessageAtOrBefore(
            messageId = message.id,
            ackMessageId = peerReceipt.lastReadMessageId,
            messageTimestamp = message.timestamp,
            ackTimestamp = peerReceipt.lastReadAt
        )
        if (isRead) {
            return MessageStatus.READ
        }

        // 2. Periksa apakah sudah DITERIMA (DELIVERED) oleh peer
        val isDelivered = isMessageAtOrBefore(
            messageId = message.id,
            ackMessageId = peerReceipt.lastDeliveredMessageId,
            messageTimestamp = message.timestamp,
            ackTimestamp = peerReceipt.lastDeliveredAt
        )
        if (isDelivered) {
            return if (message.status == MessageStatus.READ) MessageStatus.READ else MessageStatus.DELIVERED
        }

        // 3. Jika belum delivered atau read, status minimal adalah SENT (sudah tersimpan di server)
        return if (statusRank(message.status) >= statusRank(MessageStatus.SENT)) {
            message.status
        } else {
            MessageStatus.SENT
        }
    }
}
