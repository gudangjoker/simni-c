package com.chatrok.app.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chatrok.app.core.model.Attachment
import com.chatrok.app.core.model.AttachmentType
import com.chatrok.app.core.model.CallType
import com.chatrok.app.core.model.Conversation
import com.chatrok.app.core.model.Message
import com.chatrok.app.core.model.PresenceState
import com.chatrok.app.core.presence.PresenceCore
import com.chatrok.app.core.model.ReplyPreview
import com.chatrok.app.core.model.User
import com.chatrok.app.domain.repository.CallRepository
import com.chatrok.app.domain.repository.ConversationRepository
import com.chatrok.app.domain.repository.MediaRepository
import com.chatrok.app.domain.repository.MessageRepository
import com.chatrok.app.domain.repository.PresenceRepository
import com.chatrok.app.domain.repository.UserRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

enum class RecordingState {
    IDLE,
    RECORDING,
    LOCKED,
    PREVIEW
}

data class VoiceNoteRecording(
    val state: RecordingState = RecordingState.IDLE,
    val durationSeconds: Int = 0,
    val waveformPoints: List<Float> = emptyList(),
    val isPlayingPreview: Boolean = false,
    val previewProgress: Float = 0f
)

data class ChatUiState(
    val conversation: Conversation? = null,
    val otherUser: User? = null,
    val messages: List<Message> = emptyList(),
    val presence: PresenceState = PresenceState(),
    val replyingTo: ReplyPreview? = null,
    val selectedMessage: Message? = null,
    val draftText: String = "",
    val recordingState: VoiceNoteRecording = VoiceNoteRecording(),
    val showAttachmentSheet: Boolean = false,
    val isBlocked: Boolean = false
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val conversationRepository: ConversationRepository,
    private val userRepository: UserRepository,
    private val messageRepository: MessageRepository,
    private val presenceRepository: PresenceRepository,
    private val mediaRepository: MediaRepository,
    private val callRepository: CallRepository
) : ViewModel() {

    private val conversationId: String = checkNotNull(savedStateHandle["conversationId"])
    private val otherUserId: String = checkNotNull(savedStateHandle["userId"])

    private val _replyingTo = MutableStateFlow<ReplyPreview?>(null)

    private val _selectedMessage = MutableStateFlow<Message?>(null)
    private val _draftText = MutableStateFlow(messageRepository.getDraft(conversationId))
    private val _recording = MutableStateFlow(VoiceNoteRecording())
    private val _showAttachmentSheet = MutableStateFlow(false)

    private data class LocalChatState(
        val replyingTo: ReplyPreview? = null,
        val selectedMessage: Message? = null,
        val draftText: String = "",
        val recording: VoiceNoteRecording = VoiceNoteRecording(),
        val showAttachmentSheet: Boolean = false
    )

    private val localChatState: Flow<LocalChatState> = combine(
        _replyingTo,
        _selectedMessage,
        _draftText,
        _recording,
        _showAttachmentSheet
    ) { reply, selected, draft, recording, showSheet ->
        LocalChatState(
            replyingTo = reply,
            selectedMessage = selected,
            draftText = draft,
            recording = recording,
            showAttachmentSheet = showSheet
        )
    }

    private var recordingTimerJob: Job? = null
    private var typingJob: Job? = null
    private var isLoadingOlderMessages = false
    private var hasMoreOlderMessages = true

    val isConnected: StateFlow<Boolean> = presenceRepository.getNetworkConnectivity()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val uiState: StateFlow<ChatUiState> = combine(
        conversationRepository.getConversationById(conversationId),
        userRepository.getUserById(otherUserId),
        messageRepository.getMessages(conversationId),
        presenceRepository.getPresence(otherUserId, conversationId),
        localChatState
    ) { conv, user, msgs, presence, local ->
        ChatUiState(
            conversation = conv,
            otherUser = user ?: conv?.otherUser,
            messages = msgs,
            presence = presence,
            replyingTo = local.replyingTo,
            selectedMessage = local.selectedMessage,
            draftText = local.draftText,
            recordingState = local.recording,
            showAttachmentSheet = local.showAttachmentSheet,
            isBlocked = user?.isBlocked == true
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ChatUiState(draftText = messageRepository.getDraft(conversationId))
    )

    init {
        viewModelScope.launch {
            try {
                conversationRepository.markAsRead(conversationId)
            } catch (_: Exception) {}
        }
        viewModelScope.launch {
            try {
                messageRepository.getMessages(conversationId).collect { msgs ->
                    val hasIncoming = msgs.any { !it.isOutgoing }
                    if (hasIncoming) {
                        try {
                            conversationRepository.markAsRead(conversationId)
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun loadOlderMessages() {
        if (isLoadingOlderMessages || !hasMoreOlderMessages) return
        isLoadingOlderMessages = true
        viewModelScope.launch {
            messageRepository.loadOlderMessages(conversationId, pageSize = 50)
                .onSuccess { loaded ->
                    if (loaded < 50) hasMoreOlderMessages = false
                }
            isLoadingOlderMessages = false
        }
    }

    fun onDraftChange(text: String) {
        _draftText.value = text
        messageRepository.setDraft(conversationId, text)
        typingJob?.cancel()
        if (text.isNotBlank()) {
            viewModelScope.launch {
                try {
                    presenceRepository.setTyping(conversationId, true)
                } catch (_: Exception) {}
            }
            typingJob = viewModelScope.launch {
                delay(PresenceCore.TYPING_DEBOUNCE_MS)
                try {
                    presenceRepository.setTyping(conversationId, false)
                } catch (_: Exception) {}
            }
        } else {
            viewModelScope.launch {
                try {
                    presenceRepository.setTyping(conversationId, false)
                } catch (_: Exception) {}
            }
        }
    }

    fun sendMessage() {
        val text = _draftText.value.trim()
        if (text.isBlank()) return
        val reply = _replyingTo.value
        typingJob?.cancel()
        viewModelScope.launch {
            try {
                presenceRepository.setTyping(conversationId, false)
            } catch (_: Exception) {}
        }

        viewModelScope.launch {
            _draftText.value = ""
            messageRepository.setDraft(conversationId, "")
            _replyingTo.value = null
            messageRepository.sendMessage(conversationId, text, reply)
        }
    }

    fun sendAttachment(attachment: Attachment, caption: String? = null) {
        val reply = _replyingTo.value
        viewModelScope.launch {
            _replyingTo.value = null
            _showAttachmentSheet.value = false
            messageRepository.sendAttachment(conversationId, attachment, caption, reply)
        }
    }

    fun retryMessage(messageId: String) {
        viewModelScope.launch {
            messageRepository.retryMessage(messageId)
        }
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            messageRepository.deleteMessage(messageId, forEveryone = true)
            _selectedMessage.value = null
        }
    }

    fun selectMessage(message: Message?) {
        _selectedMessage.value = message
    }

    fun setReplyTo(message: Message) {
        _replyingTo.value = ReplyPreview(
            messageId = message.id,
            senderName = if (message.isOutgoing) "Anda" else (uiState.value.otherUser?.displayName ?: "Kontak"),
            snippet = message.text.ifBlank { message.type.name },
            type = message.type,
            senderId = message.senderId
        )
        _selectedMessage.value = null
    }

    fun clearReply() {
        _replyingTo.value = null
    }

    fun setAttachmentSheetVisible(visible: Boolean) {
        _showAttachmentSheet.value = visible
    }

    fun clearChatHistory() {
        viewModelScope.launch {
            conversationRepository.clearChatMessages(conversationId)
        }
    }

    fun toggleBlockUser() {
        val current = uiState.value.otherUser ?: return
        viewModelScope.launch {
            val result = userRepository.blockUser(current.id, !current.isBlocked)
            result.onFailure {
                // Milestone 1: Graceful failure handling, no fake UI state alteration
            }
        }
    }

    fun startCall(type: CallType) {
        viewModelScope.launch {
            callRepository.startCall(otherUserId, type)
        }
    }

    // Voice Note Flow UI
    fun startRecording() {
        recordingTimerJob?.cancel()
        _recording.value = VoiceNoteRecording(
            state = RecordingState.RECORDING,
            durationSeconds = 0,
            waveformPoints = listOf(0.2f, 0.4f, 0.3f)
        )
        recordingTimerJob = viewModelScope.launch {
            val randomPoints = mutableListOf(0.2f, 0.4f, 0.3f)
            while (_recording.value.state == RecordingState.RECORDING || _recording.value.state == RecordingState.LOCKED) {
                delay(1000)
                randomPoints.add((2..10).random() / 10f)
                _recording.update {
                    it.copy(
                        durationSeconds = it.durationSeconds + 1,
                        waveformPoints = randomPoints.takeLast(24)
                    )
                }
            }
        }
    }

    fun lockRecording() {
        _recording.update { it.copy(state = RecordingState.LOCKED) }
    }

    fun stopRecordingForPreview() {
        recordingTimerJob?.cancel()
        _recording.update { it.copy(state = RecordingState.PREVIEW) }
    }

    fun cancelRecording() {
        recordingTimerJob?.cancel()
        _recording.value = VoiceNoteRecording(state = RecordingState.IDLE)
    }

    fun sendVoiceNote() {
        val rec = _recording.value
        if (rec.durationSeconds < 1) {
            cancelRecording()
            return
        }
        viewModelScope.launch {
            val attachment = mediaRepository.prepareVoiceNote(rec.durationSeconds, rec.waveformPoints)
            cancelRecording()
            sendAttachment(attachment, "Pesan suara (${rec.durationSeconds} dtk)")
        }
    }

    fun togglePreviewPlayback() {
        val current = _recording.value
        val isPlaying = !current.isPlayingPreview
        _recording.update { it.copy(isPlayingPreview = isPlaying) }

        if (isPlaying) {
            viewModelScope.launch {
                for (i in 1..10) {
                    delay((current.durationSeconds.coerceAtLeast(1) * 100).toLong())
                    if (!_recording.value.isPlayingPreview) break
                    _recording.update { it.copy(previewProgress = i / 10f) }
                }
                _recording.update { it.copy(isPlayingPreview = false, previewProgress = 0f) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        typingJob?.cancel()
        if (_draftText.value.isNotBlank()) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    presenceRepository.setTyping(conversationId, false)
                } catch (_: Exception) {}
            }
        }
    }
}
