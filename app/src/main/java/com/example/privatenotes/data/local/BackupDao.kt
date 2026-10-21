package com.example.privatenotes.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * Database access used only by backup export and merge import.
 *
 * These queries deliberately bypass the UI-facing DAOs: a backup must see everything, including
 * trashed notes and tombstones, and the importer has to be able to update rows by identity
 * without deleting them first (a REPLACE would cascade into `note_tag_refs`).
 */
@Dao
interface BackupDao {

    @Query("SELECT * FROM notes")
    suspend fun allNotes(): List<NoteEntity>

    @Query("SELECT * FROM tags")
    suspend fun allTags(): List<TagEntity>

    @Query("SELECT * FROM note_tag_refs")
    suspend fun allNoteTags(): List<NoteTagCrossRef>

    @Query("SELECT * FROM deletion_markers")
    suspend fun allDeletionMarkers(): List<DeletionMarkerEntity>

    @Update
    suspend fun updateNote(note: NoteEntity): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNote(note: NoteEntity): Long

    @Update
    suspend fun updateTag(tag: TagEntity): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: TagEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDeletionMarker(marker: DeletionMarkerEntity)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteNote(id: String): Int

    @Query("DELETE FROM note_tag_refs WHERE noteId = :noteId")
    suspend fun clearTagsForNote(noteId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNoteTag(ref: NoteTagCrossRef): Long

    @Query("SELECT id FROM tags")
    suspend fun allTagIds(): List<String>

    @Query("SELECT id FROM notes")
    suspend fun allNoteIds(): List<String>
}
