package com.chatrok.app

import com.chatrok.app.core.di.FakeRepositoryModule
import com.chatrok.app.core.di.RealRepositoryModule
import com.chatrok.app.core.push.PushConstants
import com.chatrok.app.core.push.PushRouteAction
import com.chatrok.app.data.messaging.AuthenticatedUidProvider
import com.chatrok.app.data.push.FirestorePushTokenRepository
import com.chatrok.app.data.push.PushTokenGateway
import com.chatrok.app.data.repository.FakePushTokenRepository
import com.chatrok.app.domain.repository.PushTokenRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest

class Milestone6PushAndTokenTest {

    @Test
    fun testPushTokenSha256Hashing() {
        val rawToken = "fcm_device_token_sample_test_1234567890"
        val hashed = PushConstants.hashPushToken(rawToken)

        // Manual SHA-256 calculation
        val digest = MessageDigest.getInstance("SHA-256")
        val expected = digest.digest(rawToken.toByteArray()).joinToString("") { "%02x".format(it) }

        assertEquals("SHA-256 token hash harus sesuai standar kriptografi", expected, hashed)
        assertEquals("Panjang hash SHA-256 harus 64 karakter hex", 64, hashed.length)
    }

    @Test
    fun testPushTokenSha256BlankValidation() {
        try {
            PushConstants.hashPushToken("   ")
            fail("Hash token harus menolak input kosong")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("tidak boleh kosong") == true)
        }
    }

    @Test
    fun testBuildTokenDocPayload() {
        val uid = "user_abc_123"
        val token = "fcm_token_valid_sample_length_over_20_chars"
        val payload = PushConstants.buildTokenDocPayload(uid, token, "android")

        assertEquals(uid, payload["uid"])
        assertEquals(token, payload["token"])
        assertEquals(PushConstants.hashPushToken(token), payload["tokenHash"])
        assertEquals("android", payload["platform"])
        assertEquals(true, payload["active"])
    }

    @Test
    fun testBuildTokenDocPayloadRejectsInvalidTokens() {
        try {
            PushConstants.buildTokenDocPayload("uid", "short_tok")
            fail("Harus menolak token kurang dari 21 karakter")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("tidak valid") == true)
        }
    }

    @Test
    fun testStrictNotificationCloakingConstants() {
        // Kontrak invariant privasi SIMNI-C:
        // Pesan chat wajib disamarkan menjadi teks administrasi kelas
        assertEquals("SIMNI ADMINISTRASI KELAS", PushConstants.CLOAKED_TITLE)
        assertEquals("Database telah siap, segera lakukan backup", PushConstants.CLOAKED_BODY)
        assertEquals("chatrok-message-activity", PushConstants.CHANNEL_MESSAGE_ACTIVITY)
        assertEquals("chatrok-calls", PushConstants.CHANNEL_CALLS)
    }

    @Test
    fun testAssertZeroSensitivePayloadPassesCleanPayload() {
        val cleanPayload = mapOf(
            "notification" to mapOf(
                "title" to PushConstants.CLOAKED_TITLE,
                "body" to PushConstants.CLOAKED_BODY
            ),
            "data" to mapOf(
                "kind" to "chatrok-activity",
                "type" to PushConstants.EVENT_MESSAGE_ACTIVITY,
                "conversationId" to "conv_xyz789"
            )
        )

        // Tidak boleh melempar exception
        PushConstants.assertZeroSensitivePayload(cleanPayload)
    }

    @Test
    fun testAssertZeroSensitivePayloadRejectsSensitiveFields() {
        val leakyKeys = listOf("message", "bodyText", "plaintext", "preview", "caption", "email")

        for (leaky in leakyKeys) {
            val leakyPayload = mapOf(
                "notification" to mapOf(
                    "title" to PushConstants.CLOAKED_TITLE,
                    "body" to PushConstants.CLOAKED_BODY
                ),
                "data" to mapOf(
                    leaky to "Bocoran rahasia teks pesan"
                )
            )

            try {
                PushConstants.assertZeroSensitivePayload(leakyPayload)
                fail("Payload harus ditolak karena memuat field sensitif: $leaky")
            } catch (e: IllegalStateException) {
                assertTrue("Pesan error harus mendeteksi field terlarang", e.message?.contains("terlarang") == true)
            }
        }
    }

    @Test
    fun testAssertZeroSensitivePayloadRejectsEmailAddress() {
        val emailLeakPayload = mapOf(
            "notification" to mapOf(
                "title" to PushConstants.CLOAKED_TITLE,
                "body" to PushConstants.CLOAKED_BODY
            ),
            "data" to mapOf(
                "info" to "Pengirim: user.admin@sekolah.sch.id"
            )
        )

        try {
            PushConstants.assertZeroSensitivePayload(emailLeakPayload)
            fail("Payload harus ditolak jika mengandung format email")
        } catch (e: IllegalStateException) {
            assertTrue("Pesan error harus mendeteksi format email", e.message?.contains("email") == true)
        }
    }

    @Test
    fun testSanitizeCallerNameStripsEmailAddresses() {
        assertEquals("Budi Santoso", PushConstants.sanitizeCallerName("Budi Santoso"))
        assertEquals(PushConstants.CALL_TITLE_FALLBACK, PushConstants.sanitizeCallerName("budi@gmail.com"))
        assertEquals(PushConstants.CALL_TITLE_FALLBACK, PushConstants.sanitizeCallerName("user@domain.co.id"))
        assertEquals(PushConstants.CALL_TITLE_FALLBACK, PushConstants.sanitizeCallerName("   "))
        assertEquals(PushConstants.CALL_TITLE_FALLBACK, PushConstants.sanitizeCallerName(null))
    }

    @Test
    fun testParseNotificationRouting() {
        // Chat conversation routing
        val chatData = mapOf(
            "type" to PushConstants.EVENT_MESSAGE_ACTIVITY,
            "conversationId" to "conv_abc_999"
        )
        val route1 = PushConstants.parseNotificationRouting(chatData)
        assertTrue(route1 is PushRouteAction.OpenConversation)
        assertEquals("conv_abc_999", (route1 as PushRouteAction.OpenConversation).conversationId)

        // Inbox fallback routing
        val inboxData = mapOf(
            "type" to PushConstants.EVENT_MESSAGE_ACTIVITY
        )
        val route2 = PushConstants.parseNotificationRouting(inboxData)
        assertTrue(route2 is PushRouteAction.OpenInbox)

        // Call routing parsed safely
        val callData = mapOf(
            "type" to PushConstants.EVENT_VOICE_CALL,
            "callerUid" to "user_caller_123"
        )
        val route3 = PushConstants.parseNotificationRouting(callData)
        assertTrue(route3 is PushRouteAction.IncomingCall)
        assertEquals("user_caller_123", (route3 as PushRouteAction.IncomingCall).callerUid)
        assertEquals("voice", (route3 as PushRouteAction.IncomingCall).callType)
    }

    @Test
    fun testFirestorePushTokenRepositoryFlow() = runTest {
        val savedTokens = mutableMapOf<String, Map<String, Any>>()
        val deactivatedTokens = mutableSetOf<String>()
        var deactivatedAllUid: String? = null

        val fakeGateway = object : PushTokenGateway {
            override suspend fun saveToken(uid: String, tokenId: String, payload: Map<String, Any>) {
                savedTokens["$uid/$tokenId"] = payload
            }

            override suspend fun deactivateToken(uid: String, tokenId: String) {
                deactivatedTokens.add("$uid/$tokenId")
            }

            override suspend fun deactivateAllTokens(uid: String) {
                deactivatedAllUid = uid
            }
        }

        val testUid = "user_test_456"
        val uidProvider = AuthenticatedUidProvider { testUid }
        val repo = FirestorePushTokenRepository(fakeGateway, uidProvider)

        // 1. Register Token
        val sampleToken = "fcm_valid_sample_device_token_xyz987654321"
        val regResult = repo.registerCurrentToken(sampleToken)
        assertTrue("Registrasi token harus berhasil", regResult.isSuccess)

        val tokenId = PushConstants.hashPushToken(sampleToken)
        val saved = savedTokens["$testUid/$tokenId"]
        assertNotNull("Token harus tersimpan di gateway", saved)
        assertEquals(testUid, saved?.get("uid"))
        assertEquals(sampleToken, saved?.get("token"))
        assertEquals(true, saved?.get("active"))

        // 2. Unregister Token
        val unregResult = repo.unregisterToken(sampleToken)
        assertTrue("Unregister token harus berhasil", unregResult.isSuccess)
        assertTrue(deactivatedTokens.contains("$testUid/$tokenId"))

        // 3. Deactivate User Tokens (saat logout)
        val deactResult = repo.deactivateUserTokens(testUid)
        assertTrue("Deactivate all harus berhasil", deactResult.isSuccess)
        assertEquals(testUid, deactivatedAllUid)
    }

    @Test
    fun testPushTokenRepositoryModuleBindings() {
        val fakeRepo = FakeRepositoryModule.provideFakePushTokenRepository()
        val boundFake: PushTokenRepository = FakeRepositoryModule.bindPushTokenRepository(fakeRepo)
        assertNotNull(boundFake)
        assertTrue(boundFake is FakePushTokenRepository)

        val mockGateway = object : PushTokenGateway {
            override suspend fun saveToken(uid: String, tokenId: String, payload: Map<String, Any>) {}
            override suspend fun deactivateToken(uid: String, tokenId: String) {}
            override suspend fun deactivateAllTokens(uid: String) {}
        }
        val realRepo = FirestorePushTokenRepository(mockGateway, AuthenticatedUidProvider { "uid" })
        val boundReal: PushTokenRepository = RealRepositoryModule.bindPushTokenRepository(realRepo)
        assertNotNull(boundReal)
        assertTrue(boundReal is FirestorePushTokenRepository)
    }
}
