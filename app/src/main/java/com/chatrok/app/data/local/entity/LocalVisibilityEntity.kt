package com.chatrok.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "local_visibility",
    primaryKeys = ["type", "targetId"],
    indices = [
        Index(value = ["type"]),
        Index(value = ["targetId"])
    ]
)
data class LocalVisibilityEntity(
    val type: String, // e.g. "hidden_conversation", "locally_read_conversation", "hidden_message", "clear_cutoff"
    val targetId: String,
    val cutoffMs: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val TYPE_HIDDEN_CONVERSATION = "hidden_conversation"
        const val TYPE_LOCALLY_READ_CONVERSATION = "locally_read_conversation"
        const val TYPE_HIDDEN_MESSAGE = "hidden_message"
        const val TYPE_CLEAR_CUTOFF = "clear_cutoff"
    }
}
