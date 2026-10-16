package com.chatrok.app.data.media

import android.content.Context
import com.chatrok.app.BuildConfig
import com.chatrok.app.core.crypto.E2eeCryptoEngine
import com.chatrok.app.core.crypto.EncryptedMessagePayload
import com.chatrok.app.core.crypto.JwkKey
import com.chatrok.app.core.media.ChatMediaCompressor
import com.chatrok.app.core.media.PreparedMedia
import com.chatrok.app.core.model.Attachment
import com.chatrok.app.core.model.AttachmentType
import com.chatrok.app.core.security.SecureIdentityStorage
import com.chatrok.app.data.security.FirestoreSecurityRepository
import com.chatrok.app.domain.repository.MediaRepository
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class EncryptedMediaUploadResult(
    val objectKey: String,
    val expiresAt: Long,
    val bytes: Long,
    val mediaIv: String,
    val encryptedMediaMeta: EncryptedMessagePayload,
    val cryptoVersion: Int,
    val senderFingerprint: String,
    val preparedMedia: PreparedMedia
)

data class DecryptedMediaResult(
    val bytes: ByteArray,
    val metadata: JSONObject,
    val localFile: File? = null
)

@Singleton
class R2MediaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: FirebaseAuth,
    private val okHttpClient: OkHttpClient,
    private val securityRepository: FirestoreSecurityRepository,
    private val identityStorage: SecureIdentityStorage,
    private val cryptoEngine: E2eeCryptoEngine
) : MediaRepository {

    private val localDecryptedCache = ConcurrentHashMap<String, File>()

    companion object {
        private const val DEFAULT_WORKER_BASE_URL = "https://chatrok-media-gateway.2ndgoal.workers.dev"

        fun getWorkerBaseUrl(): String {
            return try {
                val field = BuildConfig::class.java.getField("WORKER_BASE_URL")
                val url = field.get(null) as? String
                if (!url.isNullOrBlank()) url.trimEnd('/') else DEFAULT_WORKER_BASE_URL
            } catch (_: Exception) {
                DEFAULT_WORKER_BASE_URL
            }
        }
    }

    private suspend fun getIdToken(): String {
        val user = auth.currentUser ?: throw IllegalStateException("Pengguna belum login (Unauthenticated).")
        return user.getIdToken(false).await()?.token
            ?: throw IllegalStateException("Gagal mendapatkan Firebase ID token.")
    }

    /**
     * Mengunggah media terenkripsi sesuai spesifikasi CHATROK R2 Worker:
     * 1. Kompresi / validasi media via [ChatMediaCompressor].
     * 2. Hitung TTL (5 hari untuk image/audio/file, 3 hari untuk video).
     * 3. aad = "$contextBase|media|$wireType|$expiresAt"
     * 4. Enkripsi biner dengan ECDH P-256 + HKDF + AES-256-GCM (128-bit tag, 12-byte IV).
     * 5. Upload raw ciphertext ke Worker: POST /v1/media/upload dengan header:
     *    - Authorization: Bearer <token>
     *    - Content-Type: application/octet-stream
     *    - X-CHATROK-Media-Type: <wireType>
     *    - X-CHATROK-Expires-At: <expiresAt>
     *    - X-CHATROK-Recipient-UID: <recipientUid>
     * 6. Enkripsi metadata JSON dengan context "$contextBase|media-meta|${objectKey}".
     * 7. Hapus orphan dari R2 jika proses enkripsi metadata gagal.
     */
    suspend fun uploadEncryptedMedia(
        rawBytes: ByteArray,
        filename: String,
        mimeType: String,
        wireType: String,
        recipientUid: String,
        contextBase: String,
        durationSeconds: Int? = null
    ): EncryptedMediaUploadResult = withContext(Dispatchers.IO) {
        val ownUid = auth.currentUser?.uid ?: throw IllegalStateException("Pengguna belum login.")
        require(recipientUid.isNotBlank() && recipientUid != ownUid) {
            "recipientUid media wajib tersedia dan bukan pengirim."
        }

        val prepared = ChatMediaCompressor.prepareMedia(
            rawBytes = rawBytes,
            filename = filename,
            mimeType = mimeType,
            wireType = wireType,
            durationSeconds = durationSeconds
        )

        val expiresAt = System.currentTimeMillis() + prepared.policy.ttlMs
        val ownIdentity = securityRepository.ensureRegisteredPublicKey(ownUid)
        val peerPublicJwk = securityRepository.getPeerPublicKey(recipientUid)
            ?: throw IllegalStateException("Kunci publik penerima ($recipientUid) tidak ditemukan.")

        val mediaAad = "$contextBase|media|${prepared.wireType}|$expiresAt"
        val encryptedMedia = cryptoEngine.encryptBytes(
            bytes = prepared.bytes,
            privateJwk = ownIdentity.privateJwk,
            peerPublicJwk = peerPublicJwk,
            context = mediaAad,
            additionalData = mediaAad
        )

        val cipherBytes = cryptoEngine.base64Decode(encryptedMedia.ciphertext)
        val token = getIdToken()
        val uploadUrl = "${getWorkerBaseUrl()}/v1/media/upload"

        val requestBody = cipherBytes.toRequestBody("application/octet-stream".toMediaTypeOrNull())
        val request = Request.Builder()
            .url(uploadUrl)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/octet-stream")
            .addHeader("X-CHATROK-Media-Type", prepared.wireType)
            .addHeader("X-CHATROK-Expires-At", expiresAt.toString())
            .addHeader("X-CHATROK-Recipient-UID", recipientUid)
            .post(requestBody)
            .build()

        val response = okHttpClient.newCall(request).execute()
        val responseBody = response.body?.string()
        if (!response.isSuccessful || responseBody.isNullOrBlank()) {
            throw IllegalStateException("Upload media ke R2 gagal (HTTP ${response.code}): $responseBody")
        }

        val json = JSONObject(responseBody)
        if (!json.optBoolean("ok", false) || !json.has("objectKey")) {
            throw IllegalStateException(json.optString("message", "Respon Worker tidak valid."))
        }

        val objectKey = json.getString("objectKey")
        val uploadedBytes = json.optLong("bytes", cipherBytes.size.toLong())

        try {
            val metaObj = JSONObject().apply {
                put("name", prepared.filename)
                put("mime", prepared.mimeType)
                put("bytes", prepared.sizeBytes)
                put("originalBytes", prepared.originalSize)
                if (prepared.durationSeconds != null && prepared.durationSeconds > 0) {
                    put("duration", prepared.durationSeconds)
                }
                if (prepared.width != null && prepared.height != null) {
                    put("width", prepared.width)
                    put("height", prepared.height)
                }
            }

            val metaContext = "$contextBase|media-meta|$objectKey"
            val encryptedMeta = cryptoEngine.encryptText(
                text = metaObj.toString(),
                privateJwk = ownIdentity.privateJwk,
                peerPublicJwk = peerPublicJwk,
                context = metaContext
            )

            // Simpan file terdekripsi lokal untuk preview instan pengirim
            val cacheFile = File(context.cacheDir, "media_${System.currentTimeMillis()}_${prepared.filename}")
            FileOutputStream(cacheFile).use { it.write(prepared.bytes) }
            localDecryptedCache[objectKey] = cacheFile

            EncryptedMediaUploadResult(
                objectKey = objectKey,
                expiresAt = expiresAt,
                bytes = uploadedBytes,
                mediaIv = encryptedMedia.iv,
                encryptedMediaMeta = encryptedMeta,
                cryptoVersion = encryptedMedia.cryptoVersion,
                senderFingerprint = ownIdentity.fingerprint,
                preparedMedia = prepared
            )
        } catch (e: Exception) {
            // Hapus orphan media di Cloudflare Worker jika enkripsi metadata gagal
            deleteMedia(objectKey)
            throw e
        }
    }

    /**
     * Mengunduh dan mendekripsi media dari Cloudflare Worker R2 Gateway:
     * 1. Cek cache lokal jika sudah terunduh.
     * 2. Periksa expiry waktu (410 GONE).
     * 3. GET /v1/media/<encodedObjectKey> dengan Bearer token.
     * 4. Dekripsi metadata dengan "$contextBase|media-meta|${objectKey}".
     * 5. Dekripsi ciphertext biner dengan "$contextBase|media|$type|$expiresAt".
     */
    suspend fun downloadAndDecryptMedia(
        objectKey: String,
        mediaType: String,
        mediaIv: String,
        expiresAt: Long,
        cryptoVersion: Int,
        encryptedMetaPayload: EncryptedMessagePayload,
        senderUid: String,
        recipientUid: String,
        contextBase: String
    ): DecryptedMediaResult = withContext(Dispatchers.IO) {
        val ownUid = auth.currentUser?.uid ?: throw IllegalStateException("Pengguna belum login.")
        if (System.currentTimeMillis() >= expiresAt) {
            throw IllegalStateException("Media sudah kedaluwarsa (Expired).")
        }

        localDecryptedCache[objectKey]?.let { cachedFile ->
            if (cachedFile.exists() && cachedFile.length() > 0) {
                return@withContext DecryptedMediaResult(
                    bytes = cachedFile.readBytes(),
                    metadata = JSONObject(),
                    localFile = cachedFile
                )
            }
        }

        val otherUid = if (senderUid == ownUid) recipientUid else senderUid
        val ownIdentity = securityRepository.ensureRegisteredPublicKey(ownUid)
        val otherPublicJwk = securityRepository.getPeerPublicKey(otherUid)
            ?: throw IllegalStateException("Kunci publik lawan bicara ($otherUid) tidak ditemukan.")

        // 1. Dekripsi metadata
        val metaContext = "$contextBase|media-meta|$objectKey"
        val metaJsonStr = cryptoEngine.decryptText(
            payload = encryptedMetaPayload,
            privateJwk = ownIdentity.privateJwk,
            peerPublicJwk = otherPublicJwk,
            context = metaContext
        )
        val metadata = JSONObject(metaJsonStr)

        // 2. Unduh ciphertext biner dari Worker
        val token = getIdToken()
        val downloadUrl = "${getWorkerBaseUrl()}/v1/media/${java.net.URLEncoder.encode(objectKey, "UTF-8")}"
        val request = Request.Builder()
            .url(downloadUrl)
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (response.code == 410) {
            throw IllegalStateException("Media sudah kedaluwarsa (HTTP 410 Gone).")
        }
        if (!response.isSuccessful) {
            throw IllegalStateException("Download media dari R2 gagal (HTTP ${response.code}).")
        }

        val cipherBytes = response.body?.bytes()
            ?: throw IllegalStateException("Body respon download media kosong.")

        // 3. Dekripsi data biner
        val mediaAad = "$contextBase|media|$mediaType|$expiresAt"
        val encryptedPayload = EncryptedMessagePayload(
            cryptoVersion = cryptoVersion,
            iv = mediaIv,
            ciphertext = cryptoEngine.base64Encode(cipherBytes),
            aad = mediaAad
        )

        val plainBytes = cryptoEngine.decryptBytes(
            payload = encryptedPayload,
            privateJwk = ownIdentity.privateJwk,
            peerPublicJwk = otherPublicJwk,
            context = mediaAad
        )

        val safeName = metadata.optString("name", "media_${System.currentTimeMillis()}")
        val cachedFile = File(context.cacheDir, "${System.currentTimeMillis()}_$safeName")
        FileOutputStream(cachedFile).use { it.write(plainBytes) }
        localDecryptedCache[objectKey] = cachedFile

        DecryptedMediaResult(
            bytes = plainBytes,
            metadata = metadata,
            localFile = cachedFile
        )
    }

    /**
     * Menghapus media dari R2 Worker sebelum masa berlaku habis (sender only)
     */
    suspend fun deleteMedia(objectKey: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = getIdToken()
            val url = "${getWorkerBaseUrl()}/v1/media/${java.net.URLEncoder.encode(objectKey, "UTF-8")}"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .delete()
                .build()

            val response = okHttpClient.newCall(request).execute()
            response.isSuccessful
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun prepareVoiceNote(durationSeconds: Int, waveform: List<Float>): Attachment {
        val id = "voice_" + System.currentTimeMillis()
        return Attachment(
            id = id,
            type = AttachmentType.AUDIO,
            title = "Voice_Note_${System.currentTimeMillis()}.m4a",
            sizeBytes = durationSeconds * 16000L,
            durationSeconds = durationSeconds,
            mimeType = "audio/mp4",
            waveformPoints = waveform
        )
    }

    override suspend fun prepareMediaAttachment(type: AttachmentType, fileName: String, sizeBytes: Long): Attachment {
        val id = "media_" + System.currentTimeMillis()
        val mime = when (type) {
            AttachmentType.IMAGE -> "image/jpeg"
            AttachmentType.VIDEO -> "video/mp4"
            AttachmentType.DOCUMENT -> "application/pdf"
            AttachmentType.AUDIO -> "audio/mp4"
        }
        return Attachment(
            id = id,
            type = type,
            title = fileName,
            sizeBytes = sizeBytes,
            mimeType = mime
        )
    }

    fun getCachedFile(objectKey: String): File? = localDecryptedCache[objectKey]

    fun putCachedFile(objectKey: String, file: File) {
        localDecryptedCache[objectKey] = file
    }

    override suspend fun downloadMedia(objectKey: String): File? = localDecryptedCache[objectKey]

    /**
     * Upload avatar directly to Cloudflare Worker R2:
     * POST /v1/avatar/upload (max 5 MB, JPG/PNG/WEBP/GIF)
     * Returns: avatarUrl string
     */
    override suspend fun uploadAvatar(bytes: ByteArray, mimeType: String): String = withContext(Dispatchers.IO) {
        require(bytes.isNotEmpty()) { "Data avatar tidak boleh kosong." }
        require(bytes.size <= 5 * 1024 * 1024) { "Ukuran foto profil maksimal 5 MB." }
        val token = getIdToken()
        val url = "${getWorkerBaseUrl()}/v1/avatar/upload"
        val body = bytes.toRequestBody(mimeType.toMediaTypeOrNull())
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .post(body)
            .build()

        val response = okHttpClient.newCall(request).execute()
        val respBody = response.body?.string() ?: throw IllegalStateException("Respon server kosong.")
        if (!response.isSuccessful) {
            throw IllegalStateException("Gagal mengunggah foto profil (${response.code}): $respBody")
        }
        val json = JSONObject(respBody)
        if (!json.optBoolean("ok", false)) {
            throw IllegalStateException(json.optString("message", "Gagal memproses foto profil."))
        }
        json.getString("avatarUrl")
    }

    /**
     * Delete avatar from Cloudflare Worker R2:
     * DELETE /v1/avatar
     */
    override suspend fun deleteAvatar(): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = getIdToken()
            val url = "${getWorkerBaseUrl()}/v1/avatar"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .delete()
                .build()

            val response = okHttpClient.newCall(request).execute()
            response.isSuccessful
        } catch (_: Exception) {
            false
        }
    }
}
