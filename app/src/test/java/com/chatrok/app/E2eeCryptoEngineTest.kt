package com.chatrok.app

import com.chatrok.app.core.crypto.E2eeCryptoEngine
import com.chatrok.app.core.crypto.EncryptedMessagePayload
import com.chatrok.app.core.crypto.JwkKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class E2eeCryptoEngineTest {

    private lateinit var cryptoEngine: E2eeCryptoEngine

    @Before
    fun setup() {
        cryptoEngine = E2eeCryptoEngine()
    }

    @Test
    fun testKeyPairGenerationAndFingerprint() {
        val (priv, pub) = cryptoEngine.generateKeyPair()

        assertEquals("EC", pub.kty)
        assertEquals("P-256", pub.crv)
        assertNotNull(pub.x)
        assertNotNull(pub.y)
        assertNotNull(priv.d)

        val fp = cryptoEngine.fingerprintJwk(pub)
        assertTrue(fp.isNotEmpty())
        // Fingerprint should be Base64 of SHA-256 (approx 44 chars)
        assertTrue(fp.length in 40..50)
    }

    @Test
    fun testEncryptionDecryptionRoundtrip() {
        val (alicePriv, alicePub) = cryptoEngine.generateKeyPair()
        val (bobPriv, bobPub) = cryptoEngine.generateKeyPair()

        val plainText = "Halo Bob, ini pesan terenkripsi E2EE rahasia!"
        val context = "conversation:conv_123|users:alice:bob"
        val aad = "conversation:conv_123"

        // Alice encrypts for Bob
        val encrypted = cryptoEngine.encryptText(plainText, alicePriv, bobPub, context, aad)
        assertEquals(1, encrypted.cryptoVersion)
        assertTrue(encrypted.ciphertext.isNotEmpty())
        assertTrue(encrypted.iv.isNotEmpty())
        assertEquals(aad, encrypted.aad)

        // Bob decrypts Alice's message
        val decrypted = cryptoEngine.decryptText(encrypted, bobPriv, alicePub, context)
        assertEquals(plainText, decrypted)

        // Bob replies to Alice
        val replyText = "Halo Alice, pesan diterima dengan selamat!"
        val replyEncrypted = cryptoEngine.encryptText(replyText, bobPriv, alicePub, context, aad)
        val replyDecrypted = cryptoEngine.decryptText(replyEncrypted, alicePriv, bobPub, context)
        assertEquals(replyText, replyDecrypted)
    }

    @Test
    fun testDecryptionFailsWithWrongKeyOrContext() {
        val (alicePriv, alicePub) = cryptoEngine.generateKeyPair()
        val (bobPriv, bobPub) = cryptoEngine.generateKeyPair()
        val (evePriv, _) = cryptoEngine.generateKeyPair()

        val plainText = "Pesan rahasia"
        val context = "conversation:conv_123|users:alice:bob"
        val encrypted = cryptoEngine.encryptText(plainText, alicePriv, bobPub, context)

        // Eve cannot decrypt with her private key
        var failedAsExpected = false
        try {
            cryptoEngine.decryptText(encrypted, evePriv, alicePub, context)
        } catch (_: Exception) {
            failedAsExpected = true
        }
        assertTrue("Eve tidak boleh dapat mendekripsi pesan", failedAsExpected)

        // Wrong context fails
        var contextMismatchFailed = false
        try {
            cryptoEngine.decryptText(encrypted, bobPriv, alicePub, "different_context")
        } catch (_: Exception) {
            contextMismatchFailed = true
        }
        assertTrue("Dekripsi dengan konteks berbeda harus gagal", contextMismatchFailed)
    }

    @Test
    fun testJwkJsonSerializationRoundtrip() {
        val (priv, pub) = cryptoEngine.generateKeyPair()
        val pubJson = pub.toJson()
        val restoredPub = JwkKey.fromJson(pubJson)
        assertEquals(pub.crv, restoredPub.crv)
        assertEquals(pub.kty, restoredPub.kty)
        assertEquals(pub.x, restoredPub.x)
        assertEquals(pub.y, restoredPub.y)

        val privJson = priv.toJson()
        val restoredPriv = JwkKey.fromJson(privJson)
        assertEquals(priv.d, restoredPriv.d)
    }

    @Test
    fun testCreateRecoveryPhrase12Words() {
        val phrase = cryptoEngine.createRecoveryPhrase()
        val words = phrase.split(" ")
        assertEquals(12, words.size)
        words.forEach { word ->
            assertTrue(word.length in 3..6)
        }
    }

    @Test
    fun testKnownPwaVectorsCompatibility() {
        // Deterministic vector generated from WebCrypto/PWA in Node
        val alicePub = JwkKey(
            kty = "EC",
            crv = "P-256",
            x = "O2q1qJTo_YCIciGekKgE4cRRZ0Uz-n8quw_7LaFaWfA",
            y = "yz-UflxYKJu5NH6ZVD69TCGHbGlMld5t7gLaX5UBsvI"
        )
        val alicePriv = JwkKey(
            kty = "EC",
            crv = "P-256",
            x = "O2q1qJTo_YCIciGekKgE4cRRZ0Uz-n8quw_7LaFaWfA",
            y = "yz-UflxYKJu5NH6ZVD69TCGHbGlMld5t7gLaX5UBsvI",
            d = "ChVk2klxq_K-V2OCPK9x9NRUMbJbVcPqQu_YtOPSdJQ"
        )
        val payload = EncryptedMessagePayload(
            cryptoVersion = 1,
            algorithm = "ECDH-P256+HKDF-SHA256+AES-256-GCM",
            iv = "QOZLegji3niVqmp7",
            ciphertext = "C+aEbv6ekmJNmz1h8nEnbslMFO8fsjX5b6I=",
            aad = "aad1"
        )
        // In this vector, peer is alice herself with context "c1" and aad "aad1"
        val decrypted = cryptoEngine.decryptText(payload, alicePriv, alicePub, "c1")
        assertEquals("Hello PWA!", decrypted)
    }
}
