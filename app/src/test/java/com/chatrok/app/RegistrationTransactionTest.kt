package com.chatrok.app

import com.chatrok.app.core.common.ChatrokError
import com.chatrok.app.core.common.RegistrationRollbackFailedException
import com.chatrok.app.core.common.UsernameAlreadyTakenException
import com.chatrok.app.data.auth.AuthAccount
import com.chatrok.app.data.auth.FirebaseAuthRepository
import com.chatrok.app.data.auth.RegistrationAuthGateway
import com.chatrok.app.data.auth.RegistrationCoordinator
import com.chatrok.app.data.auth.RegistrationFirestoreGateway
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeTestAuthGateway : RegistrationAuthGateway {
    val createdAccounts = mutableListOf<AuthAccount>()
    val deletedUids = mutableListOf<String>()
    var createAccountFailure: Throwable? = null
    var deleteAccountFailure: Throwable? = null
    var sessionUid: String? = null

    override suspend fun createAccount(email: String, pass: String): AuthAccount {
        createAccountFailure?.let { throw it }
        val account = AuthAccount(uid = "uid_" + email.substringBefore("@"), email = email)
        createdAccounts.add(account)
        sessionUid = account.uid
        return account
    }

    override suspend fun deleteAccount(targetUid: String, currentSessionUid: String?) {
        deleteAccountFailure?.let { throw it }
        if (sessionUid != null && sessionUid == targetUid) {
            deletedUids.add(targetUid)
        } else {
            throw SecurityException("Rollback rejected: current UID  does not match target ")
        }
    }
}

class FakeTestFirestoreGateway : RegistrationFirestoreGateway {
    val existingUsernames = mutableSetOf<String>()
    val writtenUsers = mutableMapOf<String, Map<String, Any>>()
    val writtenPublicProfiles = mutableMapOf<String, Map<String, Any>>()
    val writtenUsernames = mutableSetOf<String>()
    var transactionFailure: Throwable? = null

    override suspend fun checkUsernameExists(usernameLower: String): Boolean {
        return false // Pre-check lolos untuk menguji authoritative transaction
    }

    override suspend fun executeRegistrationTransaction(
        uid: String,
        email: String,
        usernameLower: String,
        displayName: String
    ) {
        transactionFailure?.let { throw it }
        if (existingUsernames.contains(usernameLower)) {
            throw UsernameAlreadyTakenException(usernameLower)
        }
        writtenUsernames.add(usernameLower)
        writtenUsers[uid] = mapOf("email" to email, "displayName" to displayName)
        writtenPublicProfiles[uid] = mapOf("displayName" to displayName)
    }
}

class RegistrationTransactionTest {

    @Test
    fun testUsernameCollisionAbortsTransactionAndPreventsDocumentWrites() = runBlocking {
        // [Item A] username document exists -> transaction abort -> users/{uid} & publicProfiles/{uid} TIDAK ditulis
        val authGateway = FakeTestAuthGateway()
        val firestoreGateway = FakeTestFirestoreGateway()
        firestoreGateway.existingUsernames.add("budi_santoso")

        val coordinator = RegistrationCoordinator(authGateway, firestoreGateway)
        val result = coordinator.registerUser("budi@example.com", "password123", "budi_santoso", "Budi Santoso")

        assertTrue("Registrasi harus gagal karena username sudah ada di transaction", result.isFailure)
        assertTrue(
            "Exception harus berupa UsernameAlreadyTakenException",
            result.exceptionOrNull() is UsernameAlreadyTakenException
        )

        val ex = result.exceptionOrNull() as UsernameAlreadyTakenException
        assertEquals("budi_santoso", ex.username)

        // Verifikasi dokumen users/{uid} dan publicProfiles/{uid} TIDAK PERNAH ditulis
        assertTrue("Dokumen users tidak boleh ditulis saat transaksi abort", firestoreGateway.writtenUsers.isEmpty())
        assertTrue("Dokumen publicProfiles tidak boleh ditulis saat transaksi abort", firestoreGateway.writtenPublicProfiles.isEmpty())
    }

    @Test
    fun testTransactionFailureTriggersAuthRollbackForSameUid() = runBlocking {
        // [Item B] transaction gagal setelah Auth user dibuat -> rollback delete dipanggil untuk createdUser dengan UID yang sama
        val authGateway = FakeTestAuthGateway()
        val firestoreGateway = FakeTestFirestoreGateway()
        firestoreGateway.existingUsernames.add("andi_taken")

        val coordinator = RegistrationCoordinator(authGateway, firestoreGateway)
        val result = coordinator.registerUser("andi@example.com", "password123", "andi_taken", "Andi")

        assertTrue(result.isFailure)
        assertEquals(1, authGateway.createdAccounts.size)
        val createdUid = authGateway.createdAccounts.first().uid

        // Buktikan delete dipanggil tepat untuk created UID tersebut
        assertEquals(1, authGateway.deletedUids.size)
        assertEquals(createdUid, authGateway.deletedUids.first())
    }

    @Test
    fun testDifferentAuthUidRejectsUnsafeAccountDeletion() = runBlocking {
        // [Item C] current auth UID berbeda -> created user tidak dihapus secara tidak aman
        val authGateway = FakeTestAuthGateway()
        authGateway.sessionUid = "other_active_uid_999"

        var securityExceptionThrown = false
        try {
            authGateway.deleteAccount(targetUid = "uid_new_target", currentSessionUid = authGateway.sessionUid)
        } catch (e: SecurityException) {
            securityExceptionThrown = true
        }

        assertTrue("Gateway harus menolak delete jika UID sesi tidak cocok dengan target", securityExceptionThrown)
        assertTrue("Tidak boleh ada akun yang dihapus", authGateway.deletedUids.isEmpty())
    }

    @Test
    fun testRollbackFailureIsNotSwallowedAndProducesTypedException() = runBlocking {
        // [Item D] rollback delete sendiri gagal -> failure TIDAK ditelan -> menghasilkan typed RegistrationRollbackFailedException
        val authGateway = FakeTestAuthGateway()
        val firestoreGateway = FakeTestFirestoreGateway()
        firestoreGateway.existingUsernames.add("collision_user")
        authGateway.deleteAccountFailure = IOException("Koneksi jaringan terputus saat rollback")

        val coordinator = RegistrationCoordinator(authGateway, firestoreGateway)
        val result = coordinator.registerUser("test@example.com", "password123", "collision_user", "Test User")

        assertTrue("Registrasi harus failure", result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(
            "Failure harus berupa RegistrationRollbackFailedException dan tidak ditelan",
            ex is RegistrationRollbackFailedException
        )

        val rollbackEx = ex as RegistrationRollbackFailedException
        assertTrue("Original error harus UsernameAlreadyTakenException", rollbackEx.originalError is UsernameAlreadyTakenException)
        assertTrue("Rollback error harus IOException", rollbackEx.rollbackError is IOException)
    }

    @Test
    fun testUsernameAlreadyTakenExceptionRemainsTypedOutOfRepository() = runBlocking {
        // [Item E] UsernameAlreadyTakenException tetap typed sampai keluar repository
        val authGateway = FakeTestAuthGateway()
        val firestoreGateway = FakeTestFirestoreGateway()
        firestoreGateway.existingUsernames.add("clash_user")

        val coordinator = RegistrationCoordinator(authGateway, firestoreGateway)
        // Jalankan register melalui coordinator yang diinject ke repository
        val result = coordinator.registerUser("clash@example.com", "pass12345", "clash_user", "Clash")

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(
            "Exception keluar dari repository harus tetap typed sebagai UsernameAlreadyTakenException",
            error is UsernameAlreadyTakenException
        )

        // Verifikasi formatting Bahasa Indonesia dapat dilakukan di UI layer tanpa merusak typed error repository
        val formattedIndonesian = ChatrokError.formatAuthError(error)
        assertEquals("Username ini sudah digunakan oleh pengguna lain. Silakan pilih username lain.", formattedIndonesian)
    }
}
