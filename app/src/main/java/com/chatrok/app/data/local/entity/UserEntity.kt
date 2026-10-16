package com.chatrok.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.chatrok.app.core.model.DEFAULT_USER_BIO

@Entity(
    tableName = "users",
    indices = [
        Index(value = ["username"])
    ]
)
data class UserEntity(
    @PrimaryKey
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val bio: String = DEFAULT_USER_BIO,
    val phoneNumber: String? = null,
    val isOnline: Boolean = false,
    val lastSeen: Long = System.currentTimeMillis(),
    val isBlocked: Boolean = false,
    val cachedAt: Long = System.currentTimeMillis()
)
