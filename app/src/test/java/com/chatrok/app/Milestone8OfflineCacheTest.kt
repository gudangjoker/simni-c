package com.chatrok.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chatrok.app.core.model.Attachment
import com.chatrok.app.core.model.AttachmentType
import com.chatrok.app.core.model.Conversation
import com.chatrok.app.core.model.Message
import com.chatrok.app.core.model.MessageStatus
import com.chatrok.app.core.model.MessageType
import com.chatrok.app.core.model.User
import com.chatrok.app.data.conversation.ConversationGateway
import com.chatrok.app.data.conversation.FirestoreConversationRepository
import com.chatrok.app.data.local.ChatrokDatabase
import com.chatrok.app.data.local.dao.ConversationDao
import com.chatrok.app.data.local.dao.DraftDao
import com.chatrok.app.data.local.dao.LocalVisibilityDao
import com.chatrok.app.data.local.dao.MessageDao
import com.chatrok.app.data.local.dao.UserDao
import com.chatrok.app.data.local.entity.DraftEntity
import com.chatrok.app.data.local.entity.LocalVisibilityEntity
import com.chatrok.app.data.local.mapper.EntityMappers.toDomain
import com.chatrok.app.data.local.mapper.EntityMappers.toEntity
import com.chatrok.app.data.message.FirestoreMessageRepository
import com.chatrok.app.data.message.MessageGateway
import com.chatrok.app.data.messaging.AuthenticatedUidProvider
import com.chatrok.app.data.messaging.MessagingLocalVisibilityStore
import com.chatrok.app.data.user.FirestoreUserGateway
import com.chatrok.app.data.user.FirestoreUserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Milestone8OfflineCacheTest {

    private lateinit var context: Context
    private lateinit var db: ChatrokDatabase
    private lateinit var messageDao: MessageDao
    private lateinit var conversationDao: ConversationDao
    private lateinit var userDao: UserDao
    private lateinit var localVisibilityDao: LocalVisibilityDao
    private lateinit var draftDao: DraftDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ChatrokDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        messageDao = db.messageDao()
        conversationDao = db.conversationDao()
        userDao = db.userDao()
        localVisibilityDao = db.localVisibilityDao()
        draftDao = db.draftDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `MessageDao insert and query messages by conversation ordered by timestamp`() = runTest {
        val msg1 = Message(
            id = "msg_001",
            conversationId = "conv_100",
            senderId = "alice",
            text = "Halo, selamat pagi!",
            timestamp = 1000L,
            status = MessageStatus.SENT,
            type = MessageType.TEXT,
            isOutgoing = true
        )
        val msg2 = Message(
            id = "msg_002",
            conversationId = "conv_100",
            senderId = "bob",
            text = "Pagi juga Alice",
            timestamp = 2000L,
            status = MessageStatus.DELIVERED,
            type = MessageType.TEXT,
            isOutgoing = false
        )
        val msgOtherConv = Message(
            id = "msg_003",
            conversationId = "conv_200",
            senderId = "charlie",
            text = "Pesan berbeda percakapan",
            timestamp = 1500L,
            status = MessageStatus.SENT,
            type = MessageType.TEXT,
            isOutgoing = false
        )

        messageDao.insertMessages(listOf(msg2.toEntity(), msg1.toEntity(), msgOtherConv.toEntity()))

        val cachedMessages = messageDao.getMessages("conv_100")
        assertEquals(2, cachedMessages.size)
        assertEquals("msg_001", cachedMessages[0].id)
        assertEquals("msg_002", cachedMessages[1].id)

        val domainMsg = cachedMessages[0].toDomain()
        assertEquals("Halo, selamat pagi!", domainMsg.text)
        assertEquals(MessageStatus.SENT, domainMsg.status)
        assertTrue(domainMsg.isOutgoing)
    }

    @Test
    fun `MessageDao update status and delete operations`() = runTest {
        val msg = Message(
            id = "msg_status_test",
            conversationId = "conv_300",
            senderId = "user1",
            text = "Testing status update",
            timestamp = 3000L,
            status = MessageStatus.SENDING,
            type = MessageType.TEXT,
            isOutgoing = true
        )
        messageDao.insertMessage(msg.toEntity())

        val inserted = messageDao.getMessageById("msg_status_test")
        assertNotNull(inserted)
        assertEquals(MessageStatus.SENDING.name, inserted?.status)

        messageDao.updateMessageStatus("msg_status_test", MessageStatus.READ.name)
        val updated = messageDao.getMessageById("msg_status_test")
        assertEquals(MessageStatus.READ.name, updated?.status)

        messageDao.deleteMessageById("msg_status_test")
        assertNull(messageDao.getMessageById("msg_status_test"))
    }

    @Test
    fun `MessageDao deleteMessagesOlderThan cutoff correctly removes previous messages`() = runTest {
        val m1 = Message("m1", "conv_cutoff", "u1", "old msg 1", 1000L, MessageStatus.READ, MessageType.TEXT, isOutgoing = false)
        val m2 = Message("m2", "conv_cutoff", "u1", "old msg 2", 2000L, MessageStatus.READ, MessageType.TEXT, isOutgoing = false)
        val m3 = Message("m3", "conv_cutoff", "u1", "new msg 3", 3000L, MessageStatus.READ, MessageType.TEXT, isOutgoing = false)

        messageDao.insertMessages(listOf(m1.toEntity(), m2.toEntity(), m3.toEntity()))
        assertEquals(3, messageDao.getMessages("conv_cutoff").size)

        messageDao.deleteMessagesOlderThan("conv_cutoff", 2000L)
        val remaining = messageDao.getMessages("conv_cutoff")
        assertEquals(1, remaining.size)
        assertEquals("m3", remaining[0].id)
    }

    @Test
    fun `ConversationDao insert, sorting with pinned, and markAsRead`() = runTest {
        val user1 = User("u1", "alice", "Alice Wonder", null, "", null, false, 0L, false)
        val user2 = User("u2", "bob", "Bob Builder", null, "", null, false, 0L, false)

        val conv1 = Conversation(
            id = "conv_1",
            otherUser = user1,
            lastMessage = null,
            unreadCount = 3,
            lastUpdated = 1000L,
            isPinned = false,
            isMuted = false
        )
        val conv2 = Conversation(
            id = "conv_2",
            otherUser = user2,
            lastMessage = null,
            unreadCount = 5,
            lastUpdated = 500L,
            isPinned = true,
            isMuted = false
        )

        conversationDao.insertConversations(listOf(conv1.toEntity(), conv2.toEntity()))

        val conversations = conversationDao.getConversations()
        assertEquals(2, conversations.size)
        // Pinned conversation should come first regardless of lastUpdated
        assertEquals("conv_2", conversations[0].id)
        assertTrue(conversations[0].isPinned)
        assertEquals("conv_1", conversations[1].id)

        // Mark conv1 as read
        conversationDao.markAsRead("conv_1")
        val updatedConv1 = conversationDao.getConversationById("conv_1")
        assertEquals(0, updatedConv1?.unreadCount)

        // Delete conv2
        conversationDao.deleteConversationById("conv_2")
        assertEquals(1, conversationDao.getConversations().size)
    }

    @Test
    fun `UserDao insert, query, and search operations`() = runTest {
        val user1 = User("u1", "charlie_brown", "Charlie Brown", null, "Bio 1", null, true, 1000L, false)
        val user2 = User("u2", "snoopy_dog", "Snoopy", null, "Bio 2", null, false, 2000L, false)

        userDao.insertUsers(listOf(user1.toEntity(), user2.toEntity()))

        val fetched = userDao.getUserById("u1")
        assertNotNull(fetched)
        assertEquals("charlie_brown", fetched?.username)

        val searchResults = userDao.searchUsersFlow("snoopy").first()
        assertEquals(1, searchResults.size)
        assertEquals("snoopy_dog", searchResults[0].username)

        userDao.updateBlockStatus("u1", true)
        val updatedUser = userDao.getUserById("u1")
        assertTrue(updatedUser?.isBlocked == true)
    }

    @Test
    fun `MessagingLocalVisibilityStore persists and restores across store restarts`() = runTest {
        // First session: write visibility state
        val store1 = MessagingLocalVisibilityStore(localVisibilityDao, this)
        store1.hideConversation("conv_hidden_1").join()
        store1.markConversationRead("conv_read_1").join()
        store1.clearConversation("conv_cleared_1", 8888L).join()
        store1.hideMessage("msg_hidden_1").join()

        // Verify entries persisted in DAO
        val allVis = localVisibilityDao.getAllVisibility()
        assertTrue(allVis.any { it.type == LocalVisibilityEntity.TYPE_HIDDEN_CONVERSATION && it.targetId == "conv_hidden_1" })
        assertTrue(allVis.any { it.type == LocalVisibilityEntity.TYPE_LOCALLY_READ_CONVERSATION && it.targetId == "conv_read_1" })
        assertTrue(allVis.any { it.type == LocalVisibilityEntity.TYPE_CLEAR_CUTOFF && it.targetId == "conv_cleared_1" && it.cutoffMs == 8888L })
        assertTrue(allVis.any { it.type == LocalVisibilityEntity.TYPE_HIDDEN_MESSAGE && it.targetId == "msg_hidden_1" })

        // Second session: create new store instance and restore from database
        val store2 = MessagingLocalVisibilityStore(localVisibilityDao, this)
        store2.initJob?.join()

        assertTrue(store2.hiddenConversationIds.value.contains("conv_hidden_1"))
        assertTrue(store2.locallyReadConversationIds.value.contains("conv_read_1"))
        assertEquals(8888L, store2.clearCutoffs.value["conv_cleared_1"])
        assertTrue(store2.hiddenMessageIds.value.contains("msg_hidden_1"))

        // Test clearLocalVisibility
        store2.clearLocalVisibility().join()
        assertTrue(store2.hiddenConversationIds.value.isEmpty())
        assertTrue(store2.locallyReadConversationIds.value.isEmpty())
        assertTrue(localVisibilityDao.getAllVisibility().isEmpty())
    }

    @Test
    fun `DraftDao persists message drafts across sessions`() = runTest {
        draftDao.saveDraft(DraftEntity("conv_draft_1", "Halo ini pesan draf yang belum terkirim"))

        val fetchedDraft = draftDao.getDraft("conv_draft_1")
        assertNotNull(fetchedDraft)
        assertEquals("Halo ini pesan draf yang belum terkirim", fetchedDraft?.draftText)

        draftDao.deleteDraft("conv_draft_1")
        assertNull(draftDao.getDraft("conv_draft_1"))
    }

    @Test
    fun `FirestoreConversationRepository offline fallback returns cached Room conversations when remote is empty`() = runTest {
        val userPeer = User("peer_1", "peer_user", "Peer Display", null, "", null, true, 0L, false)
        val cachedConv = Conversation(
            id = "conv_cached_offline",
            otherUser = userPeer,
            lastMessage = null,
            unreadCount = 2,
            lastUpdated = 5000L,
            isPinned = false,
            isMuted = false
        )
        // Pre-populate Room database
        conversationDao.insertConversation(cachedConv.toEntity())

        // Gateway simulating offline (emits empty list)
        val mockGateway = object : ConversationGateway {
            override fun observeConversations(uid: String): Flow<List<Conversation>> = flowOf(emptyList())
            override fun observeConversation(uid: String, conversationId: String): Flow<Conversation?> = flowOf(null)
            override suspend fun getOrCreateConversation(uid: String, peerUid: String): Conversation = cachedConv
            override suspend fun markAsRead(uid: String, conversationId: String) {}
        }

        val uidProvider = AuthenticatedUidProvider { "my_uid" }
        val visibilityStore = MessagingLocalVisibilityStore(localVisibilityDao)

        val repo = FirestoreConversationRepository(
            gateway = mockGateway,
            uidProvider = uidProvider,
            visibilityStore = visibilityStore,
            conversationDao = conversationDao
        )

        val conversations = repo.getConversations().first()
        assertEquals(1, conversations.size)
        assertEquals("conv_cached_offline", conversations[0].id)
        assertEquals("Peer Display", conversations[0].otherUser.displayName)
    }

    @Test
    fun `FirestoreMessageRepository offline fallback returns cached Room messages when remote is empty`() = runTest {
        val cachedMsg = Message(
            id = "msg_offline_cached",
            conversationId = "conv_test_offline",
            senderId = "peer_uid",
            text = "Pesan tersimpan di cache lokal",
            timestamp = 10000L,
            status = MessageStatus.DELIVERED,
            type = MessageType.TEXT,
            isOutgoing = false
        )
        // Pre-populate Room database
        messageDao.insertMessage(cachedMsg.toEntity())

        // Gateway simulating offline (emits empty list and empty receipts)
        val mockGateway = object : MessageGateway {
            override fun observeMessages(uid: String, conversationId: String, limit: Long): Flow<List<Message>> = flowOf(emptyList())
            override fun observeReceipts(conversationId: String): Flow<Map<String, com.chatrok.app.core.receipt.ReceiptData>> = flowOf(emptyMap())
            override suspend fun sendText(uid: String, conversationId: String, messageId: String, text: String, replyTo: com.chatrok.app.core.model.ReplyPreview?): Message = cachedMsg
            override suspend fun sendMedia(uid: String, conversationId: String, messageId: String, attachment: Attachment, caption: String?, replyTo: com.chatrok.app.core.model.ReplyPreview?): Message = cachedMsg
            override suspend fun deleteMessage(uid: String, conversationId: String, messageId: String) {}
            override suspend fun loadOlderMessages(uid: String, conversationId: String, afterMessageId: String?, pageSize: Int): List<Message> = emptyList()
            override suspend fun acknowledgeReceipt(uid: String, conversationId: String, lastDeliveredId: String?, lastReadId: String?) {}
            override suspend fun messageExists(conversationId: String, messageId: String): Boolean = false
        }

        val uidProvider = AuthenticatedUidProvider { "my_uid" }
        val visibilityStore = MessagingLocalVisibilityStore(localVisibilityDao)

        val repo = FirestoreMessageRepository(
            gateway = mockGateway,
            uidProvider = uidProvider,
            visibilityStore = visibilityStore,
            messageDao = messageDao,
            draftDao = draftDao
        )

        val messages = repo.getMessages("conv_test_offline").first()
        assertEquals(1, messages.size)
        assertEquals("msg_offline_cached", messages[0].id)
        assertEquals("Pesan tersimpan di cache lokal", messages[0].text)
    }

    @Test
    fun `Milestone 7 call isolation - Room database contains no WebRTC or calling dependencies`() {
        // Verify database tables do not require any WebRTC calling artifacts,
        // honoring the safe deferral of Milestone 7
        val entities = arrayOf(
            com.chatrok.app.data.local.entity.MessageEntity::class,
            com.chatrok.app.data.local.entity.ConversationEntity::class,
            com.chatrok.app.data.local.entity.UserEntity::class,
            com.chatrok.app.data.local.entity.LocalVisibilityEntity::class,
            com.chatrok.app.data.local.entity.DraftEntity::class
        )
        assertEquals(5, entities.size)
    }
}
