package com.chatrok.app.core.model

enum class MessageStatus {
    SENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED
}

enum class MessageType {
    TEXT,
    IMAGE,
    VIDEO,
    VOICE_NOTE,
    DOCUMENT,
    CALL_EVENT,
    SYSTEM
}

enum class AttachmentType {
    IMAGE,
    VIDEO,
    DOCUMENT,
    AUDIO
}

data class Attachment(
    val id: String,
    val type: AttachmentType,
    val title: String,
    val sizeBytes: Long,
    val durationSeconds: Int? = null,
    val uriOrUrl: String = "",
    val mimeType: String = "",
    val uploadProgress: Float = 1.0f,
    val isUploading: Boolean = false,
    val waveformPoints: List<Float> = emptyList(),
    val objectKey: String? = null,
    val expiresAt: Long? = null,
    val mediaIv: String? = null,
    val isExpired: Boolean = false,
    val localFilePath: String? = null
)

data class ReplyPreview(
    val messageId: String,
    val senderName: String,
    val snippet: String,
    val type: MessageType = MessageType.TEXT,
    val senderId: String? = null
)

data class Message(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val text: String,
    val timestamp: Long,
    val status: MessageStatus = MessageStatus.SENT,
    val type: MessageType = MessageType.TEXT,
    val attachment: Attachment? = null,
    val replyTo: ReplyPreview? = null,
    val isOutgoing: Boolean = true
)

const val DEFAULT_USER_BIO = "Pengguna SIMNI-C"

data class User(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val bio: String = DEFAULT_USER_BIO,
    val phoneNumber: String? = null,
    val isOnline: Boolean = false,
    val lastSeen: Long = System.currentTimeMillis(),
    val isBlocked: Boolean = false
)

data class PresenceState(
    val isOnline: Boolean = false,
    val isTyping: Boolean = false,
    val lastSeenTimestamp: Long = System.currentTimeMillis()
)

data class Conversation(
    val id: String,
    val otherUser: User,
    val lastMessage: Message? = null,
    val unreadCount: Int = 0,
    val lastUpdated: Long = System.currentTimeMillis(),
    val isPinned: Boolean = false,
    val isMuted: Boolean = false
)

enum class CallType {
    VOICE,
    VIDEO
}

enum class CallState {
    IDLE,
    RINGING,
    INCOMING,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    REJECTED,
    MISSED,
    ENDED,
    FAILED
}

data class CallSession(
    val callId: String,
    val otherUser: User,
    val type: CallType,
    val state: CallState,
    val isOutgoing: Boolean,
    val durationSeconds: Int = 0,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val isCameraOff: Boolean = false,
    val isFrontCamera: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
)

enum class SecurityIntegrationState {
    PENDING
}

data class SecurityInfo(
    val userId: String,
    val integrationState: SecurityIntegrationState = SecurityIntegrationState.PENDING,
    val fingerprint: String? = null,
    val qrPayload: String? = null,
    val isVerified: Boolean = false,
    val safetyNumber: String = fingerprint ?: "00000 00000 00000 00000 00000 00000 00000 00000 00000 00000 00000 00000"
)

data class IceCandidatePayload(
    val candidate: String = "",
    val sdpMid: String = "",
    val sdpMLineIndex: Int = 0,
    val senderId: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

data class SignalingCallSession(
    val callId: String = "",
    val conversationId: String = "",
    val callerId: String = "",
    val calleeId: String = "",
    val callType: String = "audio",
    val status: String = "ringing",
    val sdpOffer: String? = null,
    val sdpAnswer: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
