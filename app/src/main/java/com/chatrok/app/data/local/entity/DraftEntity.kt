package com.chatrok.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "drafts")
data class DraftEntity(
    @PrimaryKey
    val conversationId: String,
    val draftText: String,
    val updatedAt: Long = System.currentTimeMillis()
)
