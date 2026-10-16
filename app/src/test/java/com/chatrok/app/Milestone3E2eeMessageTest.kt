package com.chatrok.app

import com.chatrok.app.core.crypto.E2eeCryptoEngine
import com.chatrok.app.core.crypto.EncryptedMessagePayload
import com.chatrok.app.core.crypto.JwkKey
import com.chatrok.app.core.crypto.LocalE2eeIdentity
import com.chatrok.app.core.model.Message
import com.chatrok.app.core.model.MessageStatus
import com.chatrok.app.core.model.MessageType
import com.chatrok.app.core.model.ReplyPreview
import com.chatrok.app.core.security.SecureIdentityStorage
import com.chatrok.app.data.message.FirestoreMessageRepository
import com.chatrok.app.data.message.MessageGateway
import com.chatrok.app.data.messaging.AuthenticatedUidProvider
import com.chatrok.app.data.messaging.MessagingLocalVisibilityStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import androidx.test.core.app.ApplicationProvider
import android.content.Context

private class FakeE2eeMessageGateway(
    val cryptoEngine: E2eeCryptoEngine,
    val senderIdentity: LocalE2eeIdentity,
    val peerIdentity: LocalE2eeIdentity
) : MessageGateway {
    val remote = MutableStateFlow<List<Message>>(emptyList())
    var lastSentPayload: EncryptedMessagePayload? = null
    var lastSentMessageId: String? = null
    var lastSentSenderUid: String? = null
    var lastSentRecipientUid: String? = null

    override fun observeMessages(uid: String, conversationId: String, limit: Long): Flow<List<Message>> = remote

    override suspend fun loadOlderMessages(
        uid: String,
        conversationId: String,
        afterMessageId: String?,
        pageSize: Int
    ): List<Message> = emptyList()

    override suspend fun sendText(
        uid: String,
        conversationId: String,
        messageId: String,
        text: String,
        replyTo: ReplyPreview?
    ): Message {
        lastSentMessageId = messageId
        lastSentSenderUid = uid
        val recipientUid = if (uid == senderIdentity.fingerprint) "peer_uid" else "sender_uid"
        lastSentRecipientUid = recipientUid

        val context = "conversation:$conversationId|users:sender_uid:peer_uid"
        val aad = "conversation:$conversationId"
        val encrypted = cryptoEngine.encryptText(
            text = text,
            privateJwk = senderIdentity.privateJwk,
            peerPublicJwk = peerIdentity.publicJwk,
            context = context,
            additionalData = aad
        )
        lastSentPayload = encrypted

        val message = Message(
            id = messageId,
            conversationId = conversationId,
            senderId = uid,
            text = text,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.SENT,
            type = MessageType.TEXT,
            replyTo = replyTo,
            isOutgoing = true
        )
        remote.value = (remote.value + message).distinctBy { it.id }
        return message
    }

    override suspend fun messageExists(conversationId: String, messageId: String): Boolean =
        remote.value.any { it.id == messageId }

    override suspend fun deleteMessage(uid: String, conversationId: String, messageId: String) {
        remote.value = remote.value.filterNot { it.id == messageId }
    }
}

@RunWith(RobolectricTestRunner::class)
class Milestone3E2eeMessageTest {

    private lateinit var context: Context
    private lateinit var cryptoEngine: E2eeCryptoEngine
    private lateinit var identityStorage: SecureIdentityStorage

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        cryptoEngine = E2eeCryptoEngine()
        identityStorage = SecureIdentityStorage(context, cryptoEngine)
    }

    @Test
    fun testMilestone3EndToEndEncryptionAndDecryptionFlow() {
        val aliceUid = "alice_uid"
        val bobUid = "bob_uid"
        val convId = "direct_alice_bob_123"

        val aliceIdentity = identityStorage.getOrCreateIdentity(aliceUid)
        val bobIdentity = identityStorage.getOrCreateIdentity(bobUid)

        val plainText = "Rahasia Milestone 3: Pesan terenkripsi ECDH P-256 dan AES-GCM!"
        val aad = "conversation:$convId"
        val e2eeContext = "conversation:$convId|users:alice_uid:bob_uid"

        // Alice mengenskripsi pesan untuk Bob
        val encryptedPayload = cryptoEngine.encryptText(
            text = plainText,
            privateJwk = aliceIdentity.privateJwk,
            peerPublicJwk = bobIdentity.publicJwk,
            context = e2eeContext,
            additionalData = aad
        )

        assertEquals(1, encryptedPayload.cryptoVersion)
        assertFalse(encryptedPayload.ciphertext.contains(plainText))
        assertTrue(encryptedPayload.iv.isNotEmpty())
        assertEquals(aad, encryptedPayload.aad)

        // Bob berhasil mendekripsi pesan dari Alice
        val decryptedByBob = cryptoEngine.decryptText(
            payload = encryptedPayload,
            privateJwk = bobIdentity.privateJwk,
            peerPublicJwk = aliceIdentity.publicJwk,
            context = e2eeContext
        )

        assertEquals(plainText, decryptedByBob)
    }

    @Test
    fun testMilestone3RejectsTamperedCiphertext() {
        val aliceUid = "alice_uid"
        val bobUid = "bob_uid"
        val convId = "direct_tamper_test"

        val aliceIdentity = identityStorage.getOrCreateIdentity(aliceUid)
        val bobIdentity = identityStorage.getOrCreateIdentity(bobUid)

        val originalText = "Pesan otentik"
        val aad = "conversation:$convId"
        val e2eeContext = "conversation:$convId|users:alice_uid:bob_uid"

        val payload = cryptoEngine.encryptText(
            text = originalText,
            privateJwk = aliceIdentity.privateJwk,
            peerPublicJwk = bobIdentity.publicJwk,
            context = e2eeContext,
            additionalData = aad
        )

        // Tamper ciphertext
        val tamperedCiphertext = if (payload.ciphertext.startsWith("A")) "B" + payload.ciphertext.drop(1) else "A" + payload.ciphertext.drop(1)
        val tamperedPayload = payload.copy(ciphertext = tamperedCiphertext)

        var failed = false
        try {
            cryptoEngine.decryptText(
                payload = tamperedPayload,
                privateJwk = bobIdentity.privateJwk,
                peerPublicJwk = aliceIdentity.publicJwk,
                context = e2eeContext
            )
        } catch (_: Exception) {
            failed = true
        }
        assertTrue("Dekripsi pesan yang dirusak harus gagal (GCM auth tag mismatch)", failed)
    }

    @Test
    fun testMilestone3MessageGatewayE2eeDelivery() = runTest {
        val aliceUid = "alice_uid"
        val bobUid = "bob_uid"
        val aliceIdentity = identityStorage.getOrCreateIdentity(aliceUid)
        val bobIdentity = identityStorage.getOrCreateIdentity(bobUid)

        val gateway = FakeE2eeMessageGateway(cryptoEngine, aliceIdentity, bobIdentity)
        val repo = FirestoreMessageRepository(
            gateway = gateway,
            uidProvider = AuthenticatedUidProvider { aliceUid },
            visibilityStore = MessagingLocalVisibilityStore()
        )

        val sendResult = repo.sendMessage("conv_m3", "Pesan M3 E2EE Terkirim")
        assertTrue(sendResult.isSuccess)
        val sentMessage = sendResult.getOrThrow()
        assertEquals(MessageStatus.SENT, sentMessage.status)
        assertNotNull(gateway.lastSentPayload)
        assertEquals(1, gateway.lastSentPayload!!.cryptoVersion)

        // Verifikasi payload yang dikirim terenkripsi dan dapat didekripsi
        val decrypted = cryptoEngine.decryptText(
            payload = gateway.lastSentPayload!!,
            privateJwk = bobIdentity.privateJwk,
            peerPublicJwk = aliceIdentity.publicJwk,
            context = "conversation:conv_m3|users:sender_uid:peer_uid"
        )
        assertEquals("Pesan M3 E2EE Terkirim", decrypted)
    }
}
