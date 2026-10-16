package com.chatrok.app

import com.chatrok.app.core.model.Message
import com.chatrok.app.core.model.MessageStatus
import com.chatrok.app.core.model.MessageType
import com.chatrok.app.core.model.ReplyPreview
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
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeMessageGatewayM2 : MessageGateway {
    val remote = MutableStateFlow<List<Message>>(emptyList())
    var failSend = false
    var sendCalls = 0
    var lastSentId: String? = null
    var lastSentText: String? = null
    var exists = false
    var deleteCalls = 0
    var older: List<Message> = emptyList()

    override fun observeMessages(uid: String, conversationId: String, limit: Long): Flow<List<Message>> = remote

    override suspend fun loadOlderMessages(uid: String, conversationId: String, afterMessageId: String?, pageSize: Int): List<Message> =
        older.take(pageSize)

    override suspend fun sendText(
        uid: String,
        conversationId: String,
        messageId: String,
        text: String,
        replyTo: ReplyPreview?
    ): Message {
        sendCalls++
        lastSentId = messageId
        lastSentText = text
        if (failSend) throw IllegalStateException("network")
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
        exists = true
        return message
    }

    override suspend fun messageExists(conversationId: String, messageId: String): Boolean = exists

    override suspend fun deleteMessage(uid: String, conversationId: String, messageId: String) {
        deleteCalls++
        remote.value = remote.value.filterNot { it.id == messageId }
    }
}

class Milestone2MessageRepositoryTest {
    private val uidProvider = AuthenticatedUidProvider { "uid_a" }

    @Test
    fun sendTextUsesStableGeneratedIdAndTransitionsToSent() = runTest {
        val gateway = FakeMessageGatewayM2()
        val repo = FirestoreMessageRepository(gateway, uidProvider, MessagingLocalVisibilityStore())
        val result = repo.sendMessage("conv", " halo ")
        assertTrue(result.isSuccess)
        assertEquals("halo", gateway.lastSentText)
        assertTrue(gateway.lastSentId!!.matches(Regex("^!\\d{13}_[0-9a-f]{24}$")))
        assertEquals(MessageStatus.SENT, result.getOrThrow().status)
    }

    @Test
    fun failedSendRemainsVisibleAsFailedAndRetryUsesSameMessageId() = runTest {
        val gateway = FakeMessageGatewayM2().apply { failSend = true }
        val repo = FirestoreMessageRepository(gateway, uidProvider, MessagingLocalVisibilityStore())
        val first = repo.sendMessage("conv", "tes retry")
        assertTrue(first.isFailure)
        val failed = repo.getMessages("conv").first().single()
        assertEquals(MessageStatus.FAILED, failed.status)
        val originalId = failed.id

        gateway.failSend = false
        gateway.exists = false
        val retry = repo.retryMessage(originalId)
        assertTrue(retry.isSuccess)
        assertEquals(originalId, gateway.lastSentId)
        assertEquals(2, gateway.sendCalls)
    }

    @Test
    fun realtimeMergeDeduplicatesMessagesById() = runTest {
        val gateway = FakeMessageGatewayM2()
        val duplicate = Message("!8000000000000_aaaaaaaaaaaaaaaaaaaaaaaa", "conv", "uid_b", "halo", 1L, isOutgoing = false)
        gateway.remote.value = listOf(duplicate, duplicate.copy(status = MessageStatus.READ))
        val repo = FirestoreMessageRepository(gateway, uidProvider, MessagingLocalVisibilityStore())
        val list = repo.getMessages("conv").first()
        assertEquals(1, list.size)
        assertEquals(MessageStatus.READ, list.single().status)
    }

    @Test
    fun paginationMergesOlderMessagesWithoutOverlap() = runTest {
        val gateway = FakeMessageGatewayM2()
        gateway.older = listOf(
            Message("!9000000000000_bbbbbbbbbbbbbbbbbbbbbbbb", "conv", "uid_b", "lama", 1L, isOutgoing = false)
        )
        val repo = FirestoreMessageRepository(gateway, uidProvider, MessagingLocalVisibilityStore())
        val result = repo.loadOlderMessages("conv", 50)
        assertEquals(1, result.getOrThrow())
        assertEquals("lama", repo.getMessages("conv").first().single().text)
    }

    @Test
    fun deleteForMeHidesLocallyWithoutRemoteDeleteAndDeleteForEveryoneCallsGateway() = runTest {
        val gateway = FakeMessageGatewayM2()
        val msg = Message("!8000000000000_cccccccccccccccccccccccc", "conv", "uid_a", "hapus", 2L, isOutgoing = true)
        gateway.remote.value = listOf(msg)
        val repo = FirestoreMessageRepository(gateway, uidProvider, MessagingLocalVisibilityStore())
        repo.getMessages("conv").first() // populate index
        assertTrue(repo.deleteMessage(msg.id, forEveryone = false).isSuccess)
        assertEquals(0, gateway.deleteCalls)
        assertTrue(repo.getMessages("conv").first().isEmpty())

        val gateway2 = FakeMessageGatewayM2().apply { remote.value = listOf(msg) }
        val repo2 = FirestoreMessageRepository(gateway2, uidProvider, MessagingLocalVisibilityStore())
        repo2.getMessages("conv").first()
        assertTrue(repo2.deleteMessage(msg.id, forEveryone = true).isSuccess)
        assertEquals(1, gateway2.deleteCalls)
    }

    @Test
    fun clearCutoffFiltersOlderMessages() = runTest {
        val gateway = FakeMessageGatewayM2()
        gateway.remote.value = listOf(
            Message("!9000000000000_dddddddddddddddddddddddd", "conv", "uid_b", "lama", 1L, isOutgoing = false),
            Message("!7000000000000_eeeeeeeeeeeeeeeeeeeeeeee", "conv", "uid_b", "baru", Long.MAX_VALUE, isOutgoing = false)
        )
        val visibility = MessagingLocalVisibilityStore()
        val repo = FirestoreMessageRepository(gateway, uidProvider, visibility)
        visibility.clearConversation("conv", cutoffMs = 10L)
        val list = repo.getMessages("conv").first()
        assertEquals(1, list.size)
        assertEquals("baru", list.single().text)
    }
}
