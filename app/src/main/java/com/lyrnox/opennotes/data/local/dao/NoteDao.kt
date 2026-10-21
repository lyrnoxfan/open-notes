package com.lyrnox.opennotes.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.lyrnox.opennotes.data.local.entity.NoteEntity
import com.lyrnox.opennotes.data.local.relation.NoteWithTags
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    @Insert
    suspend fun insert(note: NoteEntity): Long

    @Update
    suspend fun update(note: NoteEntity)

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun findById(id: Long): NoteEntity?

    @Query("SELECT * FROM notes WHERE deletedAt IS NULL ORDER BY pinned DESC, updatedAt DESC")
    fun observeActive(): Flow<List<NoteEntity>>

    @Query(
        """
        SELECT * FROM notes
        WHERE deletedAt IS NULL AND folderId IS :folderId
        ORDER BY pinned DESC, updatedAt DESC
        """,
    )
    fun observeActiveInFolder(folderId: Long?): Flow<List<NoteEntity>>

    @Transaction
    @Query("SELECT * FROM notes WHERE id = :id")
    fun observeWithTags(id: Long): Flow<NoteWithTags?>

    @Query("SELECT * FROM notes WHERE deletedAt IS NULL AND (title LIKE '%' || :term || '%' OR content LIKE '%' || :term || '%') ORDER BY updatedAt DESC")
    fun search(term: String): Flow<List<NoteEntity>>

    @Query("UPDATE notes SET deletedAt = :timestamp, updatedAt = :timestamp WHERE id = :id")
    suspend fun moveToTrash(id: Long, timestamp: Long)

    @Query("UPDATE notes SET deletedAt = NULL, updatedAt = :timestamp WHERE id = :id")
    suspend fun restoreFromTrash(id: Long, timestamp: Long)

    @Query("DELETE FROM notes WHERE deletedAt IS NOT NULL AND deletedAt <= :cutoff")
    suspend fun purgeTrashOlderThan(cutoff: Long): Int

    @Query("SELECT COUNT(*) FROM notes WHERE deletedAt IS NULL")
    suspend fun countActive(): Int

    @Query("SELECT COUNT(*) FROM notes WHERE deletedAt IS NOT NULL")
    suspend fun countTrashed(): Int
}
