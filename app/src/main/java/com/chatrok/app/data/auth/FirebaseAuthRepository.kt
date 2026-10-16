package com.chatrok.app.data.auth

import com.chatrok.app.core.common.ChatrokError
import com.chatrok.app.core.common.ProfileUpdateFailedException
import com.chatrok.app.core.common.UserProfileLoadException
import com.chatrok.app.core.common.UserProfileNotFoundException
import com.chatrok.app.core.model.DEFAULT_USER_BIO
import com.chatrok.app.core.model.SessionBootstrapState
import com.chatrok.app.core.model.User
import com.chatrok.app.domain.repository.AuthRepository
import com.chatrok.app.domain.repository.PushTokenRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

interface UserProfileGateway {
    suspend fun loadUserProfile(uid: String): Result<User?>
    suspend fun updateProfileAtomic(
        uid: String,
        privateUpdates: Map<String, Any>,
        publicUpdates: Map<String, Any>
    ): Result<Unit>
}

class DefaultUserProfileGateway(
    private val firestore: FirebaseFirestore
) : UserProfileGateway {
    override suspend fun loadUserProfile(uid: String): Result<User?> = withContext(Dispatchers.IO) {
        try {
            val snapshot = firestore.collection("publicProfiles").document(uid).get().await()
            if (!snapshot.exists()) {
                return@withContext Result.success(null)
            }
            val username = snapshot.getString("username")
            if (username.isNullOrBlank()) {
                return@withContext Result.success(null)
            }
            val displayName = snapshot.getString("displayName") ?: username
            val avatarUrl = snapshot.getString("avatarUrl")
            val bio = snapshot.getString("bio") ?: DEFAULT_USER_BIO
            Result.success(
                User(
                    id = uid,
                    username = username,
                    displayName = displayName,
                    avatarUrl = avatarUrl,
                    bio = bio,
                    isOnline = true,
                    lastSeen = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateProfileAtomic(
        uid: String,
        privateUpdates: Map<String, Any>,
        publicUpdates: Map<String, Any>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val batch = firestore.batch()
            val userRef = firestore.collection("users").document(uid)
            val publicProfileRef = firestore.collection("publicProfiles").document(uid)

            batch.set(userRef, privateUpdates, SetOptions.merge())
            batch.set(publicProfileRef, publicUpdates, SetOptions.merge())

            batch.commit().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

interface AuthServiceGateway {
    val currentFirebaseUserUid: String?
    suspend fun signIn(email: String, pass: String): Result<String>
    suspend fun signOut()
    suspend fun sendPasswordResetEmail(email: String): Result<Unit>
    fun addAuthStateListener(listener: (uid: String?) -> Unit)
}

class DefaultAuthServiceGateway(
    private val auth: FirebaseAuth
) : AuthServiceGateway {
    override val currentFirebaseUserUid: String?
        get() = auth.currentUser?.uid

    override suspend fun signIn(email: String, pass: String): Result<String> = try {
        val authResult = auth.signInWithEmailAndPassword(email, pass).await()
        val user = authResult.user
        if (user != null) {
            Result.success(user.uid)
        } else {
            Result.failure(IllegalStateException("Pengguna tidak ditemukan."))
        }
    } catch (t: Throwable) {
        Result.failure(t)
    }

    override suspend fun signOut() {
        auth.signOut()
    }

    override suspend fun sendPasswordResetEmail(email: String): Result<Unit> = try {
        auth.sendPasswordResetEmail(email).await()
        Result.success(Unit)
    } catch (t: Throwable) {
        Result.failure(t)
    }

    override fun addAuthStateListener(listener: (uid: String?) -> Unit) {
        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            listener(user?.uid)
        }
    }
}

@Singleton
class FirebaseAuthRepository(
    private val scope: CoroutineScope,
    private val registrationCoordinator: RegistrationCoordinator,
    private val profileGateway: UserProfileGateway,
    private val authServiceGateway: AuthServiceGateway,
    private val pushTokenRepository: PushTokenRepository? = null
) : AuthRepository {

    @Inject
    constructor(
        auth: FirebaseAuth,
        firestore: FirebaseFirestore,
        scope: CoroutineScope,
        pushTokenRepository: PushTokenRepository
    ) : this(
        scope = scope,
        registrationCoordinator = RegistrationCoordinator(
            DefaultRegistrationAuthGateway(auth),
            DefaultRegistrationFirestoreGateway(firestore)
        ),
        profileGateway = DefaultUserProfileGateway(firestore),
        authServiceGateway = DefaultAuthServiceGateway(auth),
        pushTokenRepository = pushTokenRepository
    )

    constructor(
        auth: FirebaseAuth,
        firestore: FirebaseFirestore,
        scope: CoroutineScope
    ) : this(
        scope = scope,
        registrationCoordinator = RegistrationCoordinator(
            DefaultRegistrationAuthGateway(auth),
            DefaultRegistrationFirestoreGateway(firestore)
        ),
        profileGateway = DefaultUserProfileGateway(firestore),
        authServiceGateway = DefaultAuthServiceGateway(auth),
        pushTokenRepository = null
    )

    private val _currentUser = MutableStateFlow<User?>(null)
    override val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    private val _sessionBootstrapState = MutableStateFlow<SessionBootstrapState>(
        if (authServiceGateway.currentFirebaseUserUid != null) {
            SessionBootstrapState.Loading
        } else {
            SessionBootstrapState.Unauthenticated
        }
    )
    override val sessionBootstrapState: StateFlow<SessionBootstrapState> = _sessionBootstrapState.asStateFlow()

    init {
        val initialUid = authServiceGateway.currentFirebaseUserUid
        if (initialUid != null) {
            scope.launch {
                restoreSession(initialUid)
            }
        }

        authServiceGateway.addAuthStateListener { uid ->
            if (uid == null) {
                _currentUser.value = null
                _sessionBootstrapState.value = SessionBootstrapState.Unauthenticated
            } else {
                if (_currentUser.value?.id != uid && _sessionBootstrapState.value !is SessionBootstrapState.Loading) {
                    scope.launch {
                        restoreSession(uid)
                    }
                }
            }
        }
    }

    suspend fun restoreSession(uid: String) {
        _sessionBootstrapState.value = SessionBootstrapState.Loading
        val profileResult = profileGateway.loadUserProfile(uid)
        profileResult.fold(
            onSuccess = { user ->
                if (user != null) {
                    _currentUser.value = user
                    _sessionBootstrapState.value = SessionBootstrapState.Ready(user)
                } else {
                    _currentUser.value = null
                    val err = UserProfileNotFoundException(uid)
                    _sessionBootstrapState.value = SessionBootstrapState.Error(err)
                }
            },
            onFailure = { t ->
                _currentUser.value = null
                val err = UserProfileLoadException(uid, t)
                _sessionBootstrapState.value = SessionBootstrapState.Error(err)
            }
        )
    }

    override suspend fun login(email: String, password: String): Result<User> = withContext(Dispatchers.IO) {
        val cleanEmail = email.trim()
        if (cleanEmail.isBlank() || password.isEmpty()) {
            val err = IllegalArgumentException(ChatrokError.formatAuthError(IllegalArgumentException("wrong-password")))
            return@withContext Result.failure(err)
        }

        _sessionBootstrapState.value = SessionBootstrapState.Loading

        val signInResult = authServiceGateway.signIn(cleanEmail, password)
        val uid = signInResult.getOrElse { t ->
            val domainError = Exception(ChatrokError.formatAuthError(t), t)
            _currentUser.value = null
            _sessionBootstrapState.value = SessionBootstrapState.Error(domainError)
            return@withContext Result.failure(domainError)
        }

        // Auth sukses -> load private/public profile
        val profileResult = profileGateway.loadUserProfile(uid)
        profileResult.fold(
            onSuccess = { user ->
                if (user != null) {
                    _currentUser.value = user
                    _sessionBootstrapState.value = SessionBootstrapState.Ready(user)
                    Result.success(user)
                } else {
                    // Dokumen wajib hilang -> JANGAN fallback user!
                    _currentUser.value = null
                    val err = UserProfileNotFoundException(uid)
                    _sessionBootstrapState.value = SessionBootstrapState.Error(err)
                    Result.failure(Exception(ChatrokError.formatAuthError(err), err))
                }
            },
            onFailure = { t ->
                // Profile load gagal -> JANGAN fallback user!
                _currentUser.value = null
                val err = UserProfileLoadException(uid, t)
                _sessionBootstrapState.value = SessionBootstrapState.Error(err)
                Result.failure(Exception(ChatrokError.formatAuthError(err), err))
            }
        )
    }

    override suspend fun register(
        email: String,
        password: String,
        username: String,
        displayName: String
    ): Result<User> = withContext(Dispatchers.IO) {
        _sessionBootstrapState.value = SessionBootstrapState.Loading
        val result = registrationCoordinator.registerUser(email, password, username, displayName)
        result.fold(
            onSuccess = { user ->
                _currentUser.value = user
                _sessionBootstrapState.value = SessionBootstrapState.Ready(user)
                Result.success(user)
            },
            onFailure = { t ->
                if (_currentUser.value == null) {
                    _sessionBootstrapState.value = SessionBootstrapState.Unauthenticated
                }
                Result.failure(t)
            }
        )
    }

    override suspend fun logout() = withContext(Dispatchers.IO) {
        val uid = _currentUser.value?.id ?: authServiceGateway.currentFirebaseUserUid
        if (!uid.isNullOrBlank()) {
            try {
                pushTokenRepository?.deactivateUserTokens(uid)
            } catch (_: Exception) {}
        }
        runCatching {
            authServiceGateway.signOut()
        }
        _currentUser.value = null
        _sessionBootstrapState.value = SessionBootstrapState.Unauthenticated
    }

    override suspend fun updateProfile(
        displayName: String,
        bio: String,
        avatarUrl: String?
    ): Result<User> = withContext(Dispatchers.IO) {
        val current = _currentUser.value
            ?: return@withContext Result.failure(IllegalStateException("Pengguna belum masuk"))

        try {
            val uid = current.id
            val newDisplayName = displayName.ifBlank { current.displayName }.trim()
            val newBio = bio.trim()
            val newAvatarUrl = avatarUrl ?: current.avatarUrl ?: ""
            val timestamp = FieldValue.serverTimestamp()

            // 1. Dokumen users/{uid} (Private Profile):
            // Sinkronkan displayName dan updatedAt. Dilarang menulis email atau mengubah username.
            val privateUpdates = hashMapOf<String, Any>(
                "displayName" to newDisplayName,
                "updatedAt" to timestamp
            )

            // 2. Dokumen publicProfiles/{uid} (Public Profile):
            // displayName, bio, avatarUrl, updatedAt. Dilarang menulis email atau mengubah username.
            val publicUpdates = hashMapOf<String, Any>(
                "displayName" to newDisplayName,
                "bio" to newBio,
                "avatarUrl" to newAvatarUrl,
                "updatedAt" to timestamp
            )

            val updateResult = profileGateway.updateProfileAtomic(
                uid = uid,
                privateUpdates = privateUpdates,
                publicUpdates = publicUpdates
            )

            updateResult.fold(
                onSuccess = {
                    val updated = current.copy(
                        displayName = newDisplayName,
                        bio = newBio,
                        avatarUrl = newAvatarUrl.ifBlank { null }
                    )
                    _currentUser.value = updated
                    _sessionBootstrapState.value = SessionBootstrapState.Ready(updated)
                    Result.success(updated)
                },
                onFailure = { t ->
                    // Gagal: currentUser TIDAK di-update
                    val domainErr = ProfileUpdateFailedException(uid, t)
                    Result.failure(domainErr)
                }
            )
        } catch (t: Throwable) {
            val domainErr = ProfileUpdateFailedException(current.id, t)
            Result.failure(domainErr)
        }
    }

    override suspend fun sendPasswordResetEmail(email: String): Result<Unit> = withContext(Dispatchers.IO) {
        val trimmedEmail = email.trim()
        if (trimmedEmail.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(trimmedEmail).matches()) {
            return@withContext Result.failure(IllegalArgumentException("Format email tidak valid."))
        }
        authServiceGateway.sendPasswordResetEmail(trimmedEmail)
    }
}
