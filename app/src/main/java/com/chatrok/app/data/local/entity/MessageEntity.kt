package com.chatrok.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversationId"]),
        Index(value = ["timestamp"]),
        Index(value = ["conversationId", "timestamp"])
    ]
)
data class MessageEntity(
    @PrimaryKey
    val id: String,
    val conversationId: String,
    val senderId: String,
    val text: String,
    val timestamp: Long,
    val status: String,
    val type: String,
    val isOutgoing: Boolean,
    // Optional attachment fields
    val attachmentId: String? = null,
    val attachmentType: String? = null,
    val attachmentTitle: String? = null,
    val attachmentSizeBytes: Long = 0L,
    val attachmentDurationSeconds: Int? = null,
    val attachmentUriOrUrl: String = "",
    val attachmentMimeType: String = "",
    val attachmentObjectKey: String? = null,
    val attachmentMediaIv: String? = null,
    val attachmentLocalFilePath: String? = null,
    // Optional reply fields
    val replyMessageId: String? = null,
    val replySenderName: String? = null,
    val replySnippet: String? = null,
    val replyType: String? = null,
    val replySenderId: String? = null
)
