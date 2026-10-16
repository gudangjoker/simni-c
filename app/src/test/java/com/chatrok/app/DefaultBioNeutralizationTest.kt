package com.chatrok.app

import com.chatrok.app.core.model.DEFAULT_USER_BIO
import com.chatrok.app.core.model.User
import com.chatrok.app.data.auth.AuthAccount
import com.chatrok.app.data.auth.DefaultRegistrationFirestoreGateway
import com.chatrok.app.data.auth.RegistrationAuthGateway
import com.chatrok.app.data.auth.RegistrationCoordinator
import com.chatrok.app.data.auth.RegistrationFirestoreGateway
import com.chatrok.app.data.repository.FakeAuthRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BioTestAuthGateway : RegistrationAuthGateway {
    var createdAccount: AuthAccount? = null

    override suspend fun createAccount(email: String, pass: String): AuthAccount {
        val account = AuthAccount(uid = "uid_bio_test_01", email = email)
        createdAccount = account
        return account
    }

    override suspend fun deleteAccount(targetUid: String, currentSessionUid: String?) {
        // no-op for success flow
    }
}

class BioTestFirestoreGateway : RegistrationFirestoreGateway {
    var capturedUid: String? = null
    var capturedEmail: String? = null
    var capturedUsernameLower: String? = null
    var capturedDisplayName: String? = null

    override suspend fun checkUsernameExists(usernameLower: String): Boolean = false

    override suspend fun executeRegistrationTransaction(
        uid: String,
        email: String,
        usernameLower: String,
        displayName: String
    ) {
        capturedUid = uid
        capturedEmail = email
        capturedUsernameLower = usernameLower
        capturedDisplayName = displayName
    }
}

class DefaultBioNeutralizationTest {

    @Test
    fun testDefaultUserBioConstantValueIsPenggunaSimniC() {
        // Verifikasi nilai konstanta domain terpusat
        assertEquals("Pengguna SIMNI-C", DEFAULT_USER_BIO)
    }

    @Test
    fun testUserEntityDefaultBioIsPenggunaSimniC() {
        // User entity tanpa parameter bio eksplisit wajib default ke "Pengguna SIMNI-C"
        val user = User(
            id = "uid_test_default",
            username = "default_user",
            displayName = "Default User"
        )
        assertEquals("Pengguna SIMNI-C", user.bio)
    }

    @Test
    fun testRegistrationCoordinatorProducesUserWithNeutralBio() = runBlocking {
        // [Kriteria A]: User baru hasil registrasi RegistrationCoordinator wajib memiliki bio "Pengguna SIMNI-C"
        val authGateway = BioTestAuthGateway()
        val firestoreGateway = BioTestFirestoreGateway()
        val coordinator = RegistrationCoordinator(authGateway, firestoreGateway)

        val result = coordinator.registerUser(
            email = "citra@simni.id",
            password = "SecurePassword123",
            username = "citra_simni",
            displayName = "Citra Lestari"
        )

        assertTrue("Registrasi harus sukses", result.isSuccess)
        val registeredUser = result.getOrThrow()
        assertEquals("Pengguna SIMNI-C", registeredUser.bio)
        assertEquals(DEFAULT_USER_BIO, registeredUser.bio)
        assertEquals("citra_simni", registeredUser.username)
        assertEquals("Citra Lestari", registeredUser.displayName)
    }

    @Test
    fun testFakeAuthRepositoryProducesUserWithNeutralBio() = runBlocking {
        // [Kriteria A2]: FakeAuthRepository juga disinkronkan ke "Pengguna SIMNI-C"
        val fakeRepo = FakeAuthRepository()
        val result = fakeRepo.register(
            email = "dewi@simni.id",
            password = "SecretPassword456",
            username = "dewi_simni",
            displayName = "Dewi Anggraini"
        )

        assertTrue("FakeAuthRepository register harus sukses", result.isSuccess)
        val user = result.getOrThrow()
        assertEquals("Pengguna SIMNI-C", user.bio)
        assertEquals(DEFAULT_USER_BIO, user.bio)
    }

    @Test
    fun testPublicProfilesPayloadContainsNeutralBio() {
        // [Kriteria B]: publicProfiles payload wajib memuat bio "Pengguna SIMNI-C"
        val fixedTimestamp = 1716000000000L
        val payload = DefaultRegistrationFirestoreGateway.buildPublicProfilePayload(
            uid = "uid_citra_01",
            usernameLower = "citra_simni",
            displayName = "Citra Lestari",
            timestamp = fixedTimestamp
        )

        assertTrue("publicProfiles wajib memiliki field bio", payload.containsKey("bio"))
        assertEquals("Pengguna SIMNI-C", payload["bio"])
        assertEquals(DEFAULT_USER_BIO, payload["bio"])
        assertEquals("citra_simni", payload["usernameLower"])
        assertEquals("Citra Lestari", payload["displayName"])
        assertEquals("", payload["avatarUrl"])
        assertEquals(fixedTimestamp, payload["createdAt"])
        assertEquals(fixedTimestamp, payload["updatedAt"])
    }

    @Test
    fun testUsersDocumentPayloadDoesNotContainBioField() {
        // [Kriteria C]: Dokumen users/{uid} (private) TETAP TIDAK BOLEH memuat field bio
        val fixedTimestamp = 1716000000000L
        val payload = DefaultRegistrationFirestoreGateway.buildUserPayload(
            uid = "uid_citra_01",
            email = "citra@simni.id",
            usernameLower = "citra_simni",
            displayName = "Citra Lestari",
            timestamp = fixedTimestamp
        )

        assertFalse(
            "users/{uid} dilarang keras menyimpan field bio",
            payload.containsKey("bio")
        )
        // Pastikan field private yang diizinkan tetap ada
        assertEquals("uid_citra_01", payload["uid"])
        assertEquals("citra@simni.id", payload["email"])
        assertEquals("citra_simni", payload["username"])
        assertEquals("citra_simni", payload["usernameLower"])
        assertEquals("Citra Lestari", payload["displayName"])
    }

    @Test
    fun testOldDefaultBioStringIsZeroInProductionKotlinSource() {
        // [Kriteria D]: String default lama tidak boleh ada lagi di source code production
        val oldString = "Pengguna internal CHATROK messenger terenkripsi."
        val sourceDirs = listOf(
            File("src/main/java")
        )

        var foundOccurrences = 0
        val matchedFiles = mutableListOf<String>()

        sourceDirs.forEach { dir ->
            if (dir.exists()) {
                dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                    val content = file.readText()
                    if (content.contains(oldString)) {
                        foundOccurrences++
                        matchedFiles.add(file.path)
                    }
                }
            }
        }

        assertEquals(
            "String default bio lama masih ditemukan pada source code production: $matchedFiles",
            0,
            foundOccurrences
        )
    }
}
