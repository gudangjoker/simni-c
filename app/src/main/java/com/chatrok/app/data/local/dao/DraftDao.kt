package com.chatrok.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.chatrok.app.data.local.entity.DraftEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DraftDao {

    @Query("SELECT * FROM drafts WHERE conversationId = :conversationId LIMIT 1")
    fun getDraftFlow(conversationId: String): Flow<DraftEntity?>

    @Query("SELECT * FROM drafts WHERE conversationId = :conversationId LIMIT 1")
    suspend fun getDraft(conversationId: String): DraftEntity?

    @Query("SELECT * FROM drafts")
    suspend fun getAllDrafts(): List<DraftEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveDraft(draft: DraftEntity)

    @Query("DELETE FROM drafts WHERE conversationId = :conversationId")
    suspend fun deleteDraft(conversationId: String)

    @Query("DELETE FROM drafts")
    suspend fun clearAllDrafts()
}
