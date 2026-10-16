package com.chatrok.app.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.net.Uri
import com.chatrok.app.core.model.Attachment
import com.chatrok.app.core.model.AttachmentType
import com.chatrok.app.core.model.SecurityInfo
import com.chatrok.app.core.model.User
import com.chatrok.app.domain.repository.AuthRepository
import com.chatrok.app.domain.repository.MediaRepository
import com.chatrok.app.domain.repository.SecurityRepository
import com.chatrok.app.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val securityRepository: SecurityRepository,
    private val mediaRepository: MediaRepository
) : ViewModel() {

    constructor(
        authRepository: AuthRepository,
        userRepository: UserRepository,
        securityRepository: SecurityRepository
    ) : this(
        authRepository,
        userRepository,
        securityRepository,
        object : MediaRepository {
            override suspend fun prepareVoiceNote(durationSeconds: Int, waveform: List<Float>): Attachment = error("Not supported in test")
            override suspend fun prepareMediaAttachment(type: AttachmentType, fileName: String, sizeBytes: Long): Attachment = error("Not supported in test")
            override suspend fun uploadAvatar(bytes: ByteArray, mimeType: String): String = "https://test.avatar/avatar.png"
            override suspend fun deleteAvatar(): Boolean = true
            override suspend fun downloadMedia(objectKey: String): java.io.File? = null
        }
    )

    val currentUser: StateFlow<User?> = authRepository.currentUser
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _selectedUser = MutableStateFlow<User?>(null)
    val selectedUser: StateFlow<User?> = _selectedUser.asStateFlow()

    private val _securityInfo = MutableStateFlow<SecurityInfo?>(null)
    val securityInfo: StateFlow<SecurityInfo?> = _securityInfo.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _isUploadingAvatar = MutableStateFlow(false)
    val isUploadingAvatar: StateFlow<Boolean> = _isUploadingAvatar.asStateFlow()

    private val _avatarUploadSuccess = MutableStateFlow<Boolean?>(null)
    val avatarUploadSuccess: StateFlow<Boolean?> = _avatarUploadSuccess.asStateFlow()

    private var loadUserJob: Job? = null
    private var loadSecurityJob: Job? = null

    fun clearError() {
        _errorMessage.value = null
    }

    fun loadUser(userId: String) {
        loadUserJob?.cancel()
        loadSecurityJob?.cancel()

        loadUserJob = viewModelScope.launch {
            userRepository.getUserById(userId).collect { user ->
                _selectedUser.value = user
            }
        }

        loadSecurityJob = viewModelScope.launch {
            securityRepository.getSecurityInfo(userId).collect { secInfo ->
                _securityInfo.value = secInfo
            }
        }
    }

    fun toggleVerifyIdentity(userId: String) {
        viewModelScope.launch {
            val current = _securityInfo.value ?: return@launch
            val next = !current.isVerified
            securityRepository.verifyIdentity(userId, next)
        }
    }

    fun toggleBlockUser(userId: String) {
        viewModelScope.launch {
            val user = _selectedUser.value ?: return@launch
            val nextBlocked = !user.isBlocked
            val result = userRepository.blockUser(userId, nextBlocked)
            result.onFailure { error ->
                _errorMessage.value = error.message ?: "Gagal memperbarui status blokir pengguna"
            }.onSuccess {
                _selectedUser.value = user.copy(isBlocked = nextBlocked)
            }
        }
    }

    fun updateMyProfile(displayName: String, bio: String, phone: String = "", onComplete: () -> Unit) {
        viewModelScope.launch {
            val current = currentUser.value ?: return@launch
            val updated = current.copy(
                displayName = displayName,
                bio = bio,
                phoneNumber = phone
            )
            userRepository.updateUser(updated)
            authRepository.updateProfile(displayName, bio, current.avatarUrl)
            onComplete()
        }
    }

    fun updateMyProfile(displayName: String, bio: String, onComplete: () -> Unit) {
        updateMyProfile(displayName, bio, "", onComplete)
    }

    fun uploadAvatar(bytes: ByteArray, mimeType: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _isUploadingAvatar.value = true
            try {
                val url = mediaRepository.uploadAvatar(bytes, mimeType)
                val current = currentUser.value ?: return@launch
                val updated = current.copy(avatarUrl = url)
                userRepository.updateUser(updated)
                authRepository.updateProfile(current.displayName, current.bio, url)
                _avatarUploadSuccess.value = true
            } catch (e: Exception) {
                _errorMessage.value = "Gagal mengunggah foto profil: ${e.message}"
                _avatarUploadSuccess.value = false
            } finally {
                _isUploadingAvatar.value = false
            }
        }
    }

    fun uploadAvatarFromUri(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _isUploadingAvatar.value = true
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Tidak dapat membaca file gambar.")
                val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                val url = mediaRepository.uploadAvatar(bytes, mimeType)
                val current = currentUser.value ?: return@launch
                val updated = current.copy(avatarUrl = url)
                userRepository.updateUser(updated)
                authRepository.updateProfile(current.displayName, current.bio, url)
                _avatarUploadSuccess.value = true
            } catch (e: Exception) {
                _errorMessage.value = "Gagal mengunggah foto profil: ${e.message}"
                _avatarUploadSuccess.value = false
            } finally {
                _isUploadingAvatar.value = false
            }
        }
    }


    class Factory(
        private val authRepository: AuthRepository,
        private val userRepository: UserRepository,
        private val securityRepository: SecurityRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ProfileViewModel(authRepository, userRepository, securityRepository) as T
        }
    }
}
