package com.chatrok.app

import com.chatrok.app.core.model.Message
import com.chatrok.app.core.model.MessageStatus
import com.chatrok.app.core.model.MessageType
import com.chatrok.app.data.fake.MockChatrokDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FakeDatabaseLoginAndChatTest {

    private lateinit var mockDb: MockChatrokDatabase

    @Before
    fun setUp() {
        mockDb = MockChatrokDatabase()
    }

    @Test
    fun testLoginSuccessWithValidCredentials() {
        val loginResult = mockDb.authenticate("alex@chatrok.test", "Password123!")
        assertTrue("Login harus berhasil untuk kredensial valid", loginResult.isSuccess)

        val user = loginResult.getOrNull()
        assertNotNull(user)
        assertEquals("usr_mock_1", user?.id)
        assertEquals("alex_tester", user?.username)
        assertEquals("Alex Tester", user?.displayName)
    }

    @Test
    fun testLoginFailureWithInvalidPassword() {
        val loginResult = mockDb.authenticate("alex@chatrok.test", "WrongPassword!")
        assertTrue("Login harus gagal bila kata sandi salah", loginResult.isFailure)
        assertEquals("Kata sandi salah", loginResult.exceptionOrNull()?.message)
    }

    @Test
    fun testLoginFailureWithUnregisteredEmail() {
        val loginResult = mockDb.authenticate("unknown@chatrok.test", "Password123!")
        assertTrue("Login harus gagal bila email belum terdaftar", loginResult.isFailure)
        assertEquals("Akun tidak ditemukan", loginResult.exceptionOrNull()?.message)
    }

    @Test
    fun testRegisterNewUserAndImmediateLogin() {
        val regResult = mockDb.registerUser(
            email = "citra@chatrok.test",
            password = "SecretPassword!",
            username = "citra_test",
            displayName = "Citra Lestari"
        )
        assertTrue("Registrasi harus berhasil", regResult.isSuccess)
        val createdUser = regResult.getOrNull()
        assertNotNull(createdUser)
        assertEquals("citra_test", createdUser?.username)

        // Verifikasi login langsung berhasil dengan akun baru
        val authResult = mockDb.authenticate("citra@chatrok.test", "SecretPassword!")
        assertTrue("Login setelah registrasi harus berhasil", authResult.isSuccess)
        assertEquals(createdUser?.id, authResult.getOrNull()?.id)
    }

    @Test
    fun testGetInitialConversationAndMessages() {
        val conversations = mockDb.getConversations()
        assertFalse("Daftar percakapan tidak boleh kosong", conversations.isEmpty())

        val alexSarahConv = conversations.first()
        assertEquals("conv_alex_sarah", alexSarahConv.id)
        assertEquals("usr_mock_2", alexSarahConv.otherUser.id)

        val messages = mockDb.getMessages(alexSarahConv.id)
        assertEquals(1, messages.size)
        assertEquals("Halo Alex! Ini pesan uji coba dari mock database.", messages[0].text)
    }

    @Test
    fun testSendAndReceiveMessagesInChat() {
        val convId = "conv_alex_sarah"
        val newOutgoingMessage = Message(
            id = "msg_out_test_1",
            conversationId = convId,
            senderId = "usr_mock_1",
            text = "Halo Sarah, uji coba pengiriman pesan berhasil!",
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.SENT,
            type = MessageType.TEXT,
            isOutgoing = true
        )

        mockDb.insertMessage(convId, newOutgoingMessage)

        val updatedMessages = mockDb.getMessages(convId)
        assertEquals(2, updatedMessages.size)
        assertEquals(newOutgoingMessage.text, updatedMessages[1].text)

        // Verifikasi percakapan ter-update dengan lastMessage terbaru
        val updatedConv = mockDb.getConversation(convId)
        assertNotNull(updatedConv)
        assertEquals(newOutgoingMessage.id, updatedConv?.lastMessage?.id)
        assertEquals(newOutgoingMessage.timestamp, updatedConv?.lastUpdated)
    }

    @Test
    fun testCreateNewConversationAndSendFirstMessage() {
        val newConv = mockDb.getOrCreateConversation(
            currentUserId = "usr_mock_1",
            targetUserId = "usr_mock_3"
        )
        assertNotNull(newConv)
        assertEquals("conv_usr_mock_1_usr_mock_3", newConv.id)
        assertEquals("usr_mock_3", newConv.otherUser.id)

        val firstMsg = Message(
            id = "msg_first_budi",
            conversationId = newConv.id,
            senderId = "usr_mock_1",
            text = "Hai Budi!",
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.SENT,
            isOutgoing = true
        )
        mockDb.insertMessage(newConv.id, firstMsg)

        val messages = mockDb.getMessages(newConv.id)
        assertEquals(1, messages.size)
        assertEquals("Hai Budi!", messages.first().text)
    }

    @Test
    fun testDeleteMessageUpdatesConversationState() {
        val convId = "conv_alex_sarah"
        val msg1 = Message(
            id = "msg_del_test_1",
            conversationId = convId,
            senderId = "usr_mock_1",
            text = "Pesan ini akan dihapus",
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.SENT,
            isOutgoing = true
        )
        mockDb.insertMessage(convId, msg1)

        val deleted = mockDb.deleteMessage(convId, "msg_del_test_1")
        assertTrue("Penghapusan pesan harus berhasil", deleted)

        val remainingMessages = mockDb.getMessages(convId)
        assertEquals(1, remainingMessages.size)
        assertEquals("msg_seed_1", remainingMessages[0].id)
    }
}
