package com.chatrok.app

import com.chatrok.app.core.common.ConversationIdUtils
import com.chatrok.app.core.model.Conversation
import com.chatrok.app.core.model.User
import com.chatrok.app.data.conversation.ConversationGateway
import com.chatrok.app.data.conversation.FirestoreConversationRepository
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

private class FakeConversationGatewayM2 : ConversationGateway {
    val conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val byId = mutableMapOf<String, MutableStateFlow<Conversation?>>()
    val readIds = mutableListOf<String>()
    var createCalls = 0

    override fun observeConversations(uid: String): Flow<List<Conversation>> = conversations
    override fun observeConversation(uid: String, conversationId: String): Flow<Conversation?> =
        byId.getOrPut(conversationId) { MutableStateFlow(null) }

    override suspend fun getOrCreateConversation(uid: String, peerUid: String): Conversation {
        createCalls++
        val id = ConversationIdUtils.computeConversationId(uid, peerUid)
        val existing = conversations.value.firstOrNull { it.id == id }
        if (existing != null) return existing
        val created = Conversation(
            id = id,
            otherUser = User(peerUid, "peer", "Peer"),
            lastUpdated = 1000L
        )
        conversations.value = conversations.value + created
        byId.getOrPut(id) { MutableStateFlow(null) }.value = created
        return created
    }

    override suspend fun markAsRead(uid: String, conversationId: String) {
        readIds += conversationId
    }
}

class Milestone2ConversationRepositoryTest {
    private val uidProvider = AuthenticatedUidProvider { "uid_a" }

    @Test
    fun deterministicConversationIdIsSameForBothDirectionsAndRejectsSelf() {
        val ab = ConversationIdUtils.computeConversationId("uid_a", "uid_b")
        val ba = ConversationIdUtils.computeConversationId("uid_b", "uid_a")
        assertEquals(ab, ba)
        assertTrue(ab.startsWith("direct_"))
        assertEquals(71, ab.length)
        var rejected = false
        try { ConversationIdUtils.computeConversationId("uid_a", "uid_a") } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
    }

    @Test
    fun getOrCreateIsIdempotentAndDoesNotDuplicatePair() = runTest {
        val gateway = FakeConversationGatewayM2()
        val repo = FirestoreConversationRepository(gateway, uidProvider, MessagingLocalVisibilityStore())
        val first = repo.getOrCreateConversationWith("uid_b")
        val second = repo.getOrCreateConversationWith("uid_b")
        assertEquals(first.id, second.id)
        assertEquals(1, gateway.conversations.value.size)
    }

    @Test
    fun markAsReadSetsLocalUnreadToZeroAndCallsGatewayReceipt() = runTest {
        val gateway = FakeConversationGatewayM2()
        val id = ConversationIdUtils.computeConversationId("uid_a", "uid_b")
        gateway.conversations.value = listOf(
            Conversation(id, User("uid_b", "peer", "Peer"), unreadCount = 4, lastUpdated = 1L)
        )
        val repo = FirestoreConversationRepository(gateway, uidProvider, MessagingLocalVisibilityStore())
        repo.markAsRead(id)
        assertEquals(listOf(id), gateway.readIds)
        assertEquals(0, repo.getConversations().first().single().unreadCount)
    }

    @Test
    fun deleteConversationIsUserLocalVisibilityOnly() = runTest {
        val gateway = FakeConversationGatewayM2()
        val id = ConversationIdUtils.computeConversationId("uid_a", "uid_b")
        gateway.conversations.value = listOf(Conversation(id, User("uid_b", "peer", "Peer")))
        val repo = FirestoreConversationRepository(gateway, uidProvider, MessagingLocalVisibilityStore())
        assertTrue(repo.deleteConversation(id).isSuccess)
        assertTrue(repo.getConversations().first().isEmpty())
        assertFalse(gateway.conversations.value.isEmpty())
    }
}
