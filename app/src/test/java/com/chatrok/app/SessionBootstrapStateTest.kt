package com.chatrok.app

import com.chatrok.app.core.common.ChatrokError
import com.chatrok.app.core.common.UserProfileLoadException
import com.chatrok.app.core.common.UserProfileNotFoundException
import com.chatrok.app.core.model.SessionBootstrapState
import com.chatrok.app.core.model.User
import com.chatrok.app.data.auth.AuthServiceGateway
import com.chatrok.app.data.auth.FirebaseAuthRepository
import com.chatrok.app.data.auth.RegistrationCoordinator
import com.chatrok.app.data.auth.UserProfileGateway
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeAuthServiceGateway : AuthServiceGateway {
    override var currentFirebaseUserUid: String? = null
    var signInResult: Result<String> = Result.success("default_uid")
    var signOutCalled = false
    private val listeners = mutableListOf<(String?) -> Unit>()

    override suspend fun signIn(email: String, pass: String): Result<String> {
        return signInResult.onSuccess { uid ->
            currentFirebaseUserUid = uid
            notifyListeners(uid)
        }
    }

    override suspend fun signOut() {
        signOutCalled = true
        currentFirebaseUserUid = null
        notifyListeners(null)
    }

    override fun addAuthStateListener(listener: (uid: String?) -> Unit) {
        listeners.add(listener)
    }

    fun notifyListeners(uid: String?) {
        listeners.forEach { it(uid) }
    }
}

class FakeUserProfileGateway : UserProfileGateway {
    var profileResult: Result<User?> = Result.success(null)
    var updateResult: Result<Unit> = Result.success(Unit)
    var requestedUid: String? = null

    override suspend fun loadUserProfile(uid: String): Result<User?> {
        requestedUid = uid
        return profileResult
    }

    override suspend fun updateProfileAtomic(
        uid: String,
        privateUpdates: Map<String, Any>,
        publicUpdates: Map<String, Any>
    ): Result<Unit> {
        return updateResult
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SessionBootstrapStateTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Test
    fun testLoginSuccessWithValidProfileTransitionsToReadyAndSetsCurrentUser() = runBlocking {
        // [Kriteria A]: Auth login sukses + profile sukses -> Ready(user), currentUser terisi
        val authService = FakeAuthServiceGateway().apply {
            signInResult = Result.success("uid_budi_01")
        }
        val expectedUser = User(
            id = "uid_budi_01",
            username = "budi_test",
            displayName = "Budi Santoso",
            bio = "Halo CHATROK",
            isOnline = true
        )
        val profileGateway = FakeUserProfileGateway().apply {
            profileResult = Result.success(expectedUser)
        }

        val repository = createTestRepository(authService, profileGateway, testScope)

        val result = repository.login("budi@example.com", "secret123")

        assertTrue("Login harus sukses", result.isSuccess)
        assertEquals(expectedUser, result.getOrNull())
        assertEquals(expectedUser, repository.currentUser.value)

        val state = repository.sessionBootstrapState.value
        assertTrue("Session state harus Ready", state is SessionBootstrapState.Ready)
        assertEquals(expectedUser, (state as SessionBootstrapState.Ready).user)
    }

    @Test
    fun testLoginSuccessButProfileReadFailsTransitionsToErrorAndCurrentUserNull() = runBlocking {
        // [Kriteria B]: Auth login sukses + profile read gagal -> Error, currentUser null, Result.failure, TANPA fallback user
        val authService = FakeAuthServiceGateway().apply {
            signInResult = Result.success("uid_error_02")
        }
        val profileGateway = FakeUserProfileGateway().apply {
            profileResult = Result.failure(IOException("Firestore read timeout"))
        }

        val repository = createTestRepository(authService, profileGateway, testScope)

        val result = repository.login("error@example.com", "secret123")

        assertTrue("Login harus gagal saat pembacaan profil error", result.isFailure)
        assertNull("currentUser WAJIB null dan dilarang membuat fallback user fiktif", repository.currentUser.value)

        val state = repository.sessionBootstrapState.value
        assertTrue("Session state harus Error", state is SessionBootstrapState.Error)
    }

    @Test
    fun testLoginSuccessButProfileDocumentMissingTransitionsToErrorAndNoFallbackUser() = runBlocking {
        // [Kriteria C]: Profile document hilang -> Error, currentUser null, tidak ada fallback user
        val authService = FakeAuthServiceGateway().apply {
            signInResult = Result.success("uid_missing_03")
        }
        val profileGateway = FakeUserProfileGateway().apply {
            profileResult = Result.success(null) // Snapshot tidak ada / dokumen hilang
        }

        val repository = createTestRepository(authService, profileGateway, testScope)

        val result = repository.login("missing@example.com", "secret123")

        assertTrue("Login harus gagal saat dokumen profil tidak ada", result.isFailure)
        assertNull("currentUser WAJIB null (dilarang membuat fallback user dari email/UID)", repository.currentUser.value)

        val state = repository.sessionBootstrapState.value
        assertTrue("Session state harus Error", state is SessionBootstrapState.Error)
        val errorObj = (state as SessionBootstrapState.Error).cause
        assertTrue(
            "Error harus mengindikasikan UserProfileNotFoundException",
            errorObj is UserProfileNotFoundException || errorObj.cause is UserProfileNotFoundException
        )

        val uiMessage = ChatrokError.formatAuthError(errorObj)
        assertEquals("Profil akun tidak ditemukan. Silakan hubungi administrator atau daftar ulang.", uiMessage)
    }

    @Test
    fun testSessionRestoreWithExistingFirebaseUserButFailingProfileTransitionsToErrorNotReady() = runBlocking {
        // [Kriteria D]: Session restore saat app start dengan Firebase user ada tetapi profile gagal -> tidak Ready
        val authService = FakeAuthServiceGateway().apply {
            currentFirebaseUserUid = "uid_restored_04"
        }
        val profileGateway = FakeUserProfileGateway().apply {
            profileResult = Result.failure(IOException("Network unreachable during restore"))
        }

        val repository = createTestRepository(authService, profileGateway, testScope)
        repository.restoreSession("uid_restored_04")

        assertNull("currentUser harus null saat session restore gagal", repository.currentUser.value)
        val state = repository.sessionBootstrapState.value
        assertFalse("Session state DILARANG Ready jika profil gagal dipulihkan", state is SessionBootstrapState.Ready)
        assertTrue("Session state harus Error", state is SessionBootstrapState.Error)
    }

    @Test
    fun testLogoutTransitionsToUnauthenticatedAndClearsCurrentUser() = runBlocking {
        // [Kriteria E]: Logout -> Unauthenticated, currentUser null
        val expectedUser = User(id = "uid_logout_05", username = "logout_user", displayName = "Logout User")
        val authService = FakeAuthServiceGateway().apply {
            signInResult = Result.success("uid_logout_05")
        }
        val profileGateway = FakeUserProfileGateway().apply {
            profileResult = Result.success(expectedUser)
        }

        val repository = createTestRepository(authService, profileGateway, testScope)
        repository.login("logout@example.com", "secret123")

        assertTrue(repository.sessionBootstrapState.value is SessionBootstrapState.Ready)

        repository.logout()

        assertTrue("Sign out gateway harus dipanggil", authService.signOutCalled)
        assertNull("currentUser harus null setelah logout", repository.currentUser.value)
        assertEquals(
            "Session state harus Unauthenticated setelah logout",
            SessionBootstrapState.Unauthenticated,
            repository.sessionBootstrapState.value
        )
    }

    @Test
    fun testErrorStatePreservesTypedCauseAndUserFacingMessageIsObtainedViaChatrokErrorNotLocalizedMessage() = runBlocking {
        // [Kriteria 5A, 5B, 5C]: SessionBootstrapState.Error menyimpan typed/cause error,
        // user-facing text diperoleh melalui ChatrokError.formatAuthError, bukan error.localizedMessage
        val rawBackendError = IOException("DEADLINE_EXCEEDED: Failed to get document publicProfiles/uid_123 after 10000ms")
        val authService = FakeAuthServiceGateway().apply {
            signInResult = Result.success("uid_123")
        }
        val profileGateway = FakeUserProfileGateway().apply {
            profileResult = Result.failure(rawBackendError)
        }

        val repository = createTestRepository(authService, profileGateway, testScope)
        val result = repository.login("user@test.com", "password123")

        assertTrue(result.isFailure)
        val state = repository.sessionBootstrapState.value
        assertTrue("SessionBootstrapState harus bertipe Error", state is SessionBootstrapState.Error)

        val errorState = state as SessionBootstrapState.Error
        // 5A: Menyimpan typed/cause error
        assertTrue(
            "Cause harus berupa UserProfileLoadException atau IOException asli",
            errorState.cause is UserProfileLoadException || errorState.cause is IOException
        )

        // 5B: User-facing text diperoleh melalui ChatrokError
        val formattedUiMessage = ChatrokError.formatAuthError(errorState.cause)
        assertEquals(
            "Gagal memuat profil pengguna. Periksa koneksi internet Anda.",
            formattedUiMessage
        )

        // 5C: Raw backend message TIDAK terekspos sebagai formattedUiMessage
        assertFalse(
            "Pesan UI tidak boleh mengekspos teks mentah backend",
            formattedUiMessage.contains("DEADLINE_EXCEEDED")
        )
        assertFalse(
            "Pesan UI tidak boleh mengekspos publicProfiles/uid_123",
            formattedUiMessage.contains("publicProfiles/uid_123")
        )
    }

    private fun createTestRepository(
        authService: AuthServiceGateway,
        profileGateway: UserProfileGateway,
        scope: CoroutineScope
    ): FirebaseAuthRepository {
        val authGateway = CapturingAuthGateway()
        val firestoreGateway = NoOpFirestoreGateway()
        val coordinator = RegistrationCoordinator(authGateway, firestoreGateway)

        return FirebaseAuthRepository(
            scope = scope,
            registrationCoordinator = coordinator,
            profileGateway = profileGateway,
            authServiceGateway = authService
        )
    }
}
