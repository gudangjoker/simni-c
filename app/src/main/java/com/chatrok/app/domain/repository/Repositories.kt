package com.chatrok.app.domain.repository

import com.chatrok.app.core.model.Attachment
import com.chatrok.app.core.model.AttachmentType
import com.chatrok.app.core.model.CallSession
import com.chatrok.app.core.model.CallType
import com.chatrok.app.core.model.Conversation
import com.chatrok.app.core.model.Message
import com.chatrok.app.core.model.PresenceState
import com.chatrok.app.core.model.ReplyPreview
import com.chatrok.app.core.model.SecurityInfo
import com.chatrok.app.core.model.User
import kotlinx.coroutines.flow.Flow
import com.chatrok.app.core.model.SessionBootstrapState
import kotlinx.coroutines.flow.StateFlow

interface AuthRepository {
    val currentUser: StateFlow<User?>
    val sessionBootstrapState: StateFlow<SessionBootstrapState>
    suspend fun login(email: String, password: String): Result<User>
    suspend fun register(email: String, password: String, username: String, displayName: String): Result<User>
    suspend fun logout()
    suspend fun updateProfile(displayName: String, bio: String, avatarUrl: String?): Result<User>
    suspend fun sendPasswordResetEmail(email: String): Result<Unit>
}

interface UserRepository {
    fun getUsers(): Flow<List<User>>
    fun getUserById(userId: String): Flow<User?>
    fun searchUsers(query: String): Flow<List<User>>
    suspend fun blockUser(userId: String, isBlocked: Boolean): Result<Unit>
    suspend fun updateUser(user: User): Result<Unit>
}

interface ConversationRepository {
    fun getConversations(): Flow<List<Conversation>>
    fun getConversationById(id: String): Flow<Conversation?>
    suspend fun getOrCreateConversationWith(userId: String): Conversation
    suspend fun deleteConversation(id: String): Result<Unit>
    suspend fun markAsRead(id: String)
    suspend fun clearChatMessages(conversationId: String): Result<Unit>
}

interface MessageRepository {
    fun getMessages(conversationId: String): Flow<List<Message>>
    suspend fun sendMessage(
        conversationId: String,
        text: String,
        replyTo: ReplyPreview? = null
    ): Result<Message>
    suspend fun sendAttachment(
        conversationId: String,
        attachment: Attachment,
        caption: String? = null,
        replyTo: ReplyPreview? = null
    ): Result<Message>
    suspend fun retryMessage(messageId: String): Result<Unit>
    suspend fun deleteMessage(messageId: String, forEveryone: Boolean): Result<Unit>
    suspend fun copyMessage(message: Message): String
    fun getDraft(conversationId: String): String
    fun setDraft(conversationId: String, draft: String)
    suspend fun loadOlderMessages(conversationId: String, pageSize: Int = 50): Result<Int>
}

interface PresenceRepository {
    fun getPresence(userId: String): Flow<PresenceState>
    fun getPresence(userId: String, conversationId: String?): Flow<PresenceState> = getPresence(userId)
    fun getNetworkConnectivity(): Flow<Boolean>
    suspend fun setPresence(isOnline: Boolean) {}
    suspend fun setTyping(conversationId: String, isTyping: Boolean)
    suspend fun simulateNetworkState(isConnected: Boolean)
}

interface MediaRepository {
    suspend fun prepareVoiceNote(durationSeconds: Int, waveform: List<Float>): Attachment
    suspend fun prepareMediaAttachment(type: AttachmentType, fileName: String, sizeBytes: Long): Attachment
    suspend fun uploadAvatar(bytes: ByteArray, mimeType: String): String = throw UnsupportedOperationException("Upload avatar belum didukung.")
    suspend fun deleteAvatar(): Boolean = false
    suspend fun downloadMedia(objectKey: String): java.io.File? = null
}

interface SecurityRepository {
    fun getSecurityInfo(userId: String): Flow<SecurityInfo>
    suspend fun verifyIdentity(userId: String, isVerified: Boolean = true): Result<Boolean>
}

interface CallRepository {
    val currentCall: StateFlow<CallSession?>
    val callHistory: Flow<List<CallSession>>
    suspend fun startCall(userId: String, type: CallType)
    suspend fun answerCall()
    suspend fun rejectCall()
    suspend fun endCall()
    suspend fun toggleMute()
    suspend fun toggleSpeaker()
    suspend fun toggleCamera()
    suspend fun switchCamera()
    suspend fun simulateIncomingCall(callerId: String, type: CallType)

    // WebRTC Signaling Channel API
    suspend fun startCallSignaling(conversationId: String, calleeId: String, callType: String): String
    suspend fun answerCallSignaling(callId: String, sdpAnswer: String)
    suspend fun rejectCallSignaling(callId: String)
    suspend fun endCallSignaling(callId: String)
    fun observeIncomingCalls(myUid: String): Flow<com.chatrok.app.core.model.SignalingCallSession?>
    suspend fun sendIceCandidate(callId: String, candidate: com.chatrok.app.core.model.IceCandidatePayload)
    fun observeIceCandidates(callId: String, remoteUid: String): Flow<com.chatrok.app.core.model.IceCandidatePayload>
}

interface NotificationRepository {
    val notificationsEnabled: StateFlow<Boolean>
    suspend fun setNotificationsEnabled(enabled: Boolean)
}
