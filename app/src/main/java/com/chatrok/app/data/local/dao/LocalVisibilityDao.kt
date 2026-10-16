package com.chatrok.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.chatrok.app.data.local.entity.LocalVisibilityEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalVisibilityDao {

    @Query("SELECT * FROM local_visibility")
    fun getAllVisibilityFlow(): Flow<List<LocalVisibilityEntity>>

    @Query("SELECT * FROM local_visibility")
    suspend fun getAllVisibility(): List<LocalVisibilityEntity>

    @Query("SELECT * FROM local_visibility WHERE type = :type")
    suspend fun getVisibilityByType(type: String): List<LocalVisibilityEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVisibility(entry: LocalVisibilityEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVisibilities(entries: List<LocalVisibilityEntity>)

    @Query("DELETE FROM local_visibility WHERE type = :type AND targetId = :targetId")
    suspend fun deleteVisibility(type: String, targetId: String)

    @Query("DELETE FROM local_visibility WHERE targetId = :targetId")
    suspend fun deleteVisibilityForTarget(targetId: String)

    @Query("DELETE FROM local_visibility")
    suspend fun clearAllVisibility()
}
