package com.chatrok.app.domain.repository

/**
 * Kontrak repositori untuk mengelola token FCM perangkat pada Firestore chatPushTokens/{uid}/tokens/{tokenId}.
 */
interface PushTokenRepository {
    /**
     * Mendaftarkan atau memperbarui token FCM untuk pengguna aktif saat ini.
     */
    suspend fun registerCurrentToken(token: String): Result<Unit>

    /**
     * Menonaktifkan token FCM tertentu (misal saat rotasi atau device logout).
     */
    suspend fun unregisterToken(token: String): Result<Unit>

    /**
     * Menonaktifkan semua token perangkat aktif milik pengguna saat pengguna logout.
     */
    suspend fun deactivateUserTokens(uid: String): Result<Unit>
}
