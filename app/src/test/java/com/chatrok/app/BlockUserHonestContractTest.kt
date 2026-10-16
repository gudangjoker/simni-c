package com.chatrok.app

import com.chatrok.app.core.common.FeatureUnavailableException
import com.chatrok.app.core.model.User
import com.chatrok.app.data.repository.FakeAuthRepository
import com.chatrok.app.data.repository.FakeSecurityRepository
import com.chatrok.app.data.repository.FakeUserRepository
import com.chatrok.app.data.user.FirestoreUserGateway
import com.chatrok.app.data.user.FirestoreUserRepository
import com.chatrok.app.feature.profile.ProfileViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SpyFirestoreUserGateway : FirestoreUserGateway {
    val usersFlow = MutableStateFlow<List<User>>(
        listOf(
            User(
                id = "user_test_1",
                username = "budi_test",
                displayName = "Budi Test",
                isBlocked = false
            ),
            User(
                id = "user_test_2",
                username = "ani_test",
                displayName = "Ani Test",
                isBlocked = false
            )
        )
    )

    var updateUserCalls = 0
    var totalWriteCalls = 0

    override fun getUsers(): Flow<List<User>> = usersFlow

    override fun getUserById(userId: String): Flow<User?> = usersFlow.map { list ->
        list.find { it.id == userId }
    }

    override suspend fun updateUser(user: User): Result<Unit> {
        updateUserCalls++
        totalWriteCalls++
        return Result.success(Unit)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BlockUserHonestContractTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun testBlockUserReturnsFailureWithUnsupportedOperationExceptionInProductionRepository() = runTest {
        val spyGateway = SpyFirestoreUserGateway()
        val repository = FirestoreUserRepository(spyGateway)

        val result = repository.blockUser("user_test_1", true)

        assertTrue("blockUser harus mengembalikan failure", result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull("Exception tidak boleh null", exception)
        assertTrue("Exception harus bertipe UnsupportedOperationException", exception is UnsupportedOperationException)
        assertTrue("Exception harus bertipe FeatureUnavailableException", exception is FeatureUnavailableException)
        assertEquals(
            "Fitur pemblokiran pengguna dijadwalkan pada milestone berikutnya.",
            exception?.message
        )
    }

    @Test
    fun testBlockUserDoesNotPerformAnyWriteOperationToFirestoreGateway() = runTest {
        val spyGateway = SpyFirestoreUserGateway()
        val repository = FirestoreUserRepository(spyGateway)

        val result = repository.blockUser("user_test_1", true)

        assertTrue("blockUser harus mengembalikan failure", result.isFailure)
        assertEquals("Dilarang memanggil updateUser pada Firestore saat blockUser", 0, spyGateway.updateUserCalls)
        assertEquals("Dilarang melakukan operasi penulisan apapun ke Firestore saat blockUser", 0, spyGateway.totalWriteCalls)
    }

    @Test
    fun testProductionRepositoryDoesNotMutateLocalMemoryStateAfterBlockUserCall() = runTest {
        val spyGateway = SpyFirestoreUserGateway()
        val repository = FirestoreUserRepository(spyGateway)

        // Verifikasi initial state: isBlocked false
        val initialUser = repository.getUserById("user_test_1").first()
        assertNotNull("User harus ditemukan", initialUser)
        assertFalse("User awalnya tidak diblokir", initialUser!!.isBlocked)

        // Panggil blockUser
        val result = repository.blockUser("user_test_1", true)
        assertTrue(result.isFailure)

        // Verifikasi state repository lokal tidak berubah seolah-olah berhasil
        val postUser = repository.getUserById("user_test_1").first()
        assertNotNull("User harus ditemukan setelah blockUser", postUser)
        assertFalse("State repository lokal tidak boleh memalsukan isBlocked = true", postUser!!.isBlocked)

        val allUsers = repository.getUsers().first()
        assertTrue("Semua pengguna tetap isBlocked = false", allUsers.none { it.isBlocked })
    }

    @Test
    fun testViewModelCallerHandlesBlockUserFailureGracefullyWithoutUpdatingStateToBlocked() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        val testScope = CoroutineScope(testDispatcher)
        val spyGateway = SpyFirestoreUserGateway()
        val productionRepo = FirestoreUserRepository(spyGateway)
        val fakeAuthRepo = FakeAuthRepository(testScope)
        val fakeSecRepo = FakeSecurityRepository()

        val viewModel = ProfileViewModel(fakeAuthRepo, productionRepo, fakeSecRepo)
        viewModel.loadUser("user_test_1")

        val selectedBefore = viewModel.selectedUser.value
        assertNotNull("Selected user harus ter-load", selectedBefore)
        assertFalse("User awalnya tidak diblokir", selectedBefore!!.isBlocked)

        // Toggle block
        viewModel.toggleBlockUser("user_test_1")

        // Verifikasi: tidak crash, user tidak menjadi isBlocked = true
        val selectedAfter = viewModel.selectedUser.value
        assertNotNull("Selected user tetap ada", selectedAfter)
        assertFalse("ViewModel tidak boleh mengubah state menjadi blocked saat repo failure", selectedAfter!!.isBlocked)

        // Verifikasi pesan error domain berbahasa Indonesia terekspos
        val errorMsg = viewModel.errorMessage.value
        assertNotNull("Pesan error harus terekspos di ViewModel", errorMsg)
        assertTrue(
            "Pesan error harus menginformasikan milestone berikutnya",
            errorMsg!!.contains("Fitur pemblokiran pengguna dijadwalkan pada milestone berikutnya.")
        )
    }

    @Test
    fun testFakeUserRepositoryInTestPathPreservesSimulatedBlockBehaviorForPreviews() = runTest {
        val fakeRepo = FakeUserRepository()

        val initialUser = fakeRepo.getUserById("usr_1").first()
        assertNotNull("User awal pada fake repo harus ada", initialUser)
        assertFalse("User awal pada fake repo tidak blocked", initialUser!!.isBlocked)

        val result = fakeRepo.blockUser("usr_1", true)
        assertTrue("FakeUserRepository untuk preview/test boleh mensimulasikan sukses", result.isSuccess)

        val blockedUser = fakeRepo.getUserById("usr_1").first()
        assertNotNull("User setelah block harus ada", blockedUser)
        assertTrue("FakeUserRepository mengupdate isBlocked untuk testing", blockedUser!!.isBlocked)
    }
}
