package com.example.privatenotes.data.backup

import androidx.room.withTransaction
import com.example.privatenotes.data.local.AppDatabase
import com.example.privatenotes.data.local.DeletionMarkerEntity
import com.example.privatenotes.data.local.NoteEntity
import com.example.privatenotes.data.local.NoteTagCrossRef
import com.example.privatenotes.data.local.TagEntity
import com.example.privatenotes.data.repository.TimeSource

/**
 * A consistent copy of everything a backup contains (§9.1).
 *
 * Notes include the trash on purpose: a backup that silently dropped the trash would make the
 * 30-day safety net useless right when it matters.
 */
data class BackupSnapshot(
    val notes: List<NoteEntity>,
    val tags: List<TagEntity>,
    val noteTags: List<NoteTagCrossRef>,
    val deletionMarkers: List<DeletionMarkerEntity>,
) {
    fun toDocument(exportedAt: Long, appVersion: String): BackupDocument = BackupDocument(
        format = BackupDocument.FORMAT,
        schemaVersion = BackupDocument.CURRENT_SCHEMA_VERSION,
        appVersion = appVersion,
        exportedAt = exportedAt,
        notes = notes.sortedWith(compareBy({ it.createdAt }, { it.id })).map {
            BackupNote(
                id = it.id,
                title = it.title,
                content = it.content,
                createdAt = it.createdAt,
                updatedAt = it.updatedAt,
                deletedAt = it.deletedAt,
            )
        },
        tags = tags.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }).map {
            BackupTag(id = it.id, name = it.name, createdAt = it.createdAt, updatedAt = it.updatedAt)
        },
        noteTags = noteTags.sortedWith(compareBy({ it.noteId }, { it.tagId })).map {
            BackupNoteTag(noteId = it.noteId, tagId = it.tagId)
        },
        deletionMarkers = deletionMarkers.sortedWith(compareBy({ it.deletedAt }, { it.noteId })).map {
            BackupDeletionMarker(
                noteId = it.noteId,
                deletedAt = it.deletedAt,
                lastKnownUpdatedAt = it.lastKnownUpdatedAt,
                schemaVersion = it.schemaVersion,
            )
        },
    )
}

/**
 * Reads a snapshot inside one Room transaction.
 *
 * Four separate queries outside a transaction could catch a write between them and produce a
 * file whose tag references no longer resolve. The transaction makes the exported rows point
 * at each other.
 */
class BackupSnapshotReader(
    /**
     * A provider, not an instance: constructing this object must never open the database.
     * The backup scheduler is touched on every foreground start, and §6.2 closes the database
     * when the app locks - a service that opened it merely by being constructed would reconnect
     * behind the lock screen.
     */
    private val databaseProvider: () -> AppDatabase,
    private val timeSource: TimeSource,
) {

    constructor(database: AppDatabase, timeSource: TimeSource) : this({ database }, timeSource)

    suspend fun read(appVersion: String): BackupDocument {
        val database = databaseProvider()
        return database.withTransaction {
            val dao = database.backupDao()
            val snapshot = BackupSnapshot(
                notes = dao.allNotes(),
                tags = dao.allTags(),
                noteTags = dao.allNoteTags(),
                deletionMarkers = dao.allDeletionMarkers(),
            )
            snapshot.toDocument(exportedAt = timeSource.nowMillis(), appVersion = appVersion)
        }
    }
}
