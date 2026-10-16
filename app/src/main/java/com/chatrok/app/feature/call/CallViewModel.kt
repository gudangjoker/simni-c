package com.chatrok.app.feature.call

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chatrok.app.core.model.CallSession
import com.chatrok.app.core.model.CallState
import com.chatrok.app.core.model.CallType
import com.chatrok.app.domain.repository.CallRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CallUiControls(
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val isVideoEnabled: Boolean = true,
    val isFrontCamera: Boolean = true,
    val callDuration: Int = 0
)

@HiltViewModel
class CallViewModel @Inject constructor(
    private val callRepository: CallRepository
) : ViewModel() {

    val currentCall: StateFlow<CallSession?> = callRepository.currentCall
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _controls = MutableStateFlow(CallUiControls())
    val controls: StateFlow<CallUiControls> = _controls.asStateFlow()
    private var durationJob: Job? = null

    init {
        viewModelScope.launch {
            callRepository.currentCall.collect { session ->
                if (session?.state == CallState.CONNECTED) startDurationTimer()
                else if (session == null || session.state in listOf(CallState.ENDED, CallState.REJECTED, CallState.FAILED)) {
                    durationJob?.cancel()
                    _controls.update { it.copy(callDuration = 0) }
                }
            }
        }
    }

    private fun startDurationTimer() {
        durationJob?.cancel()
        durationJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                _controls.update { it.copy(callDuration = it.callDuration + 1) }
            }
        }
    }

    fun answerCall() { viewModelScope.launch { callRepository.answerCall() } }
    fun rejectCall() { viewModelScope.launch { callRepository.rejectCall() } }
    fun endCall() { viewModelScope.launch { callRepository.endCall() } }
    fun toggleMute() { viewModelScope.launch { callRepository.toggleMute() }; _controls.update { it.copy(isMuted = !it.isMuted) } }
    fun toggleSpeaker() { viewModelScope.launch { callRepository.toggleSpeaker() }; _controls.update { it.copy(isSpeakerOn = !it.isSpeakerOn) } }
    fun toggleVideo() { viewModelScope.launch { callRepository.toggleCamera() }; _controls.update { it.copy(isVideoEnabled = !it.isVideoEnabled) } }
    fun switchCamera() { viewModelScope.launch { callRepository.switchCamera() }; _controls.update { it.copy(isFrontCamera = !it.isFrontCamera) } }
    fun simulateIncomingCall(userId: String, type: CallType) { viewModelScope.launch { callRepository.simulateIncomingCall(userId, type) } }
}
