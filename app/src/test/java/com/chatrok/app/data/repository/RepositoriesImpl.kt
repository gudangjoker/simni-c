package com.chatrok.app.data.repository

import com.chatrok.app.core.model.Attachment
import com.chatrok.app.core.model.AttachmentType
import com.chatrok.app.core.model.CallSession
import com.chatrok.app.core.model.CallState
import com.chatrok.app.core.model.CallType
import com.chatrok.app.core.model.Conversation
import com.chatrok.app.core.model.Message
import com.chatrok.app.core.model.MessageStatus
import com.chatrok.app.core.model.MessageType
import com.chatrok.app.core.model.PresenceState
import com.chatrok.app.core.model.ReplyPreview
import com.chatrok.app.core.model.SecurityInfo
import com.chatrok.app.core.model.SessionBootstrapState
import com.chatrok.app.core.model.DEFAULT_USER_BIO
import com.chatrok.app.core.model.User
import com.chatrok.app.data.fake.FakeDataSource
import com.chatrok.app.domain.repository.AuthRepository
import com.chatrok.app.domain.repository.CallRepository
import com.chatrok.app.domain.repository.ConversationRepository
import com.chatrok.app.domain.repository.MediaRepository
import com.chatrok.app.domain.repository.MessageRepository
import com.chatrok.app.domain.repository.NotificationRepository
import com.chatrok.app.domain.repository.PresenceRepository
import com.chatrok.app.domain.repository.SecurityRepository
import com.chatrok.app.domain.repository.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class FakeAuthRepository(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) : AuthRepository {

    private val _currentUser = MutableStateFlow<User?>(FakeDataSource.currentUser)
    override val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    private val _sessionBootstrapState = MutableStateFlow<SessionBootstrapState>(
        FakeDataSource.currentUser?.let { SessionBootstrapState.Ready(it) } ?: SessionBootstrapState.Unauthenticated
    )
    override val sessionBootstrapState: StateFlow<SessionBootstrapState> = _sessionBootstrapState.asStateFlow()

    override suspend fun login(email: String, password: String): Result<User> {
        _sessionBootstrapState.value = SessionBootstrapState.Loading
        delay(400)
        if (email.isBlank() || password.isEmpty()) {
            val err = IllegalArgumentException("Email dan kata sandi wajib diisi")
            _sessionBootstrapState.value = SessionBootstrapState.Error(err)
            return Result.failure(err)
        }
        val user = FakeDataSource.currentUser.copy(username = email.substringBefore("@"))
        _currentUser.value = user
        _sessionBootstrapState.value = SessionBootstrapState.Ready(user)
        return Result.success(user)
    }

    override suspend fun register(
        email: String,
        password: String,
        username: String,
        displayName: String
    ): Result<User> {
        delay(400)
        if (email.isBlank() || password.isBlank() || username.isBlank() || displayName.isBlank()) {
            return Result.failure(IllegalArgumentException("Semua kolom harus diisi"))
        }
        val user = User(
            id = "usr_" + UUID.randomUUID().toString().take(8),
            username = username.trim(),
            displayName = displayName.trim(),
            bio = DEFAULT_USER_BIO,
            isOnline = true,
            lastSeen = System.currentTimeMillis()
        )
        _currentUser.value = user
        _sessionBootstrapState.value = SessionBootstrapState.Ready(user)
        return Result.success(user)
    }

    override suspend fun logout() {
        delay(200)
        _currentUser.value = null
        _sessionBootstrapState.value = SessionBootstrapState.Unauthenticated
    }

    override suspend fun updateProfile(
        displayName: String,
        bio: String,
        avatarUrl: String?
    ): Result<User> {
        delay(300)
        val current = _currentUser.value ?: return Result.failure(IllegalStateException("Pengguna belum masuk"))
        val updated = current.copy(
            displayName = displayName.ifBlank { current.displayName },
            bio = bio,
            avatarUrl = avatarUrl ?: current.avatarUrl
        )
        _currentUser.value = updated
        return Result.success(updated)
    }
}

class FakeUserRepository : UserRepository {
    private val _users = MutableStateFlow<List<User>>(FakeDataSource.users)

    override fun getUsers(): Flow<List<User>> = _users

    override fun getUserById(userId: String): Flow<User?> {
        return _users.map { list -> list.find { it.id == userId } }
    }

    override fun searchUsers(query: String): Flow<List<User>> {
        return _users.map { list ->
            if (query.isBlank()) list
            else list.filter {
                it.displayName.contains(query, ignoreCase = true) ||
                        it.username.contains(query, ignoreCase = true)
            }
        }
    }

    override suspend fun blockUser(userId: String, isBlocked: Boolean): Result<Unit> {
        _users.update { list ->
            list.map { if (it.id == userId) it.copy(isBlocked = isBlocked) else it }
        }
        return Result.success(Unit)
    }

    override suspend fun updateUser(user: User): Result<Unit> {
        _users.update { list ->
            list.map { if (it.id == user.id) user else it }
        }
        return Result.success(Unit)
    }
}

class FakeConversationRepository(
    private val fakeUserRepository: UserRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) : ConversationRepository {

    private val _conversations = MutableStateFlow<List<Conversation>>(FakeDataSource.getInitialConversations())

    override fun getConversations(): Flow<List<Conversation>> = _conversations

    override fun getConversationById(id: String): Flow<Conversation?> {
        return _conversations.map { list -> list.find { it.id == id } }
    }

    override suspend fun getOrCreateConversationWith(userId: String): Conversation {
        val existing = _conversations.value.find { it.otherUser.id == userId }
        if (existing != null) return existing

        val user = FakeDataSource.users.find { it.id == userId }
            ?: User(
                id = userId,
                username = "kontak_$userId",
                displayName = "Kontak $userId"
            )

        val newConversation = Conversation(
            id = "conv_$userId",
            otherUser = user,
            lastMessage = null,
            unreadCount = 0,
            lastUpdated = System.currentTimeMillis()
        )
        _conversations.update { listOf(newConversation) + it }
        return newConversation
    }

    override suspend fun deleteConversation(id: String): Result<Unit> {
        _conversations.update { list -> list.filterNot { it.id == id } }
        return Result.success(Unit)
    }

    override suspend fun markAsRead(id: String) {
        _conversations.update { list ->
            list.map { if (it.id == id) it.copy(unreadCount = 0) else it }
        }
    }

    override suspend fun clearChatMessages(conversationId: String): Result<Unit> {
        _conversations.update { list ->
            list.map { if (it.id == conversationId) it.copy(lastMessage = null, unreadCount = 0) else it }
        }
        return Result.success(Unit)
    }

    fun updateLastMessage(conversationId: String, message: Message) {
        _conversations.update { list ->
            val conv = list.find { it.id == conversationId }
            if (conv != null) {
                val updated = conv.copy(
                    lastMessage = message,
                    lastUpdated = message.timestamp,
                    unreadCount = if (message.isOutgoing) conv.unreadCount else conv.unreadCount + 1
                )
                listOf(updated) + list.filterNot { it.id == conversationId }
            } else list
        }
    }
}

class FakeMessageRepository(
    private val conversationRepository: FakeConversationRepository,
    private val presenceRepository: PresenceRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) : MessageRepository {

    private val messagesStore = mutableMapOf<String, MutableStateFlow<List<Message>>>()
    private val draftsStore = mutableMapOf<String, String>()

    init {
        FakeDataSource.initialMessages.forEach { (convId, list) ->
            messagesStore[convId] = MutableStateFlow(list.toList())
        }
    }

    private fun getOrCreateFlow(conversationId: String): MutableStateFlow<List<Message>> {
        return synchronized(messagesStore) {
            messagesStore.getOrPut(conversationId) {
                MutableStateFlow(
                    listOf(
                        Message(
                            id = "sec_$conversationId",
                            conversationId = conversationId,
                            senderId = "system",
                            text = "Pesan dan panggilan pada demo ini menunggu integrasi E2EE pada Phase 2.",
                            timestamp = System.currentTimeMillis() - 3600 * 1000,
                            type = MessageType.SYSTEM,
                            isOutgoing = false
                        )
                    )
                )
            }
        }
    }

    override fun getMessages(conversationId: String): Flow<List<Message>> {
        return getOrCreateFlow(conversationId)
    }

    override suspend fun sendMessage(
        conversationId: String,
        text: String,
        replyTo: ReplyPreview?
    ): Result<Message> {
        val flow = getOrCreateFlow(conversationId)
        val msgId = "msg_" + UUID.randomUUID().toString().take(8)
        val shouldFail = text.startsWith("!fail", ignoreCase = true)

        val message = Message(
            id = msgId,
            conversationId = conversationId,
            senderId = "usr_me",
            text = text,
            timestamp = System.currentTimeMillis(),
            status = if (shouldFail) MessageStatus.FAILED else MessageStatus.SENDING,
            type = MessageType.TEXT,
            replyTo = replyTo,
            isOutgoing = true
        )

        flow.update { it + message }
        conversationRepository.updateLastMessage(conversationId, message)

        if (!shouldFail) {
            scope.launch {
                delay(700)
                updateStatus(conversationId, msgId, MessageStatus.SENT)
                delay(1200)
                updateStatus(conversationId, msgId, MessageStatus.DELIVERED)
                delay(2000)
                updateStatus(conversationId, msgId, MessageStatus.READ)
                simulateReplyIfNeeded(conversationId, text)
            }
        }

        return Result.success(message)
    }

    override suspend fun sendAttachment(
        conversationId: String,
        attachment: Attachment,
        caption: String?,
        replyTo: ReplyPreview?
    ): Result<Message> {
        val flow = getOrCreateFlow(conversationId)
        val msgId = "att_msg_" + UUID.randomUUID().toString().take(8)

        val msgType = when (attachment.type) {
            AttachmentType.IMAGE -> MessageType.IMAGE
            AttachmentType.VIDEO -> MessageType.VIDEO
            AttachmentType.DOCUMENT -> MessageType.DOCUMENT
            AttachmentType.AUDIO -> MessageType.VOICE_NOTE
        }

        val message = Message(
            id = msgId,
            conversationId = conversationId,
            senderId = "usr_me",
            text = caption ?: attachment.title,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.SENDING,
            type = msgType,
            attachment = attachment.copy(isUploading = true, uploadProgress = 0.2f),
            replyTo = replyTo,
            isOutgoing = true
        )

        flow.update { it + message }
        conversationRepository.updateLastMessage(conversationId, message)

        scope.launch {
            delay(500)
            flow.update { list ->
                list.map { if (it.id == msgId) it.copy(attachment = it.attachment?.copy(uploadProgress = 0.7f)) else it }
            }
            delay(600)
            flow.update { list ->
                list.map { if (it.id == msgId) it.copy(status = MessageStatus.SENT, attachment = it.attachment?.copy(isUploading = false, uploadProgress = 1.0f)) else it }
            }
            delay(1200)
            updateStatus(conversationId, msgId, MessageStatus.DELIVERED)
            delay(1500)
            updateStatus(conversationId, msgId, MessageStatus.READ)
        }

        return Result.success(message)
    }

    private fun updateStatus(conversationId: String, messageId: String, status: MessageStatus) {
        val flow = getOrCreateFlow(conversationId)
        flow.update { list ->
            list.map { if (it.id == messageId) it.copy(status = status) else it }
        }
        val last = flow.value.lastOrNull()
        if (last != null && last.id == messageId) {
            conversationRepository.updateLastMessage(conversationId, last)
        }
    }

    private fun simulateReplyIfNeeded(conversationId: String, originalText: String) {
        scope.launch {
            presenceRepository.setTyping(conversationId, true)
            delay(2800)
            presenceRepository.setTyping(conversationId, false)

            val replyText = when {
                originalText.contains("schedule", ignoreCase = true) || originalText.contains("roster", ignoreCase = true) ->
                    "Got it, reviewing the schedule updates now. Looks well synchronized!"
                originalText.contains("hello", ignoreCase = true) || originalText.contains("hi", ignoreCase = true) ->
                    "Hello Alex! Hope your day is going smoothly."
                originalText.contains("exam", ignoreCase = true) || originalText.contains("test", ignoreCase = true) ->
                    "All testing rooms and proctor credentials are ready."
                else -> "Received and acknowledged. Secure copy archived."
            }

            val reply = Message(
                id = "reply_" + UUID.randomUUID().toString().take(6),
                conversationId = conversationId,
                senderId = "usr_other",
                text = replyText,
                timestamp = System.currentTimeMillis(),
                status = MessageStatus.READ,
                type = MessageType.TEXT,
                isOutgoing = false
            )
            val flow = getOrCreateFlow(conversationId)
            flow.update { it + reply }
            conversationRepository.updateLastMessage(conversationId, reply)
        }
    }

    override suspend fun retryMessage(messageId: String): Result<Unit> {
        messagesStore.values.forEach { flow ->
            val msg = flow.value.find { it.id == messageId }
            if (msg != null) {
                flow.update { list ->
                    list.map { if (it.id == messageId) it.copy(status = MessageStatus.SENDING) else it }
                }
                scope.launch {
                    delay(800)
                    flow.update { list ->
                        list.map { if (it.id == messageId) it.copy(status = MessageStatus.SENT) else it }
                    }
                    delay(1200)
                    flow.update { list ->
                        list.map { if (it.id == messageId) it.copy(status = MessageStatus.DELIVERED) else it }
                    }
                }
                return Result.success(Unit)
            }
        }
        return Result.failure(NoSuchElementException("Pesan tidak ditemukan"))
    }

    override suspend fun deleteMessage(messageId: String, forEveryone: Boolean): Result<Unit> {
        messagesStore.values.forEach { flow ->
            flow.update { list -> list.filterNot { it.id == messageId } }
        }
        return Result.success(Unit)
    }

    override suspend fun copyMessage(message: Message): String {
        return message.text
    }

    override fun getDraft(conversationId: String): String {
        return draftsStore[conversationId] ?: ""
    }

    override fun setDraft(conversationId: String, draft: String) {
        if (draft.isBlank()) {
            draftsStore.remove(conversationId)
        } else {
            draftsStore[conversationId] = draft
        }
    }

    override suspend fun loadOlderMessages(conversationId: String, pageSize: Int): Result<Int> =
        Result.success(0)
}

class FakePresenceRepository : PresenceRepository {
    private val typingMap = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private val isConnectedState = MutableStateFlow(true)

    override fun getPresence(userId: String): Flow<PresenceState> {
        return typingMap.map { map ->
            val isTyping = map[userId] == true
            val isOnline = userId in listOf("usr_1", "usr_3", "usr_5", "usr_8", "usr_12")
            PresenceState(
                isOnline = isOnline,
                isTyping = isTyping,
                lastSeenTimestamp = System.currentTimeMillis() - 10 * 60 * 1000
            )
        }
    }

    override fun getNetworkConnectivity(): Flow<Boolean> = isConnectedState

    override suspend fun setTyping(conversationId: String, isTyping: Boolean) {
        typingMap.update { current ->
            current + (conversationId to isTyping)
        }
    }

    override suspend fun simulateNetworkState(isConnected: Boolean) {
        isConnectedState.value = isConnected
    }
}

class FakeMediaRepository : MediaRepository {
    override suspend fun prepareVoiceNote(
        durationSeconds: Int,
        waveform: List<Float>
    ): Attachment {
        return Attachment(
            id = "vn_" + UUID.randomUUID().toString().take(6),
            type = AttachmentType.AUDIO,
            title = "Pesan suara (${durationSeconds} dtk)",
            sizeBytes = (durationSeconds * 6000).toLong(),
            durationSeconds = durationSeconds,
            waveformPoints = waveform.ifEmpty { listOf(0.3f, 0.6f, 0.9f, 0.4f, 0.7f, 0.5f, 0.8f, 0.3f) }
        )
    }

    override suspend fun prepareMediaAttachment(
        type: AttachmentType,
        fileName: String,
        sizeBytes: Long
    ): Attachment {
        return Attachment(
            id = "att_" + UUID.randomUUID().toString().take(6),
            type = type,
            title = fileName,
            sizeBytes = sizeBytes,
            mimeType = when (type) {
                AttachmentType.IMAGE -> "image/jpeg"
                AttachmentType.VIDEO -> "video/mp4"
                AttachmentType.DOCUMENT -> "application/pdf"
                AttachmentType.AUDIO -> "audio/m4a"
            }
        )
    }
}

class FakeSecurityRepository : SecurityRepository {
    private val _verifiedMap = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    override fun getSecurityInfo(userId: String): Flow<SecurityInfo> = _verifiedMap.map { verifiedMap ->
        SecurityInfo(
            userId = userId,
            isVerified = verifiedMap[userId] ?: false
        )
    }

    override suspend fun verifyIdentity(userId: String, isVerified: Boolean): Result<Boolean> {
        delay(200)
        _verifiedMap.update { it + (userId to isVerified) }
        return Result.success(isVerified)
    }
}

class FakeCallRepository(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) : CallRepository {

    private val _currentCall = MutableStateFlow<CallSession?>(null)
    override val currentCall: StateFlow<CallSession?> = _currentCall.asStateFlow()

    private val _callHistory = MutableStateFlow<List<CallSession>>(FakeDataSource.sampleCallHistory)
    override val callHistory: Flow<List<CallSession>> = _callHistory.asStateFlow()

    private var durationJob: Job? = null

    override suspend fun startCall(userId: String, type: CallType) {
        val user = FakeDataSource.users.find { it.id == userId } ?: FakeDataSource.users.first()
        val session = CallSession(
            callId = "call_" + UUID.randomUUID().toString().take(6),
            otherUser = user,
            type = type,
            state = CallState.RINGING,
            isOutgoing = true,
            durationSeconds = 0
        )
        _currentCall.value = session

        scope.launch {
            delay(2000)
            if (_currentCall.value?.callId == session.callId && _currentCall.value?.state == CallState.RINGING) {
                _currentCall.update { it?.copy(state = CallState.CONNECTING) }
                delay(1200)
                if (_currentCall.value?.callId == session.callId) {
                    _currentCall.update { it?.copy(state = CallState.CONNECTED) }
                    startDurationTimer()
                }
            }
        }
    }

    override suspend fun answerCall() {
        _currentCall.update { it?.copy(state = CallState.CONNECTED) }
        startDurationTimer()
    }

    override suspend fun rejectCall() {
        val current = _currentCall.value ?: return
        durationJob?.cancel()
        _currentCall.update { it?.copy(state = CallState.REJECTED) }
        recordCallHistory(current.copy(state = CallState.REJECTED))
        delay(400)
        _currentCall.value = null
    }

    override suspend fun endCall() {
        val current = _currentCall.value ?: return
        durationJob?.cancel()
        _currentCall.update { it?.copy(state = CallState.ENDED) }
        recordCallHistory(current.copy(state = CallState.ENDED))
        delay(400)
        _currentCall.value = null
    }

    override suspend fun toggleMute() {
        _currentCall.update { it?.copy(isMuted = !(it.isMuted)) }
    }

    override suspend fun toggleSpeaker() {
        _currentCall.update { it?.copy(isSpeakerOn = !(it.isSpeakerOn)) }
    }

    override suspend fun toggleCamera() {
        _currentCall.update { it?.copy(isCameraOff = !(it.isCameraOff)) }
    }

    override suspend fun switchCamera() {
        _currentCall.update { it?.copy(isFrontCamera = !(it.isFrontCamera)) }
    }

    override suspend fun simulateIncomingCall(callerId: String, type: CallType) {
        val user = FakeDataSource.users.find { it.id == callerId } ?: FakeDataSource.users[0]
        _currentCall.value = CallSession(
            callId = "inc_" + UUID.randomUUID().toString().take(6),
            otherUser = user,
            type = type,
            state = CallState.INCOMING,
            isOutgoing = false
        )
    }

    override suspend fun startCallSignaling(conversationId: String, calleeId: String, callType: String): String =
        "call_sim_${System.currentTimeMillis()}"

    override suspend fun answerCallSignaling(callId: String, sdpAnswer: String) {}

    override suspend fun rejectCallSignaling(callId: String) {}

    override suspend fun endCallSignaling(callId: String) {}

    override fun observeIncomingCalls(myUid: String): Flow<com.chatrok.app.core.model.SignalingCallSession?> =
        kotlinx.coroutines.flow.flowOf(null)

    override suspend fun sendIceCandidate(callId: String, candidate: com.chatrok.app.core.model.IceCandidatePayload) {}

    override fun observeIceCandidates(callId: String, remoteUid: String): Flow<com.chatrok.app.core.model.IceCandidatePayload> =
        kotlinx.coroutines.flow.emptyFlow()

    private fun startDurationTimer() {
        durationJob?.cancel()
        durationJob = scope.launch {
            while (true) {
                delay(1000)
                _currentCall.update {
                    if (it != null && it.state == CallState.CONNECTED) {
                        it.copy(durationSeconds = it.durationSeconds + 1)
                    } else it
                }
            }
        }
    }

    private fun recordCallHistory(session: CallSession) {
        _callHistory.update { listOf(session) + it }
    }
}

class FakeNotificationRepository : NotificationRepository {
    private val _enabled = MutableStateFlow(true)
    override val notificationsEnabled: StateFlow<Boolean> = _enabled.asStateFlow()

    override suspend fun setNotificationsEnabled(enabled: Boolean) {
        _enabled.value = enabled
    }
}
