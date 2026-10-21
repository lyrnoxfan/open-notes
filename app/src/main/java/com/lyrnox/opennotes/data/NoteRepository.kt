package com.lyrnox.opennotes.data

import com.lyrnox.opennotes.data.local.dao.NoteDao
import com.lyrnox.opennotes.data.local.entity.NoteEntity
import kotlinx.coroutines.flow.Flow

/** Coordinates note reads and writes; the DAO stays an implementation detail. */
class NoteRepository(private val noteDao: NoteDao) {

    fun observeActiveNotes(): Flow<List<NoteEntity>> = noteDao.observeActive()

    suspend fun createNote(title: String, content: String, now: Long): Long =
        noteDao.insert(
            NoteEntity(
                title = title,
                content = content,
                createdAt = now,
                updatedAt = now,
            ),
        )

    suspend fun activeNoteCount(): Int = noteDao.countActive()
}
