package com.chatrok.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversations",
    indices = [
        Index(value = ["lastUpdated"]),
        Index(value = ["isPinned", "lastUpdated"])
    ]
)
data class ConversationEntity(
    @PrimaryKey
    val id: String,
    val otherUserId: String,
    val otherUsername: String,
    val otherDisplayName: String,
    val otherAvatarUrl: String?,
    val otherBio: String,
    val otherPhoneNumber: String?,
    val otherIsOnline: Boolean,
    val otherLastSeen: Long,
    val lastMessageId: String? = null,
    val lastMessageText: String? = null,
    val lastMessageSenderId: String? = null,
    val lastMessageTimestamp: Long? = null,
    val lastMessageStatus: String? = null,
    val lastMessageType: String? = null,
    val unreadCount: Int = 0,
    val lastUpdated: Long = System.currentTimeMillis(),
    val isPinned: Boolean = false,
    val isMuted: Boolean = false
)
