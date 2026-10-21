package com.lyrnox.opennotes.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lyrnox.opennotes.data.local.entity.NoteTagCrossRef
import com.lyrnox.opennotes.data.local.entity.TagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: TagEntity): Long

    @Query("SELECT * FROM tags ORDER BY name ASC")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE name = :name")
    suspend fun findByName(name: String): TagEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun attach(crossRef: NoteTagCrossRef)

    @Query("DELETE FROM note_tag_refs WHERE noteId = :noteId AND tagId = :tagId")
    suspend fun detach(noteId: Long, tagId: Long)

    @Query("SELECT tagId FROM note_tag_refs WHERE noteId = :noteId")
    suspend fun tagIdsForNote(noteId: Long): List<Long>

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun delete(id: Long): Int
}
