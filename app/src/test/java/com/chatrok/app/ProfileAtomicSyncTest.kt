package com.chatrok.app

import com.chatrok.app.core.common.ProfileUpdateFailedException
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpyAtomicUserProfileGateway : UserProfileGateway {
    var capturedUid: String? = null
    var capturedPrivateUpdates: Map<String, Any>? = null
    var capturedPublicUpdates: Map<String, Any>? = null
    var atomicUpdateCalled = false
    var batchFailureException: Throwable? = null

    override suspend fun loadUserProfile(uid: String): Result<User?> {
        return Result.success(
            User(
                id = uid,
                username = "joko_user",
                displayName = "Joko Lama",
                bio = "Bio Lama",
                avatarUrl = "https://chatrok.local/old.png",
                isOnline = true
            )
        )
    }

    override suspend fun updateProfileAtomic(
        uid: String,
        privateUpdates: Map<String, Any>,
        publicUpdates: Map<String, Any>
    ): Result<Unit> {
        atomicUpdateCalled = true
        capturedUid = uid
        capturedPrivateUpdates = privateUpdates
        capturedPublicUpdates = publicUpdates

        batchFailureException?.let { return Result.failure(it) }
        return Result.success(Unit)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileAtomicSyncTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private fun setupRepositoryWithUser(
        userProfileGateway: UserProfileGateway,
        scope: CoroutineScope
    ): FirebaseAuthRepository {
        val authService = FakeAuthServiceGateway().apply {
            signInResult = Result.success("uid_joko_77")
        }
        val coordinator = RegistrationCoordinator(CapturingAuthGateway(), NoOpFirestoreGateway())
        val repository = FirebaseAuthRepository(
            scope = scope,
            registrationCoordinator = coordinator,
            profileGateway = userProfileGateway,
            authServiceGateway = authService
        )
        // Lakukan login agar _currentUser terisi
        runBlocking {
            repository.login("joko@chatrok.id", "secret123")
        }
        return repository
    }

    @Test
    fun testUpdateDisplayNameSyncsAcrossPrivateAndPublicProfiles() = runBlocking {
        // [Kriteria A]: update displayName -> users/{uid}.displayName dan publicProfiles/{uid}.displayName berubah
        val spyGateway = SpyAtomicUserProfileGateway()
        val repository = setupRepositoryWithUser(spyGateway, testScope)

        val result = repository.updateProfile(
            displayName = "Joko Baharu",
            bio = "Bio Baharu",
            avatarUrl = "https://chatrok.local/new.png"
        )

        assertTrue("Update profile harus sukses", result.isSuccess)
        assertTrue("Atomic update harus dipanggil", spyGateway.atomicUpdateCalled)
        assertEquals("uid_joko_77", spyGateway.capturedUid)

        val privateUpdates = spyGateway.capturedPrivateUpdates
        val publicUpdates = spyGateway.capturedPublicUpdates
        assertNotNull(privateUpdates)
        assertNotNull(publicUpdates)

        assertEquals("Joko Baharu", privateUpdates?.get("displayName"))
        assertEquals("Joko Baharu", publicUpdates?.get("displayName"))

        // Keduanya wajib memiliki timestamp sinkron
        assertNotNull(privateUpdates?.get("updatedAt"))
        assertNotNull(publicUpdates?.get("updatedAt"))
    }

    @Test
    fun testBioAndAvatarUrlAreWrittenOnlyToPublicProfilesNotPrivateUsersDocument() = runBlocking {
        // [Kriteria B]: bio/avatarUrl hanya ditulis ke publicProfiles sesuai contract
        val spyGateway = SpyAtomicUserProfileGateway()
        val repository = setupRepositoryWithUser(spyGateway, testScope)

        repository.updateProfile(
            displayName = "Joko Baharu",
            bio = "Bio Publik Saja",
            avatarUrl = "https://chatrok.local/avatar_public.png"
        )

        val privateUpdates = spyGateway.capturedPrivateUpdates!!
        val publicUpdates = spyGateway.capturedPublicUpdates!!

        // publicProfiles harus memuat bio dan avatarUrl
        assertEquals("Bio Publik Saja", publicUpdates["bio"])
        assertEquals("https://chatrok.local/avatar_public.png", publicUpdates["avatarUrl"])

        // users/{uid} (private) DILARANG memuat bio atau avatarUrl
        assertFalse("users/{uid} dilarang memuat field bio", privateUpdates.containsKey("bio"))
        assertFalse("users/{uid} dilarang memuat field avatarUrl", privateUpdates.containsKey("avatarUrl"))
    }

    @Test
    fun testZeroEmailLeakToPublicProfiles() = runBlocking {
        // [Kriteria C]: email tidak pernah masuk publicProfiles
        val spyGateway = SpyAtomicUserProfileGateway()
        val repository = setupRepositoryWithUser(spyGateway, testScope)

        repository.updateProfile(
            displayName = "Joko Baharu",
            bio = "Bio Netral",
            avatarUrl = null
        )

        val publicUpdates = spyGateway.capturedPublicUpdates!!
        assertFalse("publicProfiles DILARANG memuat field email", publicUpdates.containsKey("email"))
        publicUpdates.values.filterIsInstance<String>().forEach { strValue ->
            assertFalse(
                "Nilai String pada publicProfiles tidak boleh memuat email: $strValue",
                strValue.contains("@chatrok") || strValue.contains("@example")
            )
        }
    }

    @Test
    fun testUsernameCannotBeChangedViaUpdateProfile() = runBlocking {
        // [Kriteria D]: username tidak berubah
        val spyGateway = SpyAtomicUserProfileGateway()
        val repository = setupRepositoryWithUser(spyGateway, testScope)

        val initialUsername = repository.currentUser.value?.username
        assertEquals("joko_user", initialUsername)

        repository.updateProfile(
            displayName = "Nama Baru",
            bio = "Bio Baru",
            avatarUrl = null
        )

        val privateUpdates = spyGateway.capturedPrivateUpdates!!
        val publicUpdates = spyGateway.capturedPublicUpdates!!

        assertFalse("privateUpdates dilarang mengubah username", privateUpdates.containsKey("username"))
        assertFalse("privateUpdates dilarang mengubah usernameLower", privateUpdates.containsKey("usernameLower"))
        assertFalse("publicUpdates dilarang mengubah username", publicUpdates.containsKey("username"))
        assertFalse("publicUpdates dilarang mengubah usernameLower", publicUpdates.containsKey("usernameLower"))

        // Nilai username pada currentUser tetap sama persis
        assertEquals("joko_user", repository.currentUser.value?.username)
    }

    @Test
    fun testBatchFailureKeepsOldCurrentUserAndReturnsTypedError() = runBlocking {
        // [Kriteria E]: batch failure -> Result.failure, currentUser tetap nilai lama
        val spyGateway = SpyAtomicUserProfileGateway().apply {
            batchFailureException = IOException("Firestore WriteBatch commit timeout")
        }
        val repository = setupRepositoryWithUser(spyGateway, testScope)

        val oldUser = repository.currentUser.value!!
        assertEquals("Joko Lama", oldUser.displayName)

        val result = repository.updateProfile(
            displayName = "Nama Gagal",
            bio = "Bio Gagal",
            avatarUrl = null
        )

        assertTrue("Hasil harus failure saat batch commit gagal", result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue("Error harus berupa ProfileUpdateFailedException", ex is ProfileUpdateFailedException)

        // currentUser DILARANG berubah jika batch commit gagal
        val currentUserAfterFailure = repository.currentUser.value
        assertEquals("Joko Lama", currentUserAfterFailure?.displayName)
        assertEquals("Bio Lama", currentUserAfterFailure?.bio)
    }

    @Test
    fun testBatchSuccessUpdatesCurrentUserAndSessionBootstrapState() = runBlocking {
        // [Kriteria F]: batch success -> currentUser ter-update dan SessionBootstrapState.Ready
        val spyGateway = SpyAtomicUserProfileGateway()
        val repository = setupRepositoryWithUser(spyGateway, testScope)

        val result = repository.updateProfile(
            displayName = "Joko Sukses",
            bio = "Bio Sukses",
            avatarUrl = "https://chatrok.local/success.png"
        )

        assertTrue(result.isSuccess)
        val updatedUser = repository.currentUser.value
        assertNotNull(updatedUser)
        assertEquals("Joko Sukses", updatedUser?.displayName)
        assertEquals("Bio Sukses", updatedUser?.bio)
        assertEquals("https://chatrok.local/success.png", updatedUser?.avatarUrl)

        val state = repository.sessionBootstrapState.value
        assertTrue("State sesi harus Ready", state is SessionBootstrapState.Ready)
        assertEquals("Joko Sukses", (state as SessionBootstrapState.Ready).user.displayName)
    }
}
