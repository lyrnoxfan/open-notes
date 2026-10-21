package com.example.privatenotes.data.repository

import com.example.privatenotes.data.local.NoteDao
import com.example.privatenotes.data.local.NoteEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Note reads and writes.
 *
 * Timestamps come from [TimeSource], never from scattered `System.currentTimeMillis()` calls,
 * and every mutation stamps `updatedAt` here so the rule lives in one place.
 */
class NoteRepository(
    private val noteDao: NoteDao,
    private val timeSource: TimeSource = TimeSource.System,
) {

    fun observeActiveNotes(): Flow<List<NoteEntity>> = noteDao.observeActive()

    /** Inclusive search with the user's term escaped so `%` and `_` stay literal (§7.3). */
    fun search(term: String): Flow<List<NoteEntity>> = noteDao.search(LikePattern.escape(term))

    suspend fun findById(id: String): NoteEntity? = noteDao.findById(id)

    suspend fun createNote(title: String, content: String): String {
        val now = timeSource.nowMillis()
        val id = UUID.randomUUID().toString()
        noteDao.insert(
            NoteEntity(
                id = id,
                title = title,
                content = content,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return id
    }

    suspend fun updateNoteContent(note: NoteEntity, title: String, content: String) {
        noteDao.update(note.copy(title = title, content = content, updatedAt = timeSource.nowMillis()))
    }

    suspend fun moveToTrash(id: String) {
        noteDao.moveToTrash(id, timeSource.nowMillis())
    }

    suspend fun restoreFromTrash(id: String) {
        noteDao.restoreFromTrash(id, timeSource.nowMillis())
    }

    suspend fun purgeTrashOlderThan(cutoff: Long): Int = noteDao.purgeTrashOlderThan(cutoff)

    suspend fun activeNoteCount(): Int = noteDao.countActive()
}
