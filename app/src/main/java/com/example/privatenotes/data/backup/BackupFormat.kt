package com.example.privatenotes.data.backup

import kotlinx.serialization.Serializable

/**
 * On-disk backup format (§9.1).
 *
 * The format identifier and the schema version are separate from the database version. A backup
 * written by a newer app can carry fields this build does not know about, and the importer must
 * say so instead of guessing; keeping its own version makes that check unambiguous.
 */
@Serializable
data class BackupDocument(
    val format: String,
    val schemaVersion: Int,
    val appVersion: String,
    val exportedAt: Long,
    val notes: List<BackupNote>,
    val tags: List<BackupTag>,
    val noteTags: List<BackupNoteTag>,
    val deletionMarkers: List<BackupDeletionMarker>,
) {
    companion object {
        const val FORMAT = "private-notes-backup"

        /** Version of the backup envelope, not of the Room schema. */
        const val CURRENT_SCHEMA_VERSION = 2

        val SUPPORTED_SCHEMA_VERSIONS: Set<Int> = setOf(1, CURRENT_SCHEMA_VERSION)
    }
}

@Serializable
data class BackupNote(
    val id: String,
    val title: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val media: List<com.example.privatenotes.data.media.BackupMedia> = emptyList(),
)

@Serializable
data class BackupTag(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class BackupNoteTag(
    val noteId: String,
    val tagId: String,
)

/**
 * A tombstone. It carries no title or body: after a permanent deletion the text is gone, and a
 * backup must not reintroduce it just because an older file still contains it (§5.3, §9.5).
 */
@Serializable
data class BackupDeletionMarker(
    val noteId: String,
    val deletedAt: Long,
    val lastKnownUpdatedAt: Long,
    val schemaVersion: Int = BackupDocument.CURRENT_SCHEMA_VERSION,
)
