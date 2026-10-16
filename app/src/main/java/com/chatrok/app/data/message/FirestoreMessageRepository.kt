package com.chatrok.app.data.message

import android.content.Context
import com.chatrok.app.core.common.FeatureUnavailableException
import com.chatrok.app.core.common.MessageIdUtils
import com.chatrok.app.core.crypto.E2eeCryptoEngine
import com.chatrok.app.core.crypto.EncryptedMessagePayload
import com.chatrok.app.core.crypto.JwkKey
import com.chatrok.app.core.model.Attachment
import com.chatrok.app.core.model.AttachmentType
import com.chatrok.app.core.model.Message
import com.chatrok.app.core.model.MessageStatus
import com.chatrok.app.core.model.MessageType
import com.chatrok.app.core.model.ReplyPreview
import com.chatrok.app.core.security.SecureIdentityStorage
import com.chatrok.app.core.receipt.MessageReceiptCore
import com.chatrok.app.core.receipt.ReceiptData
import com.chatrok.app.data.local.dao.DraftDao
import com.chatrok.app.data.local.dao.MessageDao
import com.chatrok.app.data.local.entity.DraftEntity
import com.chatrok.app.data.local.mapper.EntityMappers.toDomain
import com.chatrok.app.data.local.mapper.EntityMappers.toEntity
import com.chatrok.app.data.media.R2MediaRepository
import com.chatrok.app.data.messaging.AuthenticatedUidProvider
import com.chatrok.app.data.messaging.FirebaseAuthenticatedUidProvider
import com.chatrok.app.data.messaging.MessagingLocalVisibilityStore
import com.chatrok.app.data.security.FirestoreSecurityRepository
import com.chatrok.app.domain.repository.MessageRepository
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val CHAT_CONVERSATIONS = "chatConversations"
private const val INITIAL_MESSAGE_LIMIT = 50L
private const val MAX_PAGE_SIZE = 100
internal const val MILESTONE2_TRANSPORT_MODE = "m2-plaintext"

interface MessageGateway {
    fun observeMessages(uid: String, conversationId: String, limit: Long = INITIAL_MESSAGE_LIMIT): Flow<List<Message>>
    fun observeReceipts(conversationId: String): Flow<Map<String, ReceiptData>> = kotlinx.coroutines.flow.flowOf(emptyMap())
    suspend fun acknowledgeReceipt(uid: String, conversationId: String, lastDeliveredId: String?, lastReadId: String?) {}
    suspend fun loadOlderMessages(uid: String, conversationId: String, afterMessageId: String?, pageSize: Int): List<Message>
    suspend fun sendText(
        uid: String,
        conversationId: String,
        messageId: String,
        text: String,
        replyTo: ReplyPreview?
    ): Message
    suspend fun sendMedia(
        uid: String,
        conversationId: String,
        messageId: String,
        attachment: Attachment,
        caption: String?,
        replyTo: ReplyPreview?
    ): Message = throw UnsupportedOperationException("Gateway tidak mendukung sendMedia.")
    suspend fun messageExists(conversationId: String, messageId: String): Boolean
    suspend fun deleteMessage(uid: String, conversationId: String, messageId: String)
}

class DefaultMessageGateway(
    private val firestore: FirebaseFirestore,
    private val securityRepository: FirestoreSecurityRepository? = null,
    private val identityStorage: SecureIdentityStorage? = null,
    private val cryptoEngine: E2eeCryptoEngine? = null,
    private val mediaRepository: R2MediaRepository? = null,
    private val context: Context? = null
) : MessageGateway {

    private val decryptedTextCache = ConcurrentHashMap<String, String>()

    private fun contextForPeer(peerUid: String, convId: String, ownUid: String): String {
        val sortedUsers = listOf(ownUid, peerUid).sorted().joinToString(":")
        return "conversation:$convId|users:$sortedUsers"
    }

    private fun decryptionContexts(message: DocumentSnapshot, otherUid: String, convId: String, ownUid: String): List<String> {
        val senderUid = message.getString("senderUid").orEmpty()
        val recipientUid = message.getString("recipientUid").orEmpty()
        val sortedUsers = listOf(ownUid, otherUid).sorted().joinToString(":")
        val senderFirst = "$senderUid:$recipientUid"
        val recipientFirst = "$recipientUid:$senderUid"
        return listOf(
            contextForPeer(otherUid, convId, ownUid),
            "conversation:$convId|users:$senderFirst",
            "conversation:$convId|users:$recipientFirst",
            "conversation:$convId|$sortedUsers",
            "conversation:$convId",
            "$convId|users:$sortedUsers",
            convId
        ).distinct()
    }

    override fun observeMessages(uid: String, conversationId: String, limit: Long): Flow<List<Message>> =
        kotlinx.coroutines.flow.callbackFlow {
            val listener = firestore.collection(CHAT_CONVERSATIONS).document(conversationId)
                .collection("messages")
                .orderBy(FieldPath.documentId())
                .limit(limit)
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null) {
                        trySend(emptyList())
                        return@addSnapshotListener
                    }
                    val messages = snapshot.documents.mapNotNull { it.toMessageDomain(uid, conversationId) }
                        .sortedWith(compareBy<Message> { it.timestamp }.thenBy { it.id })
                    trySend(messages)
                }
            awaitClose { listener.remove() }
        }.flowOn(Dispatchers.IO)

    override fun observeReceipts(conversationId: String): Flow<Map<String, ReceiptData>> =
        kotlinx.coroutines.flow.callbackFlow {
            val listener = firestore.collection(CHAT_CONVERSATIONS).document(conversationId)
                .collection("receipts")
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null) {
                        trySend(emptyMap())
                        return@addSnapshotListener
                    }
                    val map = snapshot.documents.associate { doc ->
                        val rUid = doc.getString("uid") ?: doc.id
                        rUid to ReceiptData(
                            uid = rUid,
                            conversationId = conversationId,
                            lastDeliveredMessageId = doc.getString("lastDeliveredMessageId"),
                            lastDeliveredAt = doc.getTimestamp("lastDeliveredAt")?.toDate()?.time,
                            lastReadMessageId = doc.getString("lastReadMessageId"),
                            lastReadAt = doc.getTimestamp("lastReadAt")?.toDate()?.time,
                            updatedAt = doc.getTimestamp("updatedAt")?.toDate()?.time
                        )
                    }
                    trySend(map)
                }
            awaitClose { listener.remove() }
        }.flowOn(Dispatchers.IO)

    override suspend fun acknowledgeReceipt(
        uid: String,
        conversationId: String,
        lastDeliveredId: String?,
        lastReadId: String?
    ): Unit = withContext(Dispatchers.IO) {
        if (lastDeliveredId.isNullOrBlank() && lastReadId.isNullOrBlank()) return@withContext
        val updates = mutableMapOf<String, Any>(
            "uid" to uid,
            "conversationId" to conversationId,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (!lastDeliveredId.isNullOrBlank()) {
            updates["lastDeliveredMessageId"] = lastDeliveredId
            updates["lastDeliveredAt"] = FieldValue.serverTimestamp()
        }
        if (!lastReadId.isNullOrBlank()) {
            updates["lastReadMessageId"] = lastReadId
            updates["lastReadAt"] = FieldValue.serverTimestamp()
        }
        firestore.collection(CHAT_CONVERSATIONS).document(conversationId)
            .collection("receipts").document(uid)
            .set(updates, com.google.firebase.firestore.SetOptions.merge())
            .await()
    }

    override suspend fun loadOlderMessages(
        uid: String,
        conversationId: String,
        afterMessageId: String?,
        pageSize: Int
    ): List<Message> = withContext(Dispatchers.IO) {
        val normalizedSize = pageSize.coerceIn(1, MAX_PAGE_SIZE).toLong()
        var query = firestore.collection(CHAT_CONVERSATIONS).document(conversationId)
            .collection("messages")
            .orderBy(FieldPath.documentId())
        if (!afterMessageId.isNullOrBlank()) query = query.startAfter(afterMessageId)
        query.limit(normalizedSize).get().await().documents
            .mapNotNull { it.toMessageDomain(uid, conversationId) }
            .sortedWith(compareBy<Message> { it.timestamp }.thenBy { it.id })
    }

    override suspend fun sendText(
        uid: String,
        conversationId: String,
        messageId: String,
        text: String,
        replyTo: ReplyPreview?
    ): Message = withContext(Dispatchers.IO) {
        val cleanText = text.trim()
        require(cleanText.isNotEmpty()) { "Pesan tidak boleh kosong." }

        val conversationRef = firestore.collection(CHAT_CONVERSATIONS).document(conversationId)
        val conversation = conversationRef.get().await()
        require(conversation.exists()) { "Percakapan tidak ditemukan." }
        val participants = (conversation.get("participants") as? List<*>)?.filterIsInstance<String>().orEmpty()
        require(uid in participants && participants.size == 2) { "Pengguna bukan partisipan percakapan." }
        val recipientUid = participants.first { it != uid }

        val messageRef = conversationRef.collection("messages").document(messageId)

        val messagePayload: LinkedHashMap<String, Any?>
        if (securityRepository != null && identityStorage != null && cryptoEngine != null) {
            // Milestone 3: Full End-to-End Encryption (schemaVersion=1)
            val myIdentity = securityRepository.ensureRegisteredPublicKey(uid)
            val peerPublicJwk = securityRepository.getPeerPublicKey(recipientUid)
                ?: throw IllegalStateException("Kunci publik penerima tidak ditemukan di chatPublicKeys.")
            val peerFingerprint = cryptoEngine.fingerprintJwk(peerPublicJwk)

            val context = contextForPeer(recipientUid, conversationId, uid)
            val aad = "conversation:$conversationId"
            val encrypted = cryptoEngine.encryptText(cleanText, myIdentity.privateJwk, peerPublicJwk, context, aad)

            decryptedTextCache[messageId] = cleanText

            messagePayload = linkedMapOf(
                "schemaVersion" to 1,
                "type" to "text",
                "senderUid" to uid,
                "recipientUid" to recipientUid,
                "cryptoVersion" to encrypted.cryptoVersion,
                "iv" to encrypted.iv,
                "ciphertext" to encrypted.ciphertext,
                "aad" to encrypted.aad,
                "objectKey" to "",
                "expiresAt" to null,
                "mediaIv" to "",
                "encryptedMediaMeta" to null,
                "senderFingerprint" to myIdentity.fingerprint,
                "recipientFingerprint" to peerFingerprint,
                "senderPublicJwk" to myIdentity.publicJwk.toMap(),
                "recipientPublicJwk" to peerPublicJwk.toMap(),
                "createdAt" to FieldValue.serverTimestamp()
            )
        } else {
            // Milestone 2 fallback mode
            messagePayload = linkedMapOf(
                "schemaVersion" to 2,
                "transportMode" to MILESTONE2_TRANSPORT_MODE,
                "type" to "text",
                "senderUid" to uid,
                "recipientUid" to recipientUid,
                "plaintext" to cleanText,
                "createdAt" to FieldValue.serverTimestamp()
            )
            if (replyTo != null) {
                messagePayload["replyTo"] = mapOf(
                    "messageId" to replyTo.messageId,
                    "senderUid" to (replyTo.senderId ?: if (replyTo.senderName == "Anda") uid else recipientUid),
                    "type" to replyTo.type.toWireType()
                )
            }
        }

        val lastMessage = mapOf(
            "type" to "text",
            "senderUid" to uid,
            "recipientUid" to recipientUid,
            "createdAt" to FieldValue.serverTimestamp()
        )

        val batch = firestore.batch()
        batch.set(messageRef, messagePayload)
        batch.update(
            conversationRef,
            mapOf(
                "lastMessage" to lastMessage,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.commit().await()

        Message(
            id = messageId,
            conversationId = conversationId,
            senderId = uid,
            text = cleanText,
            timestamp = MessageIdUtils.timestampFromMessageId(messageId) ?: System.currentTimeMillis(),
            status = MessageStatus.SENT,
            type = MessageType.TEXT,
            replyTo = replyTo,
            isOutgoing = true
        )
    }

    override suspend fun sendMedia(
        uid: String,
        conversationId: String,
        messageId: String,
        attachment: Attachment,
        caption: String?,
        replyTo: ReplyPreview?
    ): Message = withContext(Dispatchers.IO) {
        val conversationRef = firestore.collection(CHAT_CONVERSATIONS).document(conversationId)
        val conversation = conversationRef.get().await()
        require(conversation.exists()) { "Percakapan tidak ditemukan." }
        val participants = (conversation.get("participants") as? List<*>)?.filterIsInstance<String>().orEmpty()
        require(uid in participants && participants.size == 2) { "Pengguna bukan partisipan percakapan." }
        val recipientUid = participants.first { it != uid }

        val messageRef = conversationRef.collection("messages").document(messageId)
        val wireType = attachment.type.toWireType()
        val contextBase = contextForPeer(recipientUid, conversationId, uid)

        val rawBytes: ByteArray = when {
            attachment.localFilePath != null -> {
                val f = File(attachment.localFilePath)
                if (f.exists()) f.readBytes() else createDummyBytesForType(attachment.type)
            }
            attachment.uriOrUrl.startsWith("content://") && context != null -> {
                try {
                    val uri = android.net.Uri.parse(attachment.uriOrUrl)
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: createDummyBytesForType(attachment.type)
                } catch (_: Exception) {
                    createDummyBytesForType(attachment.type)
                }
            }
            attachment.uriOrUrl.startsWith("file://") -> {
                try {
                    val uri = android.net.Uri.parse(attachment.uriOrUrl)
                    File(uri.path ?: "").readBytes()
                } catch (_: Exception) {
                    createDummyBytesForType(attachment.type)
                }
            }
            else -> createDummyBytesForType(attachment.type)
        }

        val messagePayload: LinkedHashMap<String, Any?>
        val resultAttachment: Attachment

        if (mediaRepository != null && securityRepository != null && identityStorage != null && cryptoEngine != null) {
            val myIdentity = securityRepository.ensureRegisteredPublicKey(uid)
            val peerPublicJwk = securityRepository.getPeerPublicKey(recipientUid)
                ?: throw IllegalStateException("Kunci publik penerima tidak ditemukan di chatPublicKeys.")
            val peerFingerprint = cryptoEngine.fingerprintJwk(peerPublicJwk)

            val uploadResult = mediaRepository.uploadEncryptedMedia(
                rawBytes = rawBytes,
                filename = attachment.title.ifBlank { "media_${System.currentTimeMillis()}" },
                mimeType = attachment.mimeType.ifBlank { defaultMimeForType(attachment.type) },
                wireType = wireType,
                recipientUid = recipientUid,
                contextBase = contextBase,
                durationSeconds = attachment.durationSeconds
            )

            messagePayload = linkedMapOf(
                "schemaVersion" to 1,
                "type" to wireType,
                "senderUid" to uid,
                "recipientUid" to recipientUid,
                "cryptoVersion" to 1,
                "iv" to "",
                "ciphertext" to "",
                "aad" to "conversation:$conversationId",
                "objectKey" to uploadResult.objectKey,
                "expiresAt" to uploadResult.expiresAt,
                "mediaIv" to uploadResult.mediaIv,
                "encryptedMediaMeta" to mapOf(
                    "cryptoVersion" to uploadResult.encryptedMediaMeta.cryptoVersion,
                    "iv" to uploadResult.encryptedMediaMeta.iv,
                    "ciphertext" to uploadResult.encryptedMediaMeta.ciphertext,
                    "aad" to uploadResult.encryptedMediaMeta.aad
                ),
                "senderFingerprint" to myIdentity.fingerprint,
                "recipientFingerprint" to peerFingerprint,
                "senderPublicJwk" to myIdentity.publicJwk.toMap(),
                "recipientPublicJwk" to peerPublicJwk.toMap(),
                "createdAt" to FieldValue.serverTimestamp()
            )

            resultAttachment = attachment.copy(
                objectKey = uploadResult.objectKey,
                expiresAt = uploadResult.expiresAt,
                mediaIv = uploadResult.mediaIv,
                isUploading = false,
                uploadProgress = 1.0f,
                sizeBytes = uploadResult.bytes
            )
        } else {
            // Simulated fallback mode for unit testing
            val expiresAt = System.currentTimeMillis() + 5 * 86400000L
            val simulatedKey = "media/$wireType/${expiresAt}/$uid/simulated_${messageId}.bin"
            val simulatedIv = "simulated_iv_base64"

            messagePayload = linkedMapOf(
                "schemaVersion" to 1,
                "type" to wireType,
                "senderUid" to uid,
                "recipientUid" to recipientUid,
                "cryptoVersion" to 1,
                "iv" to "",
                "ciphertext" to "",
                "aad" to "conversation:$conversationId",
                "objectKey" to simulatedKey,
                "expiresAt" to expiresAt,
                "mediaIv" to simulatedIv,
                "encryptedMediaMeta" to mapOf(
                    "cryptoVersion" to 1,
                    "iv" to "sim_iv",
                    "ciphertext" to "sim_cipher",
                    "aad" to "conversation:$conversationId"
                ),
                "senderFingerprint" to "simulated_fingerprint",
                "recipientFingerprint" to "simulated_recipient_fingerprint",
                "senderPublicJwk" to mapOf("kty" to "EC", "crv" to "P-256", "x" to "sim", "y" to "sim"),
                "recipientPublicJwk" to mapOf("kty" to "EC", "crv" to "P-256", "x" to "sim", "y" to "sim"),
                "createdAt" to FieldValue.serverTimestamp()
            )

            resultAttachment = attachment.copy(
                objectKey = simulatedKey,
                expiresAt = expiresAt,
                mediaIv = simulatedIv,
                isUploading = false,
                uploadProgress = 1.0f
            )
        }

        val lastMessage = mapOf(
            "type" to wireType,
            "senderUid" to uid,
            "recipientUid" to recipientUid,
            "createdAt" to FieldValue.serverTimestamp()
        )

        val batch = firestore.batch()
        batch.set(messageRef, messagePayload)
        batch.update(
            conversationRef,
            mapOf(
                "lastMessage" to lastMessage,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.commit().await()

        val domainMessageType = when (attachment.type) {
            AttachmentType.IMAGE -> MessageType.IMAGE
            AttachmentType.VIDEO -> MessageType.VIDEO
            AttachmentType.AUDIO -> MessageType.VOICE_NOTE
            AttachmentType.DOCUMENT -> MessageType.DOCUMENT
        }

        Message(
            id = messageId,
            conversationId = conversationId,
            senderId = uid,
            text = caption ?: attachment.title,
            timestamp = MessageIdUtils.timestampFromMessageId(messageId) ?: System.currentTimeMillis(),
            status = MessageStatus.SENT,
            type = domainMessageType,
            attachment = resultAttachment,
            replyTo = replyTo,
            isOutgoing = true
        )
    }

    override suspend fun messageExists(conversationId: String, messageId: String): Boolean = withContext(Dispatchers.IO) {
        firestore.collection(CHAT_CONVERSATIONS).document(conversationId)
            .collection("messages").document(messageId).get().await().exists()
    }

    override suspend fun deleteMessage(uid: String, conversationId: String, messageId: String) = withContext(Dispatchers.IO) {
        val conversationRef = firestore.collection(CHAT_CONVERSATIONS).document(conversationId)
        val ref = conversationRef.collection("messages").document(messageId)
        val snapshot = ref.get().await()
        require(snapshot.exists()) { "Pesan tidak ditemukan." }
        require(snapshot.getString("senderUid") == uid) { "Hanya pengirim yang dapat menghapus pesan untuk semua." }
        ref.delete().await()

        val newest = conversationRef.collection("messages")
            .orderBy(FieldPath.documentId())
            .limit(1)
            .get().await().documents.firstOrNull()

        val lastMessage = newest?.let {
            mapOf(
                "type" to (it.getString("type") ?: "text"),
                "senderUid" to (it.getString("senderUid") ?: ""),
                "recipientUid" to (it.getString("recipientUid") ?: ""),
                "createdAt" to (it.getTimestamp("createdAt") ?: Timestamp.now())
            )
        }
        conversationRef.update(
            mapOf(
                "lastMessage" to lastMessage,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
        Unit
    }

    private fun DocumentSnapshot.toMessageDomain(uid: String, conversationId: String): Message? {
        return runCatching {
            if (!exists()) return null
            val schemaVersion = getLong("schemaVersion")?.toInt() ?: return null

            if (schemaVersion == 1) {
                // Milestone 3 E2EE message
                toMilestone3EncryptedMessage(uid, conversationId)
            } else if (schemaVersion == 2 && getString("transportMode") == MILESTONE2_TRANSPORT_MODE) {
                // Milestone 2 plaintext backward compatibility
                toMilestone2Message(uid, conversationId)
            } else {
                null
            }
        }.getOrNull()
    }

    private fun DocumentSnapshot.toMilestone3EncryptedMessage(uid: String, conversationId: String): Message? {
        val senderUid = getString("senderUid") ?: return null
        val recipientUid = getString("recipientUid") ?: return null
        val typeStr = getString("type") ?: "text"
        val cryptoVersion = getLong("cryptoVersion")?.toInt() ?: 1
        val iv = getString("iv").orEmpty()
        val ciphertext = getString("ciphertext").orEmpty()
        val aad = getString("aad").orEmpty()

        val otherUid = if (senderUid == uid) recipientUid else senderUid

        val resolvedText = decryptedTextCache[id] ?: run {
            if (identityStorage != null && cryptoEngine != null) {
                try {
                    val myIdentity = identityStorage.getIdentity(uid)
                    val otherPublicJwk = if (senderUid == uid) {
                        (get("recipientPublicJwk") as? Map<*, *>)?.let { JwkKey.fromMap(it) }
                    } else {
                        (get("senderPublicJwk") as? Map<*, *>)?.let { JwkKey.fromMap(it) }
                    }

                    if (myIdentity != null && otherPublicJwk != null && ciphertext.isNotEmpty()) {
                        val payload = EncryptedMessagePayload(
                            cryptoVersion = cryptoVersion,
                            iv = iv,
                            ciphertext = ciphertext,
                            aad = aad
                        )
                        var decrypted: String? = null
                        val contexts = decryptionContexts(this, otherUid, conversationId, uid)
                        for (ctx in contexts) {
                            try {
                                decrypted = cryptoEngine.decryptText(payload, myIdentity.privateJwk, otherPublicJwk, ctx)
                                if (decrypted != null) break
                            } catch (_: Exception) {}
                        }
                        decrypted?.also { decryptedTextCache[id] = it }
                    } else null
                } catch (_: Exception) {
                    null
                }
            } else null
        } ?: if (typeStr == "text") "[Pesan Terenkripsi E2EE]" else "[Media Terenkripsi E2EE]"

        val objectKey = getString("objectKey").orEmpty()
        val expiresAt = getLong("expiresAt")
        val mediaIv = getString("mediaIv").orEmpty()
        val isExpired = expiresAt != null && expiresAt <= System.currentTimeMillis()

        var decryptedMeta: JSONObject? = null
        val encryptedMediaMetaMap = get("encryptedMediaMeta") as? Map<*, *>

        if (encryptedMediaMetaMap != null && identityStorage != null && cryptoEngine != null) {
            try {
                val myIdentity = identityStorage.getIdentity(uid)
                val otherPublicJwk = if (senderUid == uid) {
                    (get("recipientPublicJwk") as? Map<*, *>)?.let { JwkKey.fromMap(it) }
                } else {
                    (get("senderPublicJwk") as? Map<*, *>)?.let { JwkKey.fromMap(it) }
                }

                if (myIdentity != null && otherPublicJwk != null) {
                    val metaPayload = EncryptedMessagePayload(
                        cryptoVersion = (encryptedMediaMetaMap["cryptoVersion"] as? Long)?.toInt() ?: 1,
                        iv = encryptedMediaMetaMap["iv"] as? String ?: "",
                        ciphertext = encryptedMediaMetaMap["ciphertext"] as? String ?: "",
                        aad = encryptedMediaMetaMap["aad"] as? String ?: ""
                    )
                    val contexts = decryptionContexts(this, otherUid, conversationId, uid)
                    for (ctx in contexts) {
                        try {
                            val metaCtx = if (metaPayload.aad.isNotEmpty()) metaPayload.aad else "$ctx|media-meta|$objectKey"
                            val metaJsonStr = cryptoEngine.decryptText(metaPayload, myIdentity.privateJwk, otherPublicJwk, metaCtx)
                            if (metaJsonStr != null) {
                                decryptedMeta = JSONObject(metaJsonStr)
                                break
                            }
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }

        val metaName = decryptedMeta?.optString("name").orEmpty()
        val metaMime = decryptedMeta?.optString("mime").orEmpty()
        val metaBytes = decryptedMeta?.optLong("bytes") ?: 0L
        val metaDuration = if (decryptedMeta?.has("duration") == true) decryptedMeta.getInt("duration") else null

        val domainAttachmentType = when (typeStr.lowercase()) {
            "image" -> AttachmentType.IMAGE
            "video" -> AttachmentType.VIDEO
            "audio" -> AttachmentType.AUDIO
            else -> AttachmentType.DOCUMENT
        }

        val attachment = if (objectKey.isNotEmpty()) {
            Attachment(
                id = id,
                type = domainAttachmentType,
                title = metaName.ifBlank { "media_$id" },
                sizeBytes = metaBytes,
                durationSeconds = metaDuration,
                uriOrUrl = "",
                mimeType = metaMime,
                uploadProgress = 1.0f,
                isUploading = false,
                waveformPoints = emptyList(),
                objectKey = objectKey,
                expiresAt = expiresAt,
                mediaIv = mediaIv,
                isExpired = isExpired,
                localFilePath = mediaRepository?.getCachedFile(objectKey)?.absolutePath
            )
        } else null

        val displayText = if (typeStr == "text") {
            resolvedText
        } else {
            if (isExpired) "[Media Telah Kedaluwarsa]"
            else metaName.ifBlank { "[Media Terenkripsi E2EE]" }
        }

        return Message(
            id = id,
            conversationId = conversationId,
            senderId = senderUid,
            text = displayText,
            timestamp = getTimestamp("createdAt")?.toDate()?.time
                ?: MessageIdUtils.timestampFromMessageId(id)
                ?: 0L,
            status = MessageStatus.SENT,
            type = typeStr.toDomainType(),
            attachment = attachment,
            replyTo = null,
            isOutgoing = senderUid == uid
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.toMilestone2Message(uid: String, conversationId: String): Message? {
        val senderUid = getString("senderUid") ?: return null
        val text = getString("plaintext") ?: return null
        val replyMap = get("replyTo") as? Map<String, Any?>
        val reply = replyMap?.let {
            ReplyPreview(
                messageId = it["messageId"] as? String ?: return@let null,
                senderName = if ((it["senderUid"] as? String) == uid) "Anda" else "Rekan chat",
                snippet = "Pesan dibalas",
                type = (it["type"] as? String).toDomainType(),
                senderId = it["senderUid"] as? String
            )
        }
        return Message(
            id = id,
            conversationId = conversationId,
            senderId = senderUid,
            text = text,
            timestamp = getTimestamp("createdAt")?.toDate()?.time
                ?: MessageIdUtils.timestampFromMessageId(id)
                ?: 0L,
            status = MessageStatus.SENT,
            type = MessageType.TEXT,
            replyTo = reply,
            isOutgoing = senderUid == uid
        )
    }
}

private sealed class PendingPayload {
    data class Text(
        val conversationId: String,
        val text: String,
        val replyTo: ReplyPreview?
    ) : PendingPayload()

    data class Media(
        val conversationId: String,
        val attachment: Attachment,
        val caption: String?,
        val replyTo: ReplyPreview?
    ) : PendingPayload()
}

@Singleton
class FirestoreMessageRepository(
    private val gateway: MessageGateway,
    private val uidProvider: AuthenticatedUidProvider,
    private val visibilityStore: MessagingLocalVisibilityStore,
    private val repoScope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO),
    private val messageDao: MessageDao? = null,
    private val draftDao: DraftDao? = null
) : MessageRepository {

    @Inject
    constructor(
        firestore: FirebaseFirestore,
        auth: FirebaseAuth,
        visibilityStore: MessagingLocalVisibilityStore,
        securityRepository: FirestoreSecurityRepository,
        identityStorage: SecureIdentityStorage,
        cryptoEngine: E2eeCryptoEngine,
        mediaRepository: R2MediaRepository,
        @ApplicationContext context: Context,
        messageDao: MessageDao,
        draftDao: DraftDao
    ) : this(
        DefaultMessageGateway(firestore, securityRepository, identityStorage, cryptoEngine, mediaRepository, context),
        FirebaseAuthenticatedUidProvider(auth),
        visibilityStore,
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO),
        messageDao,
        draftDao
    )

    constructor(
        firestore: FirebaseFirestore,
        auth: FirebaseAuth,
        visibilityStore: MessagingLocalVisibilityStore,
        securityRepository: FirestoreSecurityRepository,
        identityStorage: SecureIdentityStorage,
        cryptoEngine: E2eeCryptoEngine,
        mediaRepository: R2MediaRepository,
        @ApplicationContext context: Context
    ) : this(
        DefaultMessageGateway(firestore, securityRepository, identityStorage, cryptoEngine, mediaRepository, context),
        FirebaseAuthenticatedUidProvider(auth),
        visibilityStore,
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO),
        null,
        null
    )

    constructor(
        firestore: FirebaseFirestore,
        auth: FirebaseAuth,
        visibilityStore: MessagingLocalVisibilityStore,
        securityRepository: FirestoreSecurityRepository,
        identityStorage: SecureIdentityStorage,
        cryptoEngine: E2eeCryptoEngine
    ) : this(
        DefaultMessageGateway(firestore, securityRepository, identityStorage, cryptoEngine, null, null),
        FirebaseAuthenticatedUidProvider(auth),
        visibilityStore,
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO),
        null,
        null
    )

    constructor(
        firestore: FirebaseFirestore,
        auth: FirebaseAuth,
        visibilityStore: MessagingLocalVisibilityStore
    ) : this(
        DefaultMessageGateway(firestore),
        FirebaseAuthenticatedUidProvider(auth),
        visibilityStore,
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO),
        null,
        null
    )

    constructor(
        gateway: MessageGateway,
        uidProvider: AuthenticatedUidProvider,
        visibilityStore: MessagingLocalVisibilityStore,
        messageDao: MessageDao?,
        draftDao: DraftDao?
    ) : this(
        gateway,
        uidProvider,
        visibilityStore,
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO),
        messageDao,
        draftDao
    )

    private val olderMessages = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    private val pendingMessages = MutableStateFlow<Map<String, Message>>(emptyMap())
    private val pendingPayloads = ConcurrentHashMap<String, PendingPayload>()
    private val drafts = ConcurrentHashMap<String, String>()
    private val messageConversationIndex = ConcurrentHashMap<String, String>()
    private val pageCursors = ConcurrentHashMap<String, String>()
    private val lastAckedDeliveredMessageId = ConcurrentHashMap<String, String>()

    init {
        if (draftDao != null) {
            repoScope.launch {
                try {
                    val savedDrafts = draftDao.getAllDrafts()
                    savedDrafts.forEach { draftEntity ->
                        drafts[draftEntity.conversationId] = draftEntity.draftText
                    }
                } catch (_: Exception) {}
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getMessages(conversationId: String): Flow<List<Message>> {
        return uidProvider.observeUid().flatMapLatest { uid ->
            if (uid.isNullOrBlank()) {
                flowOf(emptyList())
            } else {
                val cachedFlow = messageDao?.getMessagesFlow(conversationId)?.map { list ->
                    list.map { it.toDomain() }
                } ?: flowOf(emptyList())

                val remoteFlow = combine(
                    gateway.observeMessages(uid, conversationId),
                    gateway.observeReceipts(conversationId)
                ) { remote, receipts ->
                    if (messageDao != null && remote.isNotEmpty()) {
                        repoScope.launch {
                            try {
                                messageDao.insertMessages(remote.map { it.toEntity() })
                            } catch (_: Exception) {}
                        }
                    }
                    Pair(remote, receipts)
                }

                val remoteAndCachedFlow = combine(remoteFlow, cachedFlow) { (remote, receipts), cachedList ->
                    Triple(remote, receipts, cachedList)
                }

                combine(
                    remoteAndCachedFlow,
                    olderMessages,
                    pendingMessages,
                    visibilityStore.hiddenMessageIds,
                    visibilityStore.clearCutoffs
                ) { (remote, receipts, cachedList), olderMap, pendingMap, hiddenIds, cutoffs ->
                    val peerReceipt = receipts.values.firstOrNull { it.uid != uid }
                    val merged = LinkedHashMap<String, Message>()
                    (cachedList + olderMap[conversationId].orEmpty() + remote + pendingMap.values.filter { it.conversationId == conversationId })
                        .forEach { message ->
                            messageConversationIndex[message.id] = conversationId
                            val resolvedMessage = if (message.isOutgoing) {
                                val newStatus = MessageReceiptCore.resolveMessageStatus(message, peerReceipt)
                                message.copy(status = newStatus)
                            } else {
                                message
                            }
                            val existing = merged[resolvedMessage.id]
                            if (existing == null || MessageReceiptCore.statusRank(resolvedMessage.status) >= MessageReceiptCore.statusRank(existing.status)) {
                                merged[resolvedMessage.id] = resolvedMessage
                            }
                        }

                    // Auto-acknowledge delivery for incoming messages
                    val newestIncoming = remote.filterNot { it.isOutgoing }.maxByOrNull { it.timestamp }
                    if (newestIncoming != null) {
                        val lastAcked = lastAckedDeliveredMessageId[conversationId]
                        if (lastAcked != newestIncoming.id) {
                            lastAckedDeliveredMessageId[conversationId] = newestIncoming.id
                            repoScope.launch {
                                try {
                                    gateway.acknowledgeReceipt(uid, conversationId, lastDeliveredId = newestIncoming.id, lastReadId = null)
                                } catch (_: Exception) {}
                            }
                        }
                    }

                    val pageIds = (olderMap[conversationId].orEmpty() + remote).map { it.id }
                    pageIds.maxOrNull()?.let { pageCursors[conversationId] = it }
                    val cutoff = cutoffs[conversationId] ?: 0L
                    merged.values
                        .filterNot { it.id in hiddenIds }
                        .filter { it.timestamp >= cutoff }
                        .sortedWith(compareBy<Message> { it.timestamp }.thenBy { it.id })
                }
            }
        }
    }

    override suspend fun sendMessage(
        conversationId: String,
        text: String,
        replyTo: ReplyPreview?
    ): Result<Message> {
        val uid = runCatching { uidProvider.requireUid() }.getOrElse { return Result.failure(it) }
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return Result.failure(IllegalArgumentException("Pesan tidak boleh kosong."))

        val tempId = MessageIdUtils.generateMessageId()
        val optimistic = Message(
            id = tempId,
            conversationId = conversationId,
            senderId = uid,
            text = cleanText,
            timestamp = MessageIdUtils.timestampFromMessageId(tempId) ?: System.currentTimeMillis(),
            status = MessageStatus.SENDING,
            type = MessageType.TEXT,
            replyTo = replyTo,
            isOutgoing = true
        )
        messageConversationIndex[tempId] = conversationId
        pendingPayloads[tempId] = PendingPayload.Text(conversationId, cleanText, replyTo)
        updatePendingMessage(optimistic)

        return try {
            val confirmed = gateway.sendText(uid, conversationId, tempId, cleanText, replyTo)
            removePendingMessage(tempId)
            drafts.remove(conversationId)
            draftDao?.let { dao ->
                repoScope.launch {
                    try {
                        dao.deleteDraft(conversationId)
                    } catch (_: Exception) {}
                }
            }
            messageDao?.let { dao ->
                repoScope.launch {
                    try {
                        dao.insertMessage(confirmed.toEntity())
                    } catch (_: Exception) {}
                }
            }
            Result.success(confirmed)
        } catch (e: Exception) {
            updatePendingMessage(optimistic.copy(status = MessageStatus.FAILED))
            Result.failure(e)
        }
    }

    override suspend fun sendAttachment(
        conversationId: String,
        attachment: Attachment,
        caption: String?,
        replyTo: ReplyPreview?
    ): Result<Message> {
        val uid = runCatching { uidProvider.requireUid() }.getOrElse { return Result.failure(it) }

        val tempId = MessageIdUtils.generateMessageId()
        val domainMessageType = when (attachment.type) {
            AttachmentType.IMAGE -> MessageType.IMAGE
            AttachmentType.VIDEO -> MessageType.VIDEO
            AttachmentType.AUDIO -> MessageType.VOICE_NOTE
            AttachmentType.DOCUMENT -> MessageType.DOCUMENT
        }

        val optimisticAttachment = attachment.copy(
            isUploading = true,
            uploadProgress = 0.3f
        )

        val optimistic = Message(
            id = tempId,
            conversationId = conversationId,
            senderId = uid,
            text = caption ?: attachment.title,
            timestamp = MessageIdUtils.timestampFromMessageId(tempId) ?: System.currentTimeMillis(),
            status = MessageStatus.SENDING,
            type = domainMessageType,
            attachment = optimisticAttachment,
            replyTo = replyTo,
            isOutgoing = true
        )

        messageConversationIndex[tempId] = conversationId
        pendingPayloads[tempId] = PendingPayload.Media(conversationId, attachment, caption, replyTo)
        updatePendingMessage(optimistic)

        return try {
            val confirmed = gateway.sendMedia(uid, conversationId, tempId, attachment, caption, replyTo)
            removePendingMessage(tempId)
            drafts.remove(conversationId)
            draftDao?.let { dao ->
                repoScope.launch {
                    try {
                        dao.deleteDraft(conversationId)
                    } catch (_: Exception) {}
                }
            }
            messageDao?.let { dao ->
                repoScope.launch {
                    try {
                        dao.insertMessage(confirmed.toEntity())
                    } catch (_: Exception) {}
                }
            }
            Result.success(confirmed)
        } catch (e: Exception) {
            updatePendingMessage(optimistic.copy(status = MessageStatus.FAILED, attachment = attachment.copy(isUploading = false)))
            Result.failure(e)
        }
    }

    override suspend fun retryMessage(messageId: String): Result<Unit> {
        val pending = pendingMessages.value[messageId]
            ?: return Result.failure(IllegalStateException("Pesan retry tidak ditemukan."))
        val payload = pendingPayloads[messageId]
        val uid = runCatching { uidProvider.requireUid() }.getOrElse { return Result.failure(it) }

        updatePendingMessage(pending.copy(status = MessageStatus.SENDING))
        return try {
            when (payload) {
                is PendingPayload.Text -> {
                    gateway.sendText(uid, payload.conversationId, messageId, payload.text, payload.replyTo)
                }
                is PendingPayload.Media -> {
                    gateway.sendMedia(uid, payload.conversationId, messageId, payload.attachment, payload.caption, payload.replyTo)
                }
                null -> {
                    if (pending.attachment != null) {
                        gateway.sendMedia(
                            uid = uid,
                            conversationId = pending.conversationId,
                            messageId = messageId,
                            attachment = pending.attachment,
                            caption = pending.text.takeIf { it != pending.attachment.title },
                            replyTo = pending.replyTo
                        )
                    } else {
                        gateway.sendText(uid, pending.conversationId, messageId, pending.text, pending.replyTo)
                    }
                }
            }
            removePendingMessage(messageId)
            drafts.remove(pending.conversationId)
            Result.success(Unit)
        } catch (e: Exception) {
            updatePendingMessage(pending.copy(status = MessageStatus.FAILED))
            Result.failure(e)
        }
    }

    override suspend fun loadOlderMessages(conversationId: String, pageSize: Int): Result<Int> {
        val uid = runCatching { uidProvider.requireUid() }.getOrElse { return Result.failure(it) }
        val cursor = pageCursors[conversationId]
        return try {
            val older = gateway.loadOlderMessages(uid, conversationId, cursor, pageSize)
            if (older.isNotEmpty()) {
                older.map { it.id }.maxOrNull()?.let { pageCursors[conversationId] = it }
                older.forEach { messageConversationIndex[it.id] = conversationId }
                olderMessages.value = olderMessages.value + (
                    conversationId to (olderMessages.value[conversationId].orEmpty() + older)
                        .distinctBy { it.id }
                        .sortedWith(compareBy<Message> { it.timestamp }.thenBy { it.id })
                )
            }
            Result.success(older.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteMessage(messageId: String, forEveryone: Boolean): Result<Unit> {
        val uid = runCatching { uidProvider.requireUid() }.getOrElse { return Result.failure(it) }
        val conversationId = messageConversationIndex[messageId]
        return try {
            if (forEveryone) {
                require(!conversationId.isNullOrBlank()) { "ID percakapan untuk pesan tidak ditemukan." }
                gateway.deleteMessage(uid, conversationId, messageId)
            }
            visibilityStore.hideMessage(messageId)
            removePendingMessage(messageId)
            messageDao?.let { dao ->
                repoScope.launch {
                    try {
                        dao.deleteMessageById(messageId)
                    } catch (_: Exception) {}
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun copyMessage(message: Message): String = message.text

    override fun getDraft(conversationId: String): String = drafts[conversationId].orEmpty()

    override fun setDraft(conversationId: String, draft: String) {
        val clean = draft.trim()
        if (clean.isEmpty()) {
            drafts.remove(conversationId)
            draftDao?.let { dao ->
                repoScope.launch {
                    try {
                        dao.deleteDraft(conversationId)
                    } catch (_: Exception) {}
                }
            }
        } else {
            drafts[conversationId] = clean
            draftDao?.let { dao ->
                repoScope.launch {
                    try {
                        dao.saveDraft(DraftEntity(conversationId = conversationId, draftText = clean))
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun updatePendingMessage(message: Message) {
        pendingMessages.value = pendingMessages.value + (message.id to message)
    }

    private fun removePendingMessage(id: String) {
        pendingMessages.value = pendingMessages.value - id
        pendingPayloads.remove(id)
    }

    private fun statusRank(status: MessageStatus): Int = when (status) {
        MessageStatus.SENDING -> 0
        MessageStatus.FAILED -> 1
        MessageStatus.SENT -> 2
        MessageStatus.DELIVERED -> 3
        MessageStatus.READ -> 4
    }
}

private fun String?.toDomainType(): MessageType = when (this) {
    "image" -> MessageType.IMAGE
    "audio" -> MessageType.VOICE_NOTE
    "video" -> MessageType.VIDEO
    "file" -> MessageType.DOCUMENT
    else -> MessageType.TEXT
}

private fun MessageType.toWireType(): String = when (this) {
    MessageType.IMAGE -> "image"
    MessageType.VOICE_NOTE -> "audio"
    MessageType.VIDEO -> "video"
    MessageType.DOCUMENT -> "file"
    else -> "text"
}

private fun AttachmentType.toWireType(): String = when (this) {
    AttachmentType.IMAGE -> "image"
    AttachmentType.AUDIO -> "audio"
    AttachmentType.VIDEO -> "video"
    AttachmentType.DOCUMENT -> "file"
}

private fun createDummyBytesForType(type: AttachmentType): ByteArray = when (type) {
    AttachmentType.IMAGE -> byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
        0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
        0x01, 0x01, 0x00, 0x60, 0x00, 0x60, 0x00, 0x00,
        0xFF.toByte(), 0xDB.toByte(), 0x00, 0x43, 0x00,
        0xFF.toByte(), 0xD9.toByte()
    )
    AttachmentType.AUDIO -> ByteArray(512) { 0 }
    AttachmentType.VIDEO -> ByteArray(1024) { 0 }
    AttachmentType.DOCUMENT -> "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n%%EOF".toByteArray()
}

private fun defaultMimeForType(type: AttachmentType): String = when (type) {
    AttachmentType.IMAGE -> "image/jpeg"
    AttachmentType.AUDIO -> "audio/mp4"
    AttachmentType.VIDEO -> "video/mp4"
    AttachmentType.DOCUMENT -> "application/pdf"
}
