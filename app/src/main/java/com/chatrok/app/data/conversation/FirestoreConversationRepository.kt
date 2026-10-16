package com.chatrok.app.data.conversation

import com.chatrok.app.core.common.ConversationIdUtils
import com.chatrok.app.core.model.Conversation
import com.chatrok.app.core.model.DEFAULT_USER_BIO
import com.chatrok.app.core.model.Message
import com.chatrok.app.core.model.MessageStatus
import com.chatrok.app.core.model.MessageType
import com.chatrok.app.core.model.User
import com.chatrok.app.data.messaging.AuthenticatedUidProvider
import com.chatrok.app.data.messaging.FirebaseAuthenticatedUidProvider
import com.chatrok.app.data.messaging.MessagingLocalVisibilityStore
import com.chatrok.app.domain.repository.ConversationRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

import com.chatrok.app.data.local.dao.ConversationDao
import com.chatrok.app.data.local.mapper.EntityMappers.toDomain
import com.chatrok.app.data.local.mapper.EntityMappers.toEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

internal const val CHAT_CONVERSATIONS = "chatConversations"

interface ConversationGateway {
    fun observeConversations(uid: String): Flow<List<Conversation>>
    fun observeConversation(uid: String, conversationId: String): Flow<Conversation?>
    suspend fun getOrCreateConversation(uid: String, peerUid: String): Conversation
    suspend fun markAsRead(uid: String, conversationId: String)
}

class DefaultConversationGateway(
    private val firestore: FirebaseFirestore
) : ConversationGateway {

    override fun observeConversations(uid: String): Flow<List<Conversation>> = kotlinx.coroutines.flow.callbackFlow {
        val registration = firestore.collection(CHAT_CONVERSATIONS)
            .whereArrayContains("participants", uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                launch(Dispatchers.IO) {
                    val conversations = snapshot.documents.mapNotNull { document ->
                        runCatching { document.toConversation(uid, unreadCount(uid, document.id)) }.getOrNull()
                    }.sortedByDescending { it.lastUpdated }
                    trySend(conversations)
                }
            }
        awaitClose { registration.remove() }
    }.flowOn(Dispatchers.IO)

    override fun observeConversation(uid: String, conversationId: String): Flow<Conversation?> =
        kotlinx.coroutines.flow.callbackFlow {
            val registration = firestore.collection(CHAT_CONVERSATIONS).document(conversationId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null || !snapshot.exists()) {
                        trySend(null)
                        return@addSnapshotListener
                    }
                    launch(Dispatchers.IO) {
                        val conversation = runCatching {
                            snapshot.toConversation(uid, unreadCount(uid, conversationId))
                        }.getOrNull()
                        trySend(conversation)
                    }
                }
            awaitClose { registration.remove() }
        }.flowOn(Dispatchers.IO)

    override suspend fun getOrCreateConversation(uid: String, peerUid: String): Conversation = withContext(Dispatchers.IO) {
        require(uid != peerUid) { "Percakapan 1-to-1 membutuhkan dua UID yang berbeda." }
        val conversationId = ConversationIdUtils.computeConversationId(uid, peerUid)
        val conversationRef = firestore.collection(CHAT_CONVERSATIONS).document(conversationId)
        val existing = runCatching { conversationRef.get().await() }.getOrNull()
        if (existing != null && existing.exists()) {
            val conv = runCatching { existing.toConversation(uid, unreadCount(uid, conversationId)) }.getOrNull()
            if (conv != null) return@withContext conv
        }

        val ownProfile = loadPublicProfile(uid)
        val peerProfile = loadPublicProfile(peerUid)
        val participants = listOf(uid, peerUid).sorted()
        val participantDetails = mapOf(
            uid to publicDetails(ownProfile),
            peerUid to publicDetails(peerProfile)
        )

        runCatching {
            firestore.runTransaction { tx ->
                val current = tx.get(conversationRef)
                if (!current.exists()) {
                    tx.set(
                        conversationRef,
                        mapOf(
                            "id" to conversationId,
                            "participants" to participants,
                            "participantDetails" to participantDetails,
                            "lastMessage" to null,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
            }.await()
        }

        Conversation(
            id = conversationId,
            otherUser = peerProfile,
            lastMessage = null,
            unreadCount = 0,
            lastUpdated = System.currentTimeMillis()
        )
    }

    override suspend fun markAsRead(uid: String, conversationId: String) = withContext(Dispatchers.IO) {
        runCatching {
            val newest = firestore.collection(CHAT_CONVERSATIONS).document(conversationId)
                .collection("messages")
                .orderBy(FieldPath.documentId())
                .limit(1)
                .get().await()
                .documents.firstOrNull()
                ?: return@withContext

            val receipt = mapOf(
                "uid" to uid,
                "conversationId" to conversationId,
                "lastDeliveredMessageId" to newest.id,
                "lastDeliveredAt" to FieldValue.serverTimestamp(),
                "lastReadMessageId" to newest.id,
                "lastReadAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            firestore.collection(CHAT_CONVERSATIONS).document(conversationId)
                .collection("receipts").document(uid)
                .set(receipt, SetOptions.merge()).await()
        }
        Unit
    }

    private suspend fun unreadCount(uid: String, conversationId: String): Int {
        return runCatching {
            val conversationRef = firestore.collection(CHAT_CONVERSATIONS).document(conversationId)
            val receipt = conversationRef.collection("receipts").document(uid).get().await()
            val lastReadId = receipt.getString("lastReadMessageId")
            val docs = conversationRef.collection("messages")
                .orderBy(FieldPath.documentId())
                .limit(100)
                .get().await().documents
            docs.count { doc ->
                val incoming = doc.getString("recipientUid") == uid
                incoming && (lastReadId.isNullOrBlank() || doc.id < lastReadId)
            }
        }.getOrDefault(0)
    }

    private suspend fun loadPublicProfile(uid: String): User {
        val doc = runCatching { firestore.collection("publicProfiles").document(uid).get().await() }.getOrNull()
        if (doc != null && doc.exists()) {
            return doc.toPublicUser()
        }
        val userDoc = runCatching { firestore.collection("users").document(uid).get().await() }.getOrNull()
        if (userDoc != null && userDoc.exists()) {
            return userDoc.toPublicUser()
        }
        return User(
            id = uid,
            username = "user_${uid.take(6)}",
            displayName = "Pengguna",
            avatarUrl = null,
            bio = DEFAULT_USER_BIO
        )
    }

    private fun publicDetails(user: User): Map<String, Any?> = mapOf(
        "uid" to user.id,
        "displayName" to user.displayName,
        "username" to user.username,
        "avatarUrl" to user.avatarUrl
    )

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.toConversation(ownUid: String, unread: Int): Conversation {
        val participants = (get("participants") as? List<*>)?.filterIsInstance<String>().orEmpty()
        val peerUid = participants.firstOrNull { it != ownUid }
            ?: (if (participants.isNotEmpty()) participants[0] else "unknown_peer")
        val details = get("participantDetails") as? Map<String, Any?>
        val peer = (details?.get(peerUid) as? Map<String, Any?>).toUser(peerUid)
        val lastMap = get("lastMessage") as? Map<String, Any?>
        val last = lastMap?.toLastMessage(id, ownUid)
        return Conversation(
            id = id,
            otherUser = peer,
            lastMessage = last,
            unreadCount = unread,
            lastUpdated = getTimestamp("updatedAt")?.toDate()?.time
                ?: getTimestamp("createdAt")?.toDate()?.time
                ?: System.currentTimeMillis()
        )
    }

    private fun Map<String, Any?>?.toUser(uid: String): User {
        val source = this.orEmpty()
        val username = source["username"] as? String ?: "pengguna"
        return User(
            id = uid,
            username = username,
            displayName = source["displayName"] as? String ?: username,
            avatarUrl = (source["avatarUrl"] as? String)?.ifBlank { null },
            bio = DEFAULT_USER_BIO
        )
    }

    private fun DocumentSnapshot.toPublicUser(): User {
        val username = getString("username") ?: getString("usernameLower") ?: "pengguna"
        return User(
            id = id,
            username = username,
            displayName = getString("displayName") ?: username,
            avatarUrl = getString("avatarUrl")?.ifBlank { null },
            bio = getString("bio") ?: DEFAULT_USER_BIO
        )
    }

    private fun Map<String, Any?>.toLastMessage(conversationId: String, ownUid: String): Message {
        val sender = this["senderUid"] as? String ?: ""
        val timestamp = (this["createdAt"] as? com.google.firebase.Timestamp)?.toDate()?.time
            ?: System.currentTimeMillis()
        val type = when (this["type"] as? String) {
            "image" -> MessageType.IMAGE
            "audio" -> MessageType.VOICE_NOTE
            "file" -> MessageType.DOCUMENT
            "video" -> MessageType.VIDEO
            else -> MessageType.TEXT
        }
        val safeText = when (type) {
            MessageType.TEXT -> "Pesan baru"
            MessageType.IMAGE -> "Foto"
            MessageType.VIDEO -> "Video"
            MessageType.VOICE_NOTE -> "Pesan suara"
            MessageType.DOCUMENT -> "Dokumen"
            else -> "Pesan"
        }
        return Message(
            id = "last_$conversationId",
            conversationId = conversationId,
            senderId = sender,
            text = safeText,
            timestamp = timestamp,
            status = MessageStatus.SENT,
            type = type,
            isOutgoing = sender == ownUid
        )
    }
}

@Singleton
class FirestoreConversationRepository(
    private val gateway: ConversationGateway,
    private val uidProvider: AuthenticatedUidProvider,
    private val visibilityStore: MessagingLocalVisibilityStore,
    private val conversationDao: ConversationDao? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : ConversationRepository {

    @Inject
    constructor(
        firestore: FirebaseFirestore,
        auth: FirebaseAuth,
        visibilityStore: MessagingLocalVisibilityStore,
        conversationDao: ConversationDao
    ) : this(
        DefaultConversationGateway(firestore),
        FirebaseAuthenticatedUidProvider(auth),
        visibilityStore,
        conversationDao
    )

    constructor(
        firestore: FirebaseFirestore,
        auth: FirebaseAuth,
        visibilityStore: MessagingLocalVisibilityStore
    ) : this(
        DefaultConversationGateway(firestore),
        FirebaseAuthenticatedUidProvider(auth),
        visibilityStore,
        null
    )

    constructor(
        gateway: ConversationGateway,
        uidProvider: AuthenticatedUidProvider,
        visibilityStore: MessagingLocalVisibilityStore
    ) : this(
        gateway,
        uidProvider,
        visibilityStore,
        null
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getConversations(): Flow<List<Conversation>> {
        return uidProvider.observeUid().flatMapLatest { uid ->
            if (uid.isNullOrBlank()) {
                flowOf(emptyList())
            } else {
                val remoteStream = gateway.observeConversations(uid).onEach { remoteList ->
                    if (conversationDao != null && remoteList.isNotEmpty()) {
                        scope.launch {
                            try {
                                conversationDao.insertConversations(remoteList.map { it.toEntity() })
                            } catch (_: Exception) {}
                        }
                    }
                }

                val cachedStream = conversationDao?.getConversationsFlow()?.map { entities ->
                    entities.map { it.toDomain() }
                } ?: flowOf(emptyList())

                combine(
                    remoteStream,
                    cachedStream,
                    visibilityStore.hiddenConversationIds,
                    visibilityStore.locallyReadConversationIds
                ) { remote, cached, hidden, read ->
                    val baseList = if (remote.isNotEmpty()) {
                        remote
                    } else if (cached.isNotEmpty()) {
                        cached
                    } else {
                        emptyList()
                    }
                    baseList.filterNot { it.id in hidden }
                        .map { if (it.id in read) it.copy(unreadCount = 0) else it }
                        .sortedByDescending { it.lastUpdated }
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getConversationById(id: String): Flow<Conversation?> {
        return uidProvider.observeUid().flatMapLatest { uid ->
            if (uid.isNullOrBlank()) {
                flowOf(null)
            } else {
                val remoteStream = gateway.observeConversation(uid, id)
                val cachedStream = conversationDao?.getConversationByIdFlow(id)?.map { it?.toDomain() } ?: flowOf(null)

                combine(
                    remoteStream,
                    cachedStream,
                    visibilityStore.hiddenConversationIds,
                    visibilityStore.locallyReadConversationIds
                ) { remote, cached, hidden, read ->
                    val conversation = remote ?: cached
                    when {
                        id in hidden -> null
                        conversation == null -> null
                        id in read -> conversation.copy(unreadCount = 0)
                        else -> conversation
                    }
                }
            }
        }
    }

    override suspend fun getOrCreateConversationWith(userId: String): Conversation {
        val uid = uidProvider.requireUid()
        if (uid == userId) {
            throw IllegalArgumentException("Tidak dapat memulai obrolan dengan akun sendiri.")
        }
        val conv = gateway.getOrCreateConversation(uid, userId)
        conversationDao?.let { dao ->
            scope.launch {
                try {
                    dao.insertConversation(conv.toEntity())
                } catch (_: Exception) {}
            }
        }
        return conv
    }

    override suspend fun deleteConversation(id: String): Result<Unit> {
        visibilityStore.hideConversation(id)
        conversationDao?.let { dao ->
            scope.launch {
                try {
                    dao.deleteConversationById(id)
                } catch (_: Exception) {}
            }
        }
        return Result.success(Unit)
    }

    override suspend fun markAsRead(id: String) {
        gateway.markAsRead(uidProvider.requireUid(), id)
        visibilityStore.markConversationRead(id)
        conversationDao?.let { dao ->
            scope.launch {
                try {
                    dao.markAsRead(id)
                } catch (_: Exception) {}
            }
        }
    }

    override suspend fun clearChatMessages(conversationId: String): Result<Unit> {
        visibilityStore.clearConversation(conversationId)
        return Result.success(Unit)
    }
}
