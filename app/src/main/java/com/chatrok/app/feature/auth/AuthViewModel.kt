package com.chatrok.app.feature.auth

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chatrok.app.R
import com.chatrok.app.core.model.SessionBootstrapState
import com.chatrok.app.core.model.User
import com.chatrok.app.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AuthUiState(
    val isLoading: Boolean = false,
    @param:StringRes val errorResId: Int? = null,
    val errorMessage: String? = null,
    val isSuccess: Boolean = false
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val e2eeIdentityManager: com.chatrok.app.core.security.E2eeIdentityManager
) : ViewModel() {

    val currentUser: StateFlow<User?> = authRepository.currentUser
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val sessionBootstrapState: StateFlow<SessionBootstrapState> = authRepository.sessionBootstrapState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SessionBootstrapState.Loading)

    val sessionErrorMessage: StateFlow<String?> = authRepository.sessionBootstrapState
        .map { state ->
            if (state is SessionBootstrapState.Error) {
                com.chatrok.app.core.common.ChatrokError.formatAuthError(state.cause)
            } else {
                null
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        // Silent E2EE warmup untuk user yang sesi-nya sudah terautentikasi sejak awal
        viewModelScope.launch {
            authRepository.currentUser.collect { user ->
                if (user != null) {
                    try {
                        e2eeIdentityManager.warmUpIdentity(user.id)
                    } catch (_: Exception) {
                        // Silent warmup non-blocking
                    }
                }
            }
        }
    }

    fun formatSessionError(cause: Throwable): String {
        return com.chatrok.app.core.common.ChatrokError.formatAuthError(cause)
    }

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun login(email: String, pass: String) {
        viewModelScope.launch {
            _uiState.value = AuthUiState(isLoading = true)
            val result = authRepository.login(email, pass)
            result.fold(
                onSuccess = { user ->
                    try {
                        e2eeIdentityManager.warmUpIdentity(user.id)
                    } catch (_: Exception) {
                        // Silent warmup non-blocking
                    }
                    _uiState.value = AuthUiState(isSuccess = true)
                },
                onFailure = { err ->
                    _uiState.value = AuthUiState(
                        errorResId = R.string.auth_failed,
                        errorMessage = com.chatrok.app.core.common.ChatrokError.formatAuthError(err)
                    )
                }
            )
        }
    }

    fun register(email: String, pass: String, username: String, displayName: String) {
        viewModelScope.launch {
            _uiState.value = AuthUiState(isLoading = true)
            val result = authRepository.register(email, pass, username, displayName)
            result.fold(
                onSuccess = { user ->
                    try {
                        e2eeIdentityManager.warmUpIdentity(user.id)
                    } catch (_: Exception) {
                        // Silent warmup non-blocking
                    }
                    _uiState.value = AuthUiState(isSuccess = true)
                },
                onFailure = { err ->
                    _uiState.value = AuthUiState(
                        errorResId = R.string.registration_failed,
                        errorMessage = com.chatrok.app.core.common.ChatrokError.formatAuthError(err)
                    )
                }
            )
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorResId = null, errorMessage = null)
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            _uiState.value = AuthUiState()
        }
    }

    fun sendPasswordReset(email: String, onResult: (isSuccess: Boolean, message: String) -> Unit) {
        viewModelScope.launch {
            val result = authRepository.sendPasswordResetEmail(email)
            result.fold(
                onSuccess = {
                    onResult(true, "Tautan reset kata sandi telah dikirim ke email Anda. Silakan periksa kotak masuk atau folder spam.")
                },
                onFailure = { err ->
                    val errorMsg = com.chatrok.app.core.common.ChatrokError.formatAuthError(err)
                    onResult(false, errorMsg)
                }
            )
        }
    }
}
