package com.chatrok.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.chatrok.app.data.local.dao.ConversationDao
import com.chatrok.app.data.local.dao.DraftDao
import com.chatrok.app.data.local.dao.LocalVisibilityDao
import com.chatrok.app.data.local.dao.MessageDao
import com.chatrok.app.data.local.dao.UserDao
import com.chatrok.app.data.local.entity.ConversationEntity
import com.chatrok.app.data.local.entity.DraftEntity
import com.chatrok.app.data.local.entity.LocalVisibilityEntity
import com.chatrok.app.data.local.entity.MessageEntity
import com.chatrok.app.data.local.entity.UserEntity

@Database(
    entities = [
        MessageEntity::class,
        ConversationEntity::class,
        UserEntity::class,
        LocalVisibilityEntity::class,
        DraftEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class ChatrokDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun userDao(): UserDao
    abstract fun localVisibilityDao(): LocalVisibilityDao
    abstract fun draftDao(): DraftDao

    companion object {
        const val DATABASE_NAME = "chatrok_offline.db"
    }
}
