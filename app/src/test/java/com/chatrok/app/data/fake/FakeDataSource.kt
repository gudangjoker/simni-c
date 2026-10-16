package com.chatrok.app.data.fake

import com.chatrok.app.core.model.CallSession
import com.chatrok.app.core.model.Conversation
import com.chatrok.app.core.model.DEFAULT_USER_BIO
import com.chatrok.app.core.model.Message
import com.chatrok.app.core.model.MessageType
import com.chatrok.app.core.model.User

object FakeDataSource {
    val currentUser: User = User(
        id = "usr_1",
        username = "alex_simni",
        displayName = "Alex Pratama",
        bio = DEFAULT_USER_BIO,
        isOnline = true,
        lastSeen = System.currentTimeMillis()
    )

    val users: List<User> = listOf(
        currentUser,
        User(
            id = "usr_2",
            username = "sarah_k",
            displayName = "Sarah Kurnia",
            bio = DEFAULT_USER_BIO,
            isOnline = true,
            lastSeen = System.currentTimeMillis()
        ),
        User(
            id = "usr_3",
            username = "budi_w",
            displayName = "Budi Wijaya",
            bio = DEFAULT_USER_BIO,
            isOnline = false,
            lastSeen = System.currentTimeMillis() - 3600 * 1000
        )
    )

    fun getInitialConversations(): List<Conversation> = listOf(
        Conversation(
            id = "conv_usr_2",
            otherUser = users[1],
            lastMessage = Message(
                id = "msg_init_1",
                conversationId = "conv_usr_2",
                senderId = "usr_2",
                text = "Halo Alex!",
                timestamp = System.currentTimeMillis() - 60000,
                type = MessageType.TEXT,
                isOutgoing = false
            ),
            unreadCount = 1,
            lastUpdated = System.currentTimeMillis() - 60000
        )
    )

    val initialMessages: Map<String, List<Message>> = mapOf(
        "conv_usr_2" to listOf(
            Message(
                id = "msg_init_1",
                conversationId = "conv_usr_2",
                senderId = "usr_2",
                text = "Halo Alex!",
                timestamp = System.currentTimeMillis() - 60000,
                type = MessageType.TEXT,
                isOutgoing = false
            )
        )
    )

    val sampleCallHistory: List<CallSession> = emptyList()
}
