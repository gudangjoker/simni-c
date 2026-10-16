package com.chatrok.app.feature.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chatrok.app.core.model.CallSession
import com.chatrok.app.core.model.Conversation
import com.chatrok.app.core.model.User
import com.chatrok.app.domain.repository.CallRepository
import com.chatrok.app.domain.repository.ConversationRepository
import com.chatrok.app.domain.repository.PresenceRepository
import com.chatrok.app.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

import com.chatrok.app.domain.repository.AuthRepository
import com.chatrok.app.core.model.SessionBootstrapState

data class InboxUiState(
    val conversations: List<Conversation> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val isConnected: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class InboxViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val userRepository: UserRepository,
    private val presenceRepository: PresenceRepository,
    private val callRepository: CallRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    constructor(
        conversationRepository: ConversationRepository,
        userRepository: UserRepository,
        presenceRepository: PresenceRepository,
        callRepository: CallRepository
    ) : this(
        conversationRepository,
        userRepository,
        presenceRepository,
        callRepository,
        object : AuthRepository {
            override val currentUser: StateFlow<User?> = MutableStateFlow(null)
            override val sessionBootstrapState: StateFlow<SessionBootstrapState> = MutableStateFlow(SessionBootstrapState.Unauthenticated)
            override suspend fun login(email: String, password: String) = Result.failure<User>(NotImplementedError())
            override suspend fun register(email: String, password: String, username: String, displayName: String) = Result.failure<User>(NotImplementedError())
            override suspend fun logout() {}
            override suspend fun updateProfile(displayName: String, bio: String, avatarUrl: String?) = Result.failure<User>(NotImplementedError())
            override suspend fun sendPasswordResetEmail(email: String): Result<Unit> = Result.success(Unit)
        }
    )

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    fun clearActionError() {
        _actionError.value = null
    }

    val isConnected: StateFlow<Boolean> = presenceRepository.getNetworkConnectivity()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val allUsers: StateFlow<List<User>> = combine(
        userRepository.getUsers(),
        authRepository.currentUser
    ) { users, currentUser ->
        val currentUid = currentUser?.id
        users.filter { it.id != currentUid }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val callHistory: StateFlow<List<CallSession>> = callRepository.callHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val uiState: StateFlow<InboxUiState> = combine(
        conversationRepository.getConversations(),
        _searchQuery,
        presenceRepository.getNetworkConnectivity()
    ) { conversations, query, connected ->
        val filtered = if (query.isBlank()) conversations else conversations.filter {
            it.otherUser.displayName.contains(query, ignoreCase = true) ||
                it.otherUser.username.contains(query, ignoreCase = true) ||
                (it.lastMessage?.text?.contains(query, ignoreCase = true) == true)
        }
        InboxUiState(filtered, query, false, connected, null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), InboxUiState(isLoading = true))

    fun onSearchQueryChange(query: String) { _searchQuery.value = query }

    fun retryConnection() {
        viewModelScope.launch { presenceRepository.simulateNetworkState(true) }
    }

    fun toggleNetworkForTesting() {
        viewModelScope.launch { presenceRepository.simulateNetworkState(!isConnected.value) }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch { conversationRepository.deleteConversation(id) }
    }

    fun createOrOpenConversation(userId: String, onResult: (String) -> Unit) {
        val currentUid = authRepository.currentUser.value?.id
        if (currentUid != null && currentUid == userId) {
            _actionError.value = "Tidak dapat memulai obrolan dengan akun sendiri."
            return
        }
        viewModelScope.launch {
            try {
                val conversation = conversationRepository.getOrCreateConversationWith(userId)
                onResult(conversation.id)
            } catch (e: Exception) {
                _actionError.value = e.message ?: "Gagal membuka percakapan"
            }
        }
    }
}
