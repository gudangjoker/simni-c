package com.chatrok.app.data.unavailable

import com.chatrok.app.core.common.FeatureUnavailableException
import com.chatrok.app.core.model.Attachment
import com.chatrok.app.core.model.AttachmentType
import com.chatrok.app.core.model.CallSession
import com.chatrok.app.core.model.CallType
import com.chatrok.app.core.model.Conversation
import com.chatrok.app.core.model.Message
import com.chatrok.app.core.model.PresenceState
import com.chatrok.app.core.model.ReplyPreview
import com.chatrok.app.core.model.SecurityInfo
import com.chatrok.app.domain.repository.CallRepository
import com.chatrok.app.domain.repository.ConversationRepository
import com.chatrok.app.domain.repository.MediaRepository
import com.chatrok.app.domain.repository.MessageRepository
import com.chatrok.app.domain.repository.NotificationRepository
import com.chatrok.app.domain.repository.PresenceRepository
import com.chatrok.app.domain.repository.SecurityRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Honest unavailable implementation for [ConversationRepository] for Milestone 1.
 * Actions fail explicitly with [FeatureUnavailableException] instead of faking persistence.
 */
@Singleton
class UnavailableConversationRepository @Inject constructor() : ConversationRepository {

    override fun getConversations(): Flow<List<Conversation>> = flowOf(emptyList())

    override fun getConversationById(id: String): Flow<Conversation?> = flowOf(null)

    override suspend fun getOrCreateConversationWith(userId: String): Conversation {
        throw FeatureUnavailableException(
            "conversation",
            "Fitur percakapan dijadwalkan pada milestone berikutnya."
        )
    }

    override suspend fun deleteConversation(id: String): Result<Unit> {
        return Result.failure(
            FeatureUnavailableException(
                "conversation",
                "Fitur percakapan dijadwalkan pada milestone berikutnya."
            )
        )
    }

    override suspend fun markAsRead(id: String) {
        // No-op for unavailable feature
    }

    override suspend fun clearChatMessages(conversationId: String): Result<Unit> {
        return Result.failure(
            FeatureUnavailableException(
                "conversation",
                "Fitur percakapan dijadwalkan pada milestone berikutnya."
            )
        )
    }
}

/**
 * Honest unavailable implementation for [MessageRepository] for Milestone 1.
 */
@Singleton
class UnavailableMessageRepository @Inject constructor() : MessageRepository {

    override fun getMessages(conversationId: String): Flow<List<Message>> = flowOf(emptyList())

    override suspend fun sendMessage(
        conversationId: String,
        text: String,
        replyTo: ReplyPreview?
    ): Result<Message> {
        return Result.failure(
            FeatureUnavailableException(
                "message",
                "Fitur pengiriman pesan dijadwalkan pada milestone berikutnya."
            )
        )
    }

    override suspend fun sendAttachment(
        conversationId: String,
        attachment: Attachment,
        caption: String?,
        replyTo: ReplyPreview?
    ): Result<Message> {
        return Result.failure(
            FeatureUnavailableException(
                "message",
                "Fitur pengiriman lampiran dijadwalkan pada milestone berikutnya."
            )
        )
    }

    override suspend fun retryMessage(messageId: String): Result<Unit> {
        return Result.failure(
            FeatureUnavailableException(
                "message",
                "Fitur kirim ulang pesan dijadwalkan pada milestone berikutnya."
            )
        )
    }

    override suspend fun deleteMessage(messageId: String, forEveryone: Boolean): Result<Unit> {
        return Result.failure(
            FeatureUnavailableException(
                "message",
                "Fitur penghapusan pesan dijadwalkan pada milestone berikutnya."
            )
        )
    }

    override suspend fun copyMessage(message: Message): String = message.text

    override fun getDraft(conversationId: String): String = ""

    override fun setDraft(conversationId: String, draft: String) {
        // No-op for unavailable feature
    }

    override suspend fun loadOlderMessages(conversationId: String, pageSize: Int): Result<Int> =
        Result.failure(
            FeatureUnavailableException(
                "message",
                "Fitur pagination pesan dijadwalkan pada milestone berikutnya."
            )
        )
}

/**
 * Honest unavailable implementation for [PresenceRepository] for Milestone 1.
 */
@Singleton
class UnavailablePresenceRepository @Inject constructor() : PresenceRepository {

    private val _connectivity = MutableStateFlow(true)

    override fun getPresence(userId: String): Flow<PresenceState> =
        flowOf(PresenceState(isOnline = false, isTyping = false))

    override fun getNetworkConnectivity(): Flow<Boolean> = _connectivity.asStateFlow()

    override suspend fun setTyping(conversationId: String, isTyping: Boolean) {
        // No-op for unavailable feature
    }

    override suspend fun simulateNetworkState(isConnected: Boolean) {
        _connectivity.value = isConnected
    }
}

/**
 * Honest unavailable implementation for [MediaRepository] for Milestone 1.
 */
@Singleton
class UnavailableMediaRepository @Inject constructor() : MediaRepository {

    override suspend fun prepareVoiceNote(durationSeconds: Int, waveform: List<Float>): Attachment {
        throw FeatureUnavailableException(
            "media",
            "Fitur pesan suara dijadwalkan pada milestone berikutnya."
        )
    }

    override suspend fun prepareMediaAttachment(
        type: AttachmentType,
        fileName: String,
        sizeBytes: Long
    ): Attachment {
        throw FeatureUnavailableException(
            "media",
            "Fitur lampiran media dijadwalkan pada milestone berikutnya."
        )
    }
}

/**
 * Honest unavailable implementation for [SecurityRepository] for Milestone 1.
 */
@Singleton
class UnavailableSecurityRepository @Inject constructor() : SecurityRepository {

    override fun getSecurityInfo(userId: String): Flow<SecurityInfo> = flowOf(
        SecurityInfo(
            userId = userId,
            isVerified = false
        )
    )

    override suspend fun verifyIdentity(userId: String, isVerified: Boolean): Result<Boolean> {
        return Result.failure(
            FeatureUnavailableException(
                "security",
                "Fitur verifikasi identitas dijadwalkan pada milestone berikutnya."
            )
        )
    }
}

/**
 * Honest unavailable implementation for [CallRepository] for Milestone 1.
 */
@Singleton
class UnavailableCallRepository @Inject constructor() : CallRepository {

    private val _currentCall = MutableStateFlow<CallSession?>(null)
    override val currentCall: StateFlow<CallSession?> = _currentCall.asStateFlow()

    override val callHistory: Flow<List<CallSession>> = flowOf(emptyList())

    override suspend fun startCall(userId: String, type: CallType) {
        throw FeatureUnavailableException(
            "call",
            "Fitur panggilan suara/video dijadwalkan pada milestone berikutnya."
        )
    }

    override suspend fun answerCall() {
        throw FeatureUnavailableException(
            "call",
            "Fitur panggilan suara/video dijadwalkan pada milestone berikutnya."
        )
    }

    override suspend fun rejectCall() {
        // No-op for unavailable call
    }

    override suspend fun endCall() {
        _currentCall.value = null
    }

    override suspend fun toggleMute() {
        // No-op
    }

    override suspend fun toggleSpeaker() {
        // No-op
    }

    override suspend fun toggleCamera() {
        // No-op
    }

    override suspend fun switchCamera() {
        // No-op
    }

    override suspend fun simulateIncomingCall(callerId: String, type: CallType) {
        // No-op in production
    }

    override suspend fun startCallSignaling(conversationId: String, calleeId: String, callType: String): String = ""
    override suspend fun answerCallSignaling(callId: String, sdpAnswer: String) {}
    override suspend fun rejectCallSignaling(callId: String) {}
    override suspend fun endCallSignaling(callId: String) {}
    override fun observeIncomingCalls(myUid: String): Flow<com.chatrok.app.core.model.SignalingCallSession?> = flowOf(null)
    override suspend fun sendIceCandidate(callId: String, candidate: com.chatrok.app.core.model.IceCandidatePayload) {}
    override fun observeIceCandidates(callId: String, remoteUid: String): Flow<com.chatrok.app.core.model.IceCandidatePayload> = kotlinx.coroutines.flow.emptyFlow()
}

/**
 * Honest unavailable implementation for [NotificationRepository] for Milestone 1.
 */
@Singleton
class UnavailableNotificationRepository @Inject constructor() : NotificationRepository {

    private val _notificationsEnabled = MutableStateFlow(false)
    override val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()

    override suspend fun setNotificationsEnabled(enabled: Boolean) {
        _notificationsEnabled.value = enabled
    }
}
