package com.chatrok.app.data.fake

import com.chatrok.app.core.model.Conversation
import com.chatrok.app.core.model.Message
import com.chatrok.app.core.model.MessageStatus
import com.chatrok.app.core.model.MessageType
import com.chatrok.app.core.model.User
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Mock/Fake Database terpisah dari build produksi.
 * Didesain khusus untuk menguji alur Login, Autentikasi, Pembuatan Percakapan,
 * dan Pengiriman/Penerimaan Pesan Chat secara independen tanpa ketergantungan pada Firebase backend.
 */
class MockChatrokDatabase {

    data class MockCredential(
        val email: String,
        val passwordHash: String,
        val userId: String
    )

    // Tabel in-memory terisolasi
    private val usersTable = ConcurrentHashMap<String, User>()
    private val credentialsTable = ConcurrentHashMap<String, MockCredential>()
    private val conversationsTable = ConcurrentHashMap<String, Conversation>()
    private val messagesTable = ConcurrentHashMap<String, CopyOnWriteArrayList<Message>>()

    init {
        seedDefaults()
    }

    /**
     * Mengisi data default awal untuk pengujian instan.
     */
    fun seedDefaults() {
        usersTable.clear()
        credentialsTable.clear()
        conversationsTable.clear()
        messagesTable.clear()

        val userAlex = User(
            id = "usr_mock_1",
            username = "alex_tester",
            displayName = "Alex Tester",
            bio = "Pengguna Uji Coba SIMNI-C",
            phoneNumber = "+6281234567890",
            isOnline = true,
            lastSeen = System.currentTimeMillis()
        )

        val userSarah = User(
            id = "usr_mock_2",
            username = "sarah_tester",
            displayName = "Sarah Tester",
            bio = "Partner Chat Uji Coba",
            phoneNumber = "+6289876543210",
            isOnline = true,
            lastSeen = System.currentTimeMillis()
        )

        val userBudi = User(
            id = "usr_mock_3",
            username = "budi_tester",
            displayName = "Budi Offline",
            bio = "Pengguna Simulasi Offline",
            isOnline = false,
            lastSeen = System.currentTimeMillis() - 3600000
        )

        // Seed Users
        usersTable[userAlex.id] = userAlex
        usersTable[userSarah.id] = userSarah
        usersTable[userBudi.id] = userBudi

        // Seed Credentials for Login
        credentialsTable["alex@chatrok.test"] = MockCredential("alex@chatrok.test", "Password123!", userAlex.id)
        credentialsTable["sarah@chatrok.test"] = MockCredential("sarah@chatrok.test", "Password123!", userSarah.id)

        // Seed Initial Conversation between Alex and Sarah
        val convId = "conv_alex_sarah"
        val initialMsg = Message(
            id = "msg_seed_1",
            conversationId = convId,
            senderId = userSarah.id,
            text = "Halo Alex! Ini pesan uji coba dari mock database.",
            timestamp = System.currentTimeMillis() - 120000,
            status = MessageStatus.READ,
            type = MessageType.TEXT,
            isOutgoing = false
        )

        val conv = Conversation(
            id = convId,
            otherUser = userSarah,
            lastMessage = initialMsg,
            unreadCount = 0,
            lastUpdated = initialMsg.timestamp
        )

        conversationsTable[convId] = conv
        messagesTable[convId] = CopyOnWriteArrayList(listOf(initialMsg))
    }

    /**
     * Operasi Otentikasi & Pengguna (Login/Register/Query)
     */
    fun authenticate(email: String, password: String): Result<User> {
        val credential = credentialsTable[email.trim().lowercase()]
            ?: return Result.failure(IllegalArgumentException("Akun tidak ditemukan"))

        if (credential.passwordHash != password) {
            return Result.failure(IllegalArgumentException("Kata sandi salah"))
        }

        val user = usersTable[credential.userId]
            ?: return Result.failure(IllegalStateException("Data profil pengguna korup"))

        return Result.success(user)
    }

    fun registerUser(email: String, password: String, username: String, displayName: String): Result<User> {
        val normalizedEmail = email.trim().lowercase()
        if (credentialsTable.containsKey(normalizedEmail)) {
            return Result.failure(IllegalArgumentException("Email sudah terdaftar"))
        }

        val newUserId = "usr_mock_" + UUID.randomUUID().toString().take(8)
        val newUser = User(
            id = newUserId,
            username = username,
            displayName = displayName,
            isOnline = true
        )

        usersTable[newUserId] = newUser
        credentialsTable[normalizedEmail] = MockCredential(normalizedEmail, password, newUserId)
        return Result.success(newUser)
    }

    fun getUser(userId: String): User? = usersTable[userId]

    fun getAllUsers(): List<User> = usersTable.values.toList()

    fun updateUser(user: User): Result<Unit> {
        usersTable[user.id] = user
        return Result.success(Unit)
    }

    /**
     * Operasi Chat & Percakapan
     */
    fun getConversations(): List<Conversation> = conversationsTable.values.sortedByDescending { it.lastUpdated }

    fun getConversation(conversationId: String): Conversation? = conversationsTable[conversationId]

    fun getOrCreateConversation(currentUserId: String, targetUserId: String): Conversation {
        val existing = conversationsTable.values.find {
            it.otherUser.id == targetUserId
        }
        if (existing != null) return existing

        val targetUser = usersTable[targetUserId] ?: User(
            id = targetUserId,
            username = "unknown",
            displayName = "Pengguna Baru"
        )

        val newConvId = "conv_${currentUserId}_${targetUserId}"
        val newConv = Conversation(
            id = newConvId,
            otherUser = targetUser,
            lastMessage = null,
            unreadCount = 0,
            lastUpdated = System.currentTimeMillis()
        )
        conversationsTable[newConvId] = newConv
        messagesTable[newConvId] = CopyOnWriteArrayList()
        return newConv
    }

    fun getMessages(conversationId: String): List<Message> {
        return messagesTable[conversationId]?.toList() ?: emptyList()
    }

    fun insertMessage(conversationId: String, message: Message): Message {
        val list = messagesTable.getOrPut(conversationId) { CopyOnWriteArrayList() }
        list.add(message)

        // Update ringkasan percakapan
        val conv = conversationsTable[conversationId]
        if (conv != null) {
            conversationsTable[conversationId] = conv.copy(
                lastMessage = message,
                lastUpdated = message.timestamp,
                unreadCount = if (!message.isOutgoing) conv.unreadCount + 1 else conv.unreadCount
            )
        }
        return message
    }

    fun deleteMessage(conversationId: String, messageId: String): Boolean {
        val list = messagesTable[conversationId] ?: return false
        val removed = list.removeIf { it.id == messageId }
        if (removed) {
            val last = list.lastOrNull()
            conversationsTable[conversationId]?.let { conv ->
                conversationsTable[conversationId] = conv.copy(
                    lastMessage = last,
                    lastUpdated = last?.timestamp ?: conv.lastUpdated
                )
            }
        }
        return removed
    }

    fun clearAll() {
        usersTable.clear()
        credentialsTable.clear()
        conversationsTable.clear()
        messagesTable.clear()
    }
}
