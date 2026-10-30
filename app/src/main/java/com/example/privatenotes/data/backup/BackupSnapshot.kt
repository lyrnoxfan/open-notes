package com.example.privatenotes.data.backup

import androidx.room.withTransaction
import java.io.InputStream
import java.io.Writer
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
    val media: Map<String, List<com.example.privatenotes.data.media.BackupMedia>> = emptyMap(),
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
                media = media[it.id].orEmpty(),
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

    /**
     * Stream the schema-2 legacy JSON snapshot while the same Room read transaction is active.
     * Only note/table metadata and one 256 KiB media chunk are resident at a time; media is
     * fetched from the chunk cursor and never converted to a Base64 String in memory.
     */
    suspend fun writeStreaming(appVersion: String, json: BackupJson, writer: Writer): Long {
        val database = databaseProvider()
        return database.withTransaction {
            val dao = database.backupDao()
            val mediaDao = database.mediaDao()
            val allMedia = mediaDao.all()
            val snapshot = BackupSnapshot(
                notes = dao.allNotes(),
                tags = dao.allTags(),
                noteTags = dao.allNoteTags(),
                deletionMarkers = dao.allDeletionMarkers(),
                media = allMedia.groupBy { it.noteId }.mapValues { (_, list) ->
                    list.map { com.example.privatenotes.data.media.BackupMedia(
                        it.id, it.name, it.mime, it.size, it.sha256, "",
                    ) }
                },
            )
            val exportedAt = timeSource.nowMillis()
            val document = snapshot.toDocument(exportedAt, appVersion)
            val byId = allMedia.associateBy { it.id }
            json.writeStreaming(document, writer) { media ->
                val entity = checkNotNull(byId[media.id]) { "Snapshot media disappeared" }
                CursorMediaInputStream(mediaDao.chunkCursor(entity.id), entity.size)
            }
            exportedAt
        }
    }

    private class CursorMediaInputStream(
        private val cursor: android.database.Cursor,
        private val expectedSize: Long,
    ) : InputStream() {
        private var chunk = ByteArray(0)
        private var chunkOffset = 0
        private var nextPosition = 0
        private var totalRead = 0L
        private var eofChecked = false
        private var closed = false

        override fun read(): Int {
            val one = ByteArray(1)
            val count = read(one, 0, 1)
            return if (count < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            check(!closed) { "Media stream is closed" }
            require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
            if (length == 0) return 0
            if (chunkOffset == chunk.size && !loadChunk()) return -1
            val count = minOf(length, chunk.size - chunkOffset)
            chunk.copyInto(buffer, offset, chunkOffset, chunkOffset + count)
            chunkOffset += count
            totalRead += count
            return count
        }

        private fun loadChunk(): Boolean {
            if (totalRead == expectedSize) {
                if (!eofChecked) {
                    eofChecked = true
                    check(!cursor.moveToNext()) { "Unexpected trailing media chunks" }
                }
                return false
            }
            check(totalRead < expectedSize) { "Media exceeds recorded length" }
            check(cursor.moveToNext()) { "Media chunks ended before recorded length" }
            val position = cursor.getInt(0)
            check(position == nextPosition) { "Media chunk sequence is corrupt" }
            chunk = cursor.getBlob(1)
            val expected = minOf(
                com.example.privatenotes.data.media.MediaRules.CHUNK_SIZE.toLong(),
                expectedSize - totalRead,
            ).toInt()
            check(chunk.size == expected) { "Media chunk length is corrupt" }
            nextPosition++
            chunkOffset = 0
            return true
        }

        override fun close() {
            if (!closed) { closed = true; cursor.close() }
        }
    }

    suspend fun read(appVersion: String): BackupDocument {
        val database = databaseProvider()
        return database.withTransaction {
            val dao = database.backupDao()
            val allMedia = database.mediaDao().all()
            if (allMedia.any { it.size > com.example.privatenotes.data.media.MediaRules.LEGACY_MAX_BYTES } || allMedia.sumOf { it.size.toLong() } > 40L * 1024 * 1024) {
                throw BackupException(BackupErrorKind.STORAGE, "请使用“大媒体加密备份”导出此媒体库；旧 JSON/PNB 容量不足，未生成不完整备份")
            }
            val snapshot = BackupSnapshot(
                notes = dao.allNotes(),
                tags = dao.allTags(),
                noteTags = dao.allNoteTags(),
                deletionMarkers = dao.allDeletionMarkers(),
                media = allMedia.groupBy { it.noteId }.mapValues { (_, list) ->
                    list.map { com.example.privatenotes.data.media.MediaRules.encode(it,
                        com.example.privatenotes.data.media.MediaRules.read(database.mediaDao(), it)) }
                },
            )
            snapshot.toDocument(exportedAt = timeSource.nowMillis(), appVersion = appVersion)
        }
    }
}
