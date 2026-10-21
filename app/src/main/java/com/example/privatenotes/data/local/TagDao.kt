package com.example.privatenotes.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: TagEntity): Long

    @Update
    suspend fun update(tag: TagEntity)

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun findById(id: String): TagEntity?

    @Query("SELECT * FROM tags WHERE name = :name")
    suspend fun findByName(name: String): TagEntity?

    @Query("SELECT * FROM tags ORDER BY name ASC")
    fun observeAll(): Flow<List<TagEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun attach(crossRef: NoteTagCrossRef)

    @Query("DELETE FROM note_tag_refs WHERE noteId = :noteId AND tagId = :tagId")
    suspend fun detach(noteId: String, tagId: String)

    @Query("SELECT tagId FROM note_tag_refs WHERE noteId = :noteId")
    suspend fun tagIdsForNote(noteId: String): List<String>

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun delete(id: String): Int
}
