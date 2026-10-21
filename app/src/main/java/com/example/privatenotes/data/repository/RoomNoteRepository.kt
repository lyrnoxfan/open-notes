package com.example.privatenotes.data.repository

import androidx.room.withTransaction
import com.example.privatenotes.data.local.AppDatabase
import com.example.privatenotes.data.local.DeletionMarkerEntity
import com.example.privatenotes.data.local.NoteEntity
import com.example.privatenotes.data.local.NoteWithTags
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** Room-backed [NoteRepository]. */
class RoomNoteRepository(
    private val database: AppDatabase,
    private val timeSource: TimeSource = TimeSource.System,
) : NoteRepository {

    private val noteDao = database.noteDao()
    private val markerDao = database.deletionMarkerDao()

    override fun observeActiveNotes(): Flow<List<NoteEntity>> = noteDao.observeActive()

    override fun observeTrash(): Flow<List<NoteEntity>> = noteDao.observeTrash()

    override fun observeNotes(term: String, tagId: String?): Flow<List<NoteWithTags>> =
        noteDao.observeNotesWithTags(LikePattern.escape(term.trim()), tagId)

    override fun search(term: String): Flow<List<NoteEntity>> =
        noteDao.search(LikePattern.escape(term))

    override suspend fun findById(id: String): NoteEntity? = noteDao.findById(id)

    override suspend fun createNote(title: String, content: String): String {
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

    override suspend fun updateNote(id: String, title: String, content: String) {
        val existing = noteDao.findById(id)
        // The note may have been deleted while an editor still held unsaved input; a stale
        // write must not resurrect it.
        if (existing == null || existing.deletedAt != null) return

        // Strictly increasing timestamps keep "newest edit wins" meaningful for §9.5 merges,
        // even when the device clock steps backwards.
        val updatedAt = maxOf(timeSource.nowMillis(), existing.updatedAt + 1)

        noteDao.update(existing.copy(title = title, content = content, updatedAt = updatedAt))
    }

    override suspend fun moveToTrash(id: String) {
        noteDao.moveToTrash(id, updatedTimestampFor(id))
    }

    override suspend fun restoreFromTrash(id: String) {
        noteDao.restoreFromTrash(id, updatedTimestampFor(id))
    }

    override suspend fun permanentlyDelete(id: String) {
        database.withTransaction {
            val note = noteDao.findById(id) ?: return@withTransaction
            if (note.deletedAt == null) {
                throw IllegalStateException("Only a trashed note can be permanently deleted")
            }
            markerDao.insert(
                DeletionMarkerEntity(
                    noteId = note.id,
                    deletedAt = note.deletedAt,
                    lastKnownUpdatedAt = note.updatedAt,
                    schemaVersion = AppDatabase.SCHEMA_VERSION,
                ),
            )
            noteDao.deleteById(id)
        }
    }

    override suspend fun purgeExpiredTrash(nowMillis: Long): Int {
        val cutoff = nowMillis - NoteRepository.TRASH_RETENTION_MILLIS
        var purged = 0
        database.withTransaction {
            val expired = noteDao.findExpiredTrash(cutoff)
            for (note in expired) {
                val deletedAt = note.deletedAt ?: continue
                markerDao.insert(
                    DeletionMarkerEntity(
                        noteId = note.id,
                        deletedAt = deletedAt,
                        lastKnownUpdatedAt = note.updatedAt,
                        schemaVersion = AppDatabase.SCHEMA_VERSION,
                    ),
                )
                noteDao.deleteById(note.id)
                purged++
            }
        }
        return purged
    }

    override suspend fun activeNoteCount(): Int = noteDao.countActive()

    private suspend fun updatedTimestampFor(id: String): Long {
        val existing = noteDao.findById(id)
        val previous = existing?.updatedAt ?: 0L
        return maxOf(timeSource.nowMillis(), previous + 1)
    }
}
