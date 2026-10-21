package com.example.privatenotes.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DeletionMarkerDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(marker: DeletionMarkerEntity)

    @Query("SELECT * FROM deletion_markers WHERE noteId = :noteId")
    suspend fun findByNoteId(noteId: String): DeletionMarkerEntity?

    @Query("SELECT noteId FROM deletion_markers")
    fun observeAllNoteIds(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM deletion_markers")
    suspend fun count(): Int
}
