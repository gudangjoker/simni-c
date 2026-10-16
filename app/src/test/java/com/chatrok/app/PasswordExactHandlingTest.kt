package com.chatrok.app

import com.chatrok.app.data.auth.AuthAccount
import com.chatrok.app.data.auth.RegistrationAuthGateway
import com.chatrok.app.data.auth.RegistrationCoordinator
import com.chatrok.app.data.auth.RegistrationFirestoreGateway
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturingAuthGateway : RegistrationAuthGateway {
    var capturedEmail: String? = null
    var capturedPassword: String? = null
    var createAccountCalled = false

    override suspend fun createAccount(email: String, pass: String): AuthAccount {
        createAccountCalled = true
        capturedEmail = email
        capturedPassword = pass
        return AuthAccount(uid = "uid_test_123", email = email)
    }

    override suspend fun deleteAccount(targetUid: String, currentSessionUid: String?) {
        // no-op
    }
}

class NoOpFirestoreGateway : RegistrationFirestoreGateway {
    override suspend fun checkUsernameExists(usernameLower: String): Boolean = false
    override suspend fun executeRegistrationTransaction(
        uid: String,
        email: String,
        usernameLower: String,
        displayName: String
    ) {
        // no-op
    }
}

class PasswordExactHandlingTest {

    @Test
    fun testRegistrationPreservesPasswordWithLeadingAndTrailingSpaces() = runBlocking {
        // [Kriteria A]: Input password " rahasia123 " harus sampai ke RegistrationAuthGateway PERSIS sama
        val authGateway = CapturingAuthGateway()
        val firestoreGateway = NoOpFirestoreGateway()
        val coordinator = RegistrationCoordinator(authGateway, firestoreGateway)

        val rawPasswordWithSpaces = " rahasia123 "
        val result = coordinator.registerUser(
            email = "user@test.com",
            password = rawPasswordWithSpaces,
            username = "user_test",
            displayName = "User Test"
        )

        assertTrue("Registrasi harus sukses", result.isSuccess)
        assertTrue("createAccount harus dipanggil", authGateway.createAccountCalled)
        assertEquals(
            "Password harus diteruskan ke AuthGateway persis sama tanpa trim",
            rawPasswordWithSpaces,
            authGateway.capturedPassword
        )
        assertEquals(12, authGateway.capturedPassword?.length)
    }

    @Test
    fun testPasswordValidationUsesExactLengthWithoutTrim() = runBlocking {
        // [Kriteria C]: Validasi panjang menggunakan password asli, bukan hasil trim.
        // Password " ab12 " memiliki panjang 6 (1 spasi + 4 karakter + 1 spasi).
        // Jika di-trim, panjangnya menjadi 4 (< 6) dan akan ditolak.
        // Karena diperlakukan sebagai opaque secret tanpa trim, panjangnya adalah 6 dan lolos validasi.
        val authGateway = CapturingAuthGateway()
        val firestoreGateway = NoOpFirestoreGateway()
        val coordinator = RegistrationCoordinator(authGateway, firestoreGateway)

        val passwordOfExactLengthSix = " ab12 "
        val result = coordinator.registerUser(
            email = "user@test.com",
            password = passwordOfExactLengthSix,
            username = "user_six",
            displayName = "User Six"
        )

        assertTrue("Password dengan panjang asli 6 karakter termasuk spasi harus lolos", result.isSuccess)
        assertEquals(passwordOfExactLengthSix, authGateway.capturedPassword)
    }

    @Test
    fun testPasswordShorterThanSixCharactersFailsValidation() = runBlocking {
        // Password " ab1 " memiliki panjang asli 5 (1 spasi + 3 karakter + 1 spasi).
        // Harus ditolak karena password.length < 6.
        val authGateway = CapturingAuthGateway()
        val firestoreGateway = NoOpFirestoreGateway()
        val coordinator = RegistrationCoordinator(authGateway, firestoreGateway)

        val shortPassword = " ab1 "
        val result = coordinator.registerUser(
            email = "user@test.com",
            password = shortPassword,
            username = "user_short",
            displayName = "User Short"
        )

        assertTrue("Password di bawah 6 karakter harus gagal", result.isFailure)
        assertFalse("createAccount tidak boleh dipanggil jika validasi password gagal", authGateway.createAccountCalled)
        val ex = result.exceptionOrNull()
        assertTrue("Exception harus IllegalArgumentException", ex is IllegalArgumentException)
        assertEquals("auth/weak-password", ex?.message)
    }

    @Test
    fun testEmptyPasswordFailsWithRequiredFieldMessage() = runBlocking {
        val authGateway = CapturingAuthGateway()
        val firestoreGateway = NoOpFirestoreGateway()
        val coordinator = RegistrationCoordinator(authGateway, firestoreGateway)

        val result = coordinator.registerUser(
            email = "user@test.com",
            password = "",
            username = "user_empty",
            displayName = "User Empty"
        )

        assertTrue("Password kosong harus gagal", result.isFailure)
        assertFalse("createAccount tidak boleh dipanggil jika password kosong", authGateway.createAccountCalled)
        val ex = result.exceptionOrNull()
        assertEquals("Semua kolom wajib diisi.", ex?.message)
    }

    @Test
    fun testEmailAndDisplayNameAreTrimmedWhilePasswordIsNot() = runBlocking {
        // [Kriteria 4, 5, 6]: Email, username, displayName boleh di-trim/normalize, tetapi password TIDAK
        val authGateway = CapturingAuthGateway()
        val firestoreGateway = NoOpFirestoreGateway()
        val coordinator = RegistrationCoordinator(authGateway, firestoreGateway)

        val inputEmail = "   user@test.com   "
        val inputPassword = " secret pass with spaces "
        val inputUsername = "  User_Test  "
        val inputDisplayName = "  Test User Display  "

        val result = coordinator.registerUser(
            email = inputEmail,
            password = inputPassword,
            username = inputUsername,
            displayName = inputDisplayName
        )

        assertTrue("Registrasi harus sukses", result.isSuccess)
        // Email di authGateway harus sudah di-trim
        assertEquals("user@test.com", authGateway.capturedEmail)
        // Password di authGateway TIDAK BOLEH di-trim
        assertEquals(inputPassword, authGateway.capturedPassword)
    }
}
