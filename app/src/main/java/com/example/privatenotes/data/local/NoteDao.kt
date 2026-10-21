package com.example.privatenotes.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    @Insert
    suspend fun insert(note: NoteEntity)

    @Update
    suspend fun update(note: NoteEntity)

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun findById(id: String): NoteEntity?

    /** Notes visible in the normal list, newest edit first (§4.3). */
    @Query("SELECT * FROM notes WHERE deletedAt IS NULL ORDER BY updatedAt DESC")
    fun observeActive(): Flow<List<NoteEntity>>

    @Transaction
    @Query("SELECT * FROM notes WHERE id = :id")
    fun observeWithTags(id: String): Flow<NoteWithTags?>

    /**
     * Inclusive search over title and body (§7.3).
     *
     * Parameters are bound, not concatenated, so quotes, `%`, `_` and other SQL metacharacters
     * in user input cannot change the statement.
     */
    @Query(
        """
        SELECT * FROM notes
        WHERE deletedAt IS NULL
          AND (title LIKE '%' || :term || '%' ESCAPE '\' OR content LIKE '%' || :term || '%' ESCAPE '\')
        ORDER BY updatedAt DESC
        """,
    )
    fun search(term: String): Flow<List<NoteEntity>>

    @Query("UPDATE notes SET deletedAt = :timestamp, updatedAt = :timestamp WHERE id = :id")
    suspend fun moveToTrash(id: String, timestamp: Long)

    @Query("UPDATE notes SET deletedAt = NULL, updatedAt = :timestamp WHERE id = :id")
    suspend fun restoreFromTrash(id: String, timestamp: Long)

    /** Notes sitting in the trash, most recently deleted first (§8). */
    @Query("SELECT * FROM notes WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeTrash(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE deletedAt IS NOT NULL AND deletedAt <= :cutoff")
    suspend fun findExpiredTrash(cutoff: Long): List<NoteEntity>

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM notes WHERE deletedAt IS NOT NULL AND deletedAt <= :cutoff")
    suspend fun purgeTrashOlderThan(cutoff: Long): Int

    /**
     * List/search query with tags attached.
     *
     * One statement serves both the plain list and the filtered one: a null [tagId] means "any
     * tag", and an empty [term] matches everything. Tag filtering is an EXISTS subquery over the
     * stable-ID join table, so a note with several tags is never duplicated (§7.3).
     */
    @Transaction
    @Query(
        """
        SELECT * FROM notes
        WHERE deletedAt IS NULL
          AND (:term = '' OR title LIKE '%' || :term || '%' ESCAPE '\' OR content LIKE '%' || :term || '%' ESCAPE '\')
          AND (:tagId IS NULL OR EXISTS (
                SELECT 1 FROM note_tag_refs refs
                WHERE refs.noteId = notes.id AND refs.tagId = :tagId))
        ORDER BY updatedAt DESC
        """,
    )
    fun observeNotesWithTags(term: String, tagId: String?): Flow<List<NoteWithTags>>

    @Query("SELECT COUNT(*) FROM notes WHERE deletedAt IS NULL")
    suspend fun countActive(): Int

    @Query("SELECT COUNT(*) FROM notes WHERE deletedAt IS NOT NULL")
    suspend fun countTrashed(): Int
}
