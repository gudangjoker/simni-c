package com.chatrok.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.chatrok.app.core.common.DateTimeUtils
import com.chatrok.app.core.common.MessageIdUtils
import com.chatrok.app.core.model.Message
import com.chatrok.app.core.model.MessageStatus
import com.chatrok.app.core.model.MessageType
import com.chatrok.app.core.model.PresenceState
import com.chatrok.app.core.model.ReplyPreview
import com.chatrok.app.core.presence.PresenceCore
import com.chatrok.app.core.receipt.MessageReceiptCore
import com.chatrok.app.core.receipt.ReceiptData
import com.chatrok.app.data.message.FirestoreMessageRepository
import com.chatrok.app.data.message.MessageGateway
import com.chatrok.app.data.messaging.AuthenticatedUidProvider
import com.chatrok.app.data.messaging.MessagingLocalVisibilityStore
import com.chatrok.app.data.presence.FirestorePresenceRepository
import com.chatrok.app.data.presence.PresenceGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneId
import java.time.ZonedDateTime

private class FakeMilestone4MessageGateway : MessageGateway {
    val remoteMessages = MutableStateFlow<List<Message>>(emptyList())
    val remoteReceipts = MutableStateFlow<Map<String, ReceiptData>>(emptyMap())

    var lastAckedUid: String? = null
    var lastAckedConvId: String? = null
    var lastAckedDeliveredId: String? = null
    var lastAckedReadId: String? = null

    override fun observeMessages(uid: String, conversationId: String, limit: Long): Flow<List<Message>> = remoteMessages

    override fun observeReceipts(conversationId: String): Flow<Map<String, ReceiptData>> = remoteReceipts

    override suspend fun acknowledgeReceipt(
        uid: String,
        conversationId: String,
        lastDeliveredId: String?,
        lastReadId: String?
    ) {
        lastAckedUid = uid
        lastAckedConvId = conversationId
        lastAckedDeliveredId = lastDeliveredId
        lastAckedReadId = lastReadId

        val existing = remoteReceipts.value[uid] ?: ReceiptData(uid = uid, conversationId = conversationId)
        val updated = existing.copy(
            lastDeliveredMessageId = lastDeliveredId ?: existing.lastDeliveredMessageId,
            lastReadMessageId = lastReadId ?: existing.lastReadMessageId,
            updatedAt = System.currentTimeMillis()
        )
        remoteReceipts.value = remoteReceipts.value + (uid to updated)
    }

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
        val msg = Message(
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
        remoteMessages.value = (remoteMessages.value + msg).distinctBy { it.id }
        return msg
    }

    override suspend fun messageExists(conversationId: String, messageId: String): Boolean =
        remoteMessages.value.any { it.id == messageId }

    override suspend fun deleteMessage(uid: String, conversationId: String, messageId: String) {
        remoteMessages.value = remoteMessages.value.filterNot { it.id == messageId }
    }
}

private class FakeMilestone4PresenceGateway : PresenceGateway {
    val presenceMap = MutableStateFlow<Map<String, Pair<Boolean, Long>>>(emptyMap())
    val typingMap = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    var lastSetPresenceUid: String? = null
    var lastSetPresenceOnline: Boolean? = null
    var lastSetTypingConvId: String? = null
    var lastSetTypingUid: String? = null
    var lastSetTypingState: Boolean? = null

    override fun observeUserPresence(userId: String): Flow<Pair<Boolean, Long>> {
        return MutableStateFlow(presenceMap.value[userId] ?: Pair(false, 0L))
    }

    override fun observeUserTyping(conversationId: String, userId: String): Flow<Boolean> {
        val key = "$conversationId:$userId"
        return MutableStateFlow(typingMap.value[key] ?: false)
    }

    override suspend fun setPresence(uid: String, isOnline: Boolean) {
        lastSetPresenceUid = uid
        lastSetPresenceOnline = isOnline
        presenceMap.value = presenceMap.value + (uid to Pair(isOnline, System.currentTimeMillis()))
    }

    override suspend fun setTyping(uid: String, conversationId: String, isTyping: Boolean) {
        lastSetTypingConvId = conversationId
        lastSetTypingUid = uid
        lastSetTypingState = isTyping
        val key = "$conversationId:$uid"
        typingMap.value = typingMap.value + (key to isTyping)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class Milestone4ReceiptsPresenceTypingTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testMilestone4MonotonicReceiptStatusProgression() {
        val now = System.currentTimeMillis()
        val msgId1 = MessageIdUtils.generateMessageId(now)
        val msgId2 = MessageIdUtils.generateMessageId(now + 5000)

        val outMsg = Message(
            id = msgId1,
            conversationId = "conv_m4",
            senderId = "me_uid",
            text = "Halo M4",
            timestamp = now,
            status = MessageStatus.SENT,
            type = MessageType.TEXT,
            isOutgoing = true
        )

        // 1. Initial outgoing message: SENT
        val statusInitial = MessageReceiptCore.resolveMessageStatus(outMsg, null)
        assertEquals(MessageStatus.SENT, statusInitial)

        // 2. Peer acknowledges delivered up to msgId1
        val deliveredReceipt = ReceiptData(
            uid = "peer_uid",
            conversationId = "conv_m4",
            lastDeliveredMessageId = msgId1,
            lastDeliveredAt = now + 1000
        )
        val statusDelivered = MessageReceiptCore.resolveMessageStatus(outMsg, deliveredReceipt)
        assertEquals(MessageStatus.DELIVERED, statusDelivered)

        // 3. Peer acknowledges read up to msgId1
        val readReceipt = ReceiptData(
            uid = "peer_uid",
            conversationId = "conv_m4",
            lastDeliveredMessageId = msgId1,
            lastDeliveredAt = now + 1000,
            lastReadMessageId = msgId1,
            lastReadAt = now + 2000
        )
        val statusRead = MessageReceiptCore.resolveMessageStatus(outMsg, readReceipt)
        assertEquals(MessageStatus.READ, statusRead)

        // 4. Verification of monotonicity: READ cannot regress back to DELIVERED or SENT
        assertFalse(MessageReceiptCore.canAdvanceStatus(MessageStatus.READ, MessageStatus.DELIVERED))
        assertFalse(MessageReceiptCore.canAdvanceStatus(MessageStatus.READ, MessageStatus.SENT))
        assertFalse(MessageReceiptCore.canAdvanceStatus(MessageStatus.DELIVERED, MessageStatus.SENT))
    }

    @Test
    fun testMilestone4MessageRepositoryReceiptIntegration() = runTest {
        val myUid = "alice_uid"
        val peerUid = "bob_uid"
        val convId = "conv_alice_bob"

        val gateway = FakeMilestone4MessageGateway()
        val visibilityStore = MessagingLocalVisibilityStore()
        val repo = FirestoreMessageRepository(
            gateway = gateway,
            uidProvider = AuthenticatedUidProvider { myUid },
            visibilityStore = visibilityStore
        )

        // Kirim pesan outgoing
        val sendResult = repo.sendMessage(convId, "Testing M4 Receipts")
        assertTrue(sendResult.isSuccess)
        val sentMsg = sendResult.getOrThrow()

        // Ambil stream pesan sebelum peer receipt
        var messages = repo.getMessages(convId).first()
        val loadedMsg = messages.first { it.id == sentMsg.id }
        assertEquals(MessageStatus.SENT, loadedMsg.status)

        // Peer kirim ack DELIVERED
        gateway.remoteReceipts.value = mapOf(
            peerUid to ReceiptData(
                uid = peerUid,
                conversationId = convId,
                lastDeliveredMessageId = sentMsg.id,
                lastDeliveredAt = System.currentTimeMillis()
            )
        )

        messages = repo.getMessages(convId).first()
        val deliveredMsg = messages.first { it.id == sentMsg.id }
        assertEquals(MessageStatus.DELIVERED, deliveredMsg.status)

        // Peer kirim ack READ
        gateway.remoteReceipts.value = mapOf(
            peerUid to ReceiptData(
                uid = peerUid,
                conversationId = convId,
                lastDeliveredMessageId = sentMsg.id,
                lastDeliveredAt = System.currentTimeMillis(),
                lastReadMessageId = sentMsg.id,
                lastReadAt = System.currentTimeMillis() + 1000
            )
        )

        messages = repo.getMessages(convId).first()
        val readMsg = messages.first { it.id == sentMsg.id }
        assertEquals(MessageStatus.READ, readMsg.status)
    }

    @Test
    fun testMilestone4AutoAcknowledgeDeliveryForIncomingMessages() = runTest {
        val myUid = "alice_uid"
        val peerUid = "bob_uid"
        val convId = "conv_alice_bob"

        val gateway = FakeMilestone4MessageGateway()
        val repo = FirestoreMessageRepository(
            gateway = gateway,
            uidProvider = AuthenticatedUidProvider { myUid },
            visibilityStore = MessagingLocalVisibilityStore(),
            repoScope = this
        )

        val incomingTimestamp = System.currentTimeMillis()
        val incomingId = MessageIdUtils.generateMessageId(incomingTimestamp)
        val incomingMsg = Message(
            id = incomingId,
            conversationId = convId,
            senderId = peerUid,
            text = "Pesan masuk dari Bob",
            timestamp = incomingTimestamp,
            status = MessageStatus.SENT,
            type = MessageType.TEXT,
            isOutgoing = false
        )

        // Bob mengirim pesan ke room
        gateway.remoteMessages.value = listOf(incomingMsg)

        // Alice mengamati messages -> memicu auto-ack delivery
        val list = repo.getMessages(convId).first()
        assertEquals(1, list.size)

        // Verifikasi delivery di-ack ke gateway
        assertEquals(myUid, gateway.lastAckedUid)
        assertEquals(convId, gateway.lastAckedConvId)
        assertEquals(incomingId, gateway.lastAckedDeliveredId)
    }

    @Test
    fun testMilestone4PresenceOnlineAndStaleDetection() {
        val now = 1750000000000L

        // 1. Fresh heartbeat -> online
        val (onlineFresh, lastSeenFresh) = PresenceCore.computePresenceState(
            online = true,
            lastSeenMs = now - 5000L,
            updatedAtMs = now - 15000L, // 15 detik (di bawah 65 detik)
            nowMs = now
        )
        assertTrue(onlineFresh)
        assertEquals(now - 5000L, lastSeenFresh)

        // 2. Stale heartbeat (> 65000ms) -> otomatis offline
        val (onlineStale, lastSeenStale) = PresenceCore.computePresenceState(
            online = true,
            lastSeenMs = now - 5000L,
            updatedAtMs = now - 70000L, // 70 detik
            nowMs = now
        )
        assertFalse("Heartbeat usang harus dievaluasi offline", onlineStale)
        assertEquals(now - 70000L, lastSeenStale)

        // 3. Format last seen
        val formatted = PresenceCore.formatLastSeen(now - 70000L, now)
        assertTrue(formatted.startsWith("terakhir dilihat hari ini pukul"))
    }

    @Test
    fun testMilestone4TypingDebounceAndExpiry() {
        val now = 1750000000000L

        // 1. Ketikan aktif (dalam 5 detik)
        val typingActive = PresenceCore.computeTypingState(
            typing = true,
            updatedAtMs = now - 2000L, // 2s lalu
            nowMs = now
        )
        assertTrue(typingActive)

        // 2. Ketikan kadaluarsa (> 5000ms) -> otomatis false
        val typingExpired = PresenceCore.computeTypingState(
            typing = true,
            updatedAtMs = now - 5500L, // 5.5s lalu
            nowMs = now
        )
        assertFalse("Typing melebihi 5 detik harus otomatis expired", typingExpired)

        // 3. Throttling penulisan heartbeat (3 detik)
        assertFalse(PresenceCore.shouldSendTypingHeartbeat(lastSentAtMs = now - 1500L, nowMs = now))
        assertTrue(PresenceCore.shouldSendTypingHeartbeat(lastSentAtMs = now - 3500L, nowMs = now))
    }

    @Test
    fun testMilestone4PresenceRepositoryLifecycleBackgroundAndForeground() = runTest {
        val myUid = "user_m4_test"
        val gateway = FakeMilestone4PresenceGateway()
        val repo = FirestorePresenceRepository(
            context = context,
            gateway = gateway,
            uidProvider = AuthenticatedUidProvider { myUid },
            scope = this
        )

        try {
            // 1. App masuk background -> setPresence(false)
            repo.setPresence(false)
            assertEquals(myUid, gateway.lastSetPresenceUid)
            assertEquals(false, gateway.lastSetPresenceOnline)

            // 2. App masuk foreground -> setPresence(true)
            repo.setPresence(true)
            assertEquals(myUid, gateway.lastSetPresenceUid)
            assertEquals(true, gateway.lastSetPresenceOnline)

            // 3. Set typing
            repo.setTyping("conv_123", true)
            assertEquals("conv_123", gateway.lastSetTypingConvId)
            assertEquals(myUid, gateway.lastSetTypingUid)
            assertEquals(true, gateway.lastSetTypingState)

            // 4. Clear typing
            repo.setTyping("conv_123", false)
            assertEquals(false, gateway.lastSetTypingState)
        } finally {
            repo.stopHeartbeatLoop()
        }
    }

    @Test
    fun testReceiptDeliveredAndReadVisualIndicatorContract() {
        val sentStatus = MessageStatus.SENT
        val deliveredStatus = MessageStatus.DELIVERED
        val readStatus = MessageStatus.READ

        // Contract verification for visual indicators:
        // SENT: Single checkmark
        // DELIVERED: Double checkmark (slate grey)
        // READ: Double checkmark (electric cyan/blue)
        assertTrue(sentStatus.ordinal < deliveredStatus.ordinal)
        assertTrue(deliveredStatus.ordinal < readStatus.ordinal)

        assertEquals(com.chatrok.app.core.designsystem.theme.ReceiptRead, androidx.compose.ui.graphics.Color(0xFF0284C7))
        assertEquals(com.chatrok.app.core.designsystem.theme.ReceiptDelivered, androidx.compose.ui.graphics.Color(0xFF64748B))
    }
}
