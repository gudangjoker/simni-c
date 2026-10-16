package com.chatrok.app

import com.chatrok.app.core.common.ChatrokError
import com.chatrok.app.core.common.UsernameAlreadyTakenException
import com.chatrok.app.core.common.UsernamePrecheckFailedException
import com.chatrok.app.data.auth.AuthAccount
import com.chatrok.app.data.auth.RegistrationAuthGateway
import com.chatrok.app.data.auth.RegistrationCoordinator
import com.chatrok.app.data.auth.RegistrationFirestoreGateway
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendFirestoreException(val code: String, message: String) : Exception(message)

class SpyAuthGateway : RegistrationAuthGateway {
    var createAccountCalled = false
    var deleteAccountCalled = false
    val createdAccounts = mutableListOf<AuthAccount>()

    override suspend fun createAccount(email: String, pass: String): AuthAccount {
        createAccountCalled = true
        val account = AuthAccount(uid = "uid_" + email.substringBefore("@"), email = email)
        createdAccounts.add(account)
        return account
    }

    override suspend fun deleteAccount(targetUid: String, currentSessionUid: String?) {
        deleteAccountCalled = true
    }
}

class ControllableFirestoreGateway : RegistrationFirestoreGateway {
    var precheckException: Throwable? = null
    var precheckReturnsExists: Boolean = false
    var transactionThrowsCollision: Boolean = false
    var transactionExecuted = false

    override suspend fun checkUsernameExists(usernameLower: String): Boolean {
        precheckException?.let { throw it }
        return precheckReturnsExists
    }

    override suspend fun executeRegistrationTransaction(
        uid: String,
        email: String,
        usernameLower: String,
        displayName: String
    ) {
        transactionExecuted = true
        if (transactionThrowsCollision) {
            throw UsernameAlreadyTakenException(usernameLower)
        }
    }
}

class UsernameFailClosedTest {

    @Test
    fun testPrecheckThrowsIoExceptionFailsClosedWithoutCallingCreateAccount() = runBlocking {
        // [Kriteria A]: checkUsernameExists() melempar IOException -> register Result.failure -> createAccount TIDAK dipanggil
        val authGateway = SpyAuthGateway()
        val firestoreGateway = ControllableFirestoreGateway().apply {
            precheckException = IOException("Network connection lost during precheck")
        }

        val coordinator = RegistrationCoordinator(authGateway, firestoreGateway)
        val result = coordinator.registerUser("user@example.com", "password123", "budi_test", "Budi Test")

        assertTrue("Registrasi wajib gagal (fail-closed) ketika pre-check error", result.isFailure)
        assertFalse("createAccount DILARANG dipanggil saat pre-check error", authGateway.createAccountCalled)

        val ex = result.exceptionOrNull()
        assertTrue("Exception harus berupa UsernamePrecheckFailedException", ex is UsernamePrecheckFailedException)
        assertTrue("Cause harus berupa IOException asli", (ex as UsernamePrecheckFailedException).originalError is IOException)
    }

    @Test
    fun testPrecheckThrowsFirestoreExceptionFailsClosedWithoutCallingCreateAccount() = runBlocking {
        // [Kriteria B]: checkUsernameExists() melempar Firestore/backend exception equivalent -> createAccount TIDAK dipanggil
        val authGateway = SpyAuthGateway()
        val firestoreGateway = ControllableFirestoreGateway().apply {
            precheckException = BackendFirestoreException(
                "PERMISSION_DENIED",
                "Permission denied on collection usernames"
            )
        }

        val coordinator = RegistrationCoordinator(authGateway, firestoreGateway)
        val result = coordinator.registerUser("user@example.com", "password123", "budi_test", "Budi Test")

        assertTrue("Registrasi wajib gagal (fail-closed) saat Firestore precheck melempar exception", result.isFailure)
        assertFalse("createAccount DILARANG dipanggil saat Firestore exception terjadi", authGateway.createAccountCalled)

        val ex = result.exceptionOrNull()
        assertTrue("Exception harus berupa UsernamePrecheckFailedException", ex is UsernamePrecheckFailedException)
        val original = (ex as UsernamePrecheckFailedException).originalError
        assertTrue("Original error harus berupa BackendFirestoreException", original is BackendFirestoreException)
        assertEquals("PERMISSION_DENIED", (original as BackendFirestoreException).code)
    }

    @Test
    fun testPrecheckAvailableButCollisionInTransactionRollsBackAndFailsWithTypedError() = runBlocking {
        // [Kriteria C]: pre-check mengatakan username tersedia -> Auth dibuat -> transaction tetap mengecek username lagi -> jika collision muncul di transaction, UsernameAlreadyTakenException tetap menang
        val authGateway = SpyAuthGateway()
        val firestoreGateway = ControllableFirestoreGateway().apply {
            precheckReturnsExists = false // Pre-check bilang tersedia
            transactionThrowsCollision = true // Tetapi saat transaksi, terjadi collision race
        }

        val coordinator = RegistrationCoordinator(authGateway, firestoreGateway)
        val result = coordinator.registerUser("user@example.com", "password123", "race_user", "Race User")

        assertTrue("createAccount sempat dipanggil karena precheck lolos", authGateway.createAccountCalled)
        assertTrue("Transaksi Firestore dijalankan", firestoreGateway.transactionExecuted)
        assertTrue("Rollback deleteAccount dipanggil untuk membersihkan akun Auth", authGateway.deleteAccountCalled)

        assertTrue("Hasil registrasi harus failure", result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue("UsernameAlreadyTakenException dari transaction harus tetap menang", ex is UsernameAlreadyTakenException)
        assertEquals("race_user", (ex as UsernameAlreadyTakenException).username)
    }

    @Test
    fun testPrecheckFindsUsernameTakenAbortsBeforeAuthCreation() = runBlocking {
        // [Kriteria D]: pre-check menemukan username sudah ada -> Auth tidak dibuat
        val authGateway = SpyAuthGateway()
        val firestoreGateway = ControllableFirestoreGateway().apply {
            precheckReturnsExists = true // Username sudah terdaftar
        }

        val coordinator = RegistrationCoordinator(authGateway, firestoreGateway)
        val result = coordinator.registerUser("user@example.com", "password123", "existing_user", "Existing User")

        assertTrue("Registrasi harus gagal", result.isFailure)
        assertFalse("createAccount TIDAK BOLEH dipanggil jika pre-check mendeteksi username ada", authGateway.createAccountCalled)
        assertFalse("Transaksi tidak perlu dijalankan jika pre-check sudah menolak", firestoreGateway.transactionExecuted)

        val ex = result.exceptionOrNull()
        assertTrue("Error harus UsernameAlreadyTakenException", ex is UsernameAlreadyTakenException)
        assertEquals("existing_user", (ex as UsernameAlreadyTakenException).username)
    }

    @Test
    fun testTypedErrorPreservedUpToRepositoryBoundary() = runBlocking {
        // [Kriteria E]: typed error tetap dipertahankan sampai repository boundary
        val authGateway = SpyAuthGateway()
        val firestoreGateway = ControllableFirestoreGateway().apply {
            precheckException = IOException("Connection reset by peer")
        }

        val coordinator = RegistrationCoordinator(authGateway, firestoreGateway)
        val result = coordinator.registerUser("user@example.com", "password123", "test_user", "Test User")

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("Error harus tetap typed sebagai UsernamePrecheckFailedException pada repository boundary", error is UsernamePrecheckFailedException)

        // Verifikasi formatting UI Bahasa Indonesia dilakukan terpisah di layer presentation
        val indonesianMessage = ChatrokError.formatAuthError(error)
        assertEquals("Gagal memeriksa ketersediaan username karena kendala jaringan atau server. Silakan coba lagi.", indonesianMessage)
    }
}
