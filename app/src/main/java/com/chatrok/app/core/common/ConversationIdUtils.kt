package com.chatrok.app.core.common

import java.security.MessageDigest

object ConversationIdUtils {

    /**
     * Menghitung ID percakapan deterministik terhash.
     * Sesuai Master Context Addendum A5 dan js/chat-conversations-core.mjs:
     * - Memvalidasi kedua UID
     * - Menolak percakapan mandiri (self-conversation)
     * - Mengurutkan UID secara leksikografis
     * - Membentuk pasangan kanonikal: "${sorted[0]}|${sorted[1]}"
     * - Mengembalikan "direct_${sha256Hex}"
     */
    fun computeConversationId(uidA: String, uidB: String): String {
        val a = uidA.trim()
        val b = uidB.trim()
        require(a.isNotBlank() && b.isNotBlank()) {
            "Kedua UID wajib tersedia untuk menghitung ID percakapan."
        }
        require(a != b) {
            "Percakapan 1-to-1 membutuhkan dua UID yang berbeda."
        }
        val sorted = listOf(a, b).sorted()
        val canonicalPair = "${sorted[0]}|${sorted[1]}"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalPair.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "direct_$hex"
    }
}
