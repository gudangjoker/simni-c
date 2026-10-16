package com.chatrok.app.core.push

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * CHATROK — Push & Notification Core Engine Constants and Utilities.
 *
 * Mengimplementasikan kontrak ketat penyamaran notifikasi (Notification Cloaking):
 * - Pesan Chat: Judul & isi statis ("SIMNI ADMINISTRASI KELAS" / "Database telah siap, segera lakukan backup").
 * - Zero plaintext, zero sender identity, zero email, zero previews dalam payload push pesan.
 * - Call Notifications: Mengizinkan nama pemanggil dengan pembersihan ketat alamat email.
 * - Single source of truth untuk event types, hashing token FCM, validasi payload, dan routing intent.
 */
object PushConstants {

    // Notification Channel IDs
    const val CHANNEL_MESSAGE_ACTIVITY = "chatrok-message-activity"
    const val CHANNEL_CALLS = "chatrok-calls"

    // Push Event Types
    const val EVENT_MESSAGE_ACTIVITY = "message_activity"
    const val EVENT_VOICE_CALL = "voice_call"
    const val EVENT_VIDEO_CALL = "video_call"

    // Cloaked Notification Constants
    const val CLOAKED_TITLE = "SIMNI ADMINISTRASI KELAS"
    const val CLOAKED_BODY = "Database telah siap, segera lakukan backup"
    const val CLOAKED_TAG = "simni-admin-backup"

    // Call Notification Fallback Constants
    const val CALL_TITLE_FALLBACK = "CHATROK"
    const val CALL_VOICE_BODY = "Panggilan suara masuk"
    const val CALL_VIDEO_BODY = "Panggilan video masuk"
    const val CALL_TAG = "chatrok-call"

    // Intent Extras for Navigation
    const val EXTRA_ROUTE = "extra_push_route"
    const val EXTRA_CONVERSATION_ID = "extra_push_conversation_id"
    const val EXTRA_CALLER_UID = "extra_push_caller_uid"
    const val EXTRA_CALL_TYPE = "extra_push_call_type"

    val FORBIDDEN_PAYLOAD_KEYS = listOf(
        "message", "bodytext", "plaintext", "decrypted", "text", "caption",
        "lastmessage", "preview", "filename", "originalname", "email"
    )

    private val EMAIL_REGEX = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")

    /**
     * Menghasilkan SHA-256 hash dari FCM push token secara deterministik untuk document ID Firestore.
     */
    fun hashPushToken(token: String): String {
        val clean = token.trim()
        require(clean.isNotBlank()) { "Token push tidak boleh kosong." }
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(clean.toByteArray(StandardCharsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Memastikan nama pemanggil tidak memuat atau menyerupai alamat email.
     * Jika memuat '@' atau pola email, otomatis fallback ke "CHATROK".
     */
    fun sanitizeCallerName(name: String?): String {
        val text = name?.trim().orEmpty()
        if (text.isBlank()) return CALL_TITLE_FALLBACK
        if (text.contains("@") || EMAIL_REGEX.containsMatchIn(text)) {
            return CALL_TITLE_FALLBACK
        }
        return text.take(50).ifBlank { CALL_TITLE_FALLBACK }
    }

    /**
     * Memvalidasi bahwa payload push keluar tidak memuat data sensitif (zero sensitive payload).
     * Melemparkan IllegalStateException jika terdeteksi kebocoran plaintext/email.
     */
    fun assertZeroSensitivePayload(payload: Map<String, Any?>) {
        val lowerCaseKeys = payload.keys.map { it.lowercase() }
        for (forbidden in FORBIDDEN_PAYLOAD_KEYS) {
            if (lowerCaseKeys.contains(forbidden)) {
                throw IllegalStateException("Kebocoran payload push terdeteksi: field terlarang '$forbidden' ditemukan.")
            }
        }

        // Cek rekursif isi string
        fun checkValue(v: Any?) {
            when (v) {
                is String -> {
                    if (EMAIL_REGEX.containsMatchIn(v)) {
                        throw IllegalStateException("Kebocoran payload push terdeteksi: format email ditemukan dalam payload.")
                    }
                }
                is Map<*, *> -> {
                    for ((key, value) in v) {
                        val kLower = key.toString().lowercase()
                        if (FORBIDDEN_PAYLOAD_KEYS.contains(kLower)) {
                            throw IllegalStateException("Kebocoran payload push terdeteksi: nested field terlarang '$key' ditemukan.")
                        }
                        checkValue(value)
                    }
                }
                is Iterable<*> -> {
                    for (item in v) checkValue(item)
                }
            }
        }

        for (value in payload.values) {
            checkValue(value)
        }
    }

    /**
     * Membangun payload dokumen token perangkat untuk Firestore chatPushTokens/{uid}/tokens/{tokenId}.
     */
    fun buildTokenDocPayload(
        uid: String,
        token: String,
        platform: String = "android",
        updatedAt: Any? = null
    ): Map<String, Any> {
        val cleanUid = uid.trim()
        val cleanToken = token.trim()
        require(cleanUid.isNotBlank()) { "UID push token wajib tersedia." }
        require(cleanToken.length in 21..4095) { "Format push token FCM tidak valid." }

        val map = mutableMapOf<String, Any>(
            "uid" to cleanUid,
            "token" to cleanToken,
            "tokenHash" to hashPushToken(cleanToken),
            "platform" to platform,
            "active" to true
        )
        if (updatedAt != null) {
            map["updatedAt"] = updatedAt
        }
        return map
    }

    /**
     * Menentukan rute navigasi dari data notifikasi push yang diterima.
     */
    fun parseNotificationRouting(data: Map<String, String>): PushRouteAction {
        val type = data["type"] ?: data["kind"] ?: EVENT_MESSAGE_ACTIVITY
        val convId = data["conversationId"]

        return when (type) {
            EVENT_VOICE_CALL, EVENT_VIDEO_CALL -> {
                val callerUid = data["callerUid"].orEmpty()
                PushRouteAction.IncomingCall(
                    callerUid = callerUid,
                    callType = if (type == EVENT_VOICE_CALL) "voice" else "video"
                )
            }
            EVENT_MESSAGE_ACTIVITY -> {
                if (!convId.isNullOrBlank() && convId.length <= 128) {
                    PushRouteAction.OpenConversation(convId)
                } else {
                    PushRouteAction.OpenInbox
                }
            }
            else -> {
                if (!convId.isNullOrBlank()) {
                    PushRouteAction.OpenConversation(convId)
                } else {
                    PushRouteAction.OpenInbox
                }
            }
        }
    }
}

sealed interface PushRouteAction {
    data class OpenConversation(val conversationId: String) : PushRouteAction
    data object OpenInbox : PushRouteAction
    data class IncomingCall(val callerUid: String, val callType: String) : PushRouteAction
}
