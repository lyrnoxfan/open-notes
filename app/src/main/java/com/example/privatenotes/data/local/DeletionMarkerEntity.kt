package com.example.privatenotes.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A tombstone for a note that was permanently deleted (§5.3).
 *
 * Without these, importing an older backup would resurrect notes the user deliberately
 * removed. The marker carries no title or body — after permanent deletion the text is gone —
 * only the identity and the version facts a merge has to compare.
 *
 * Markers are intentionally kept far longer than the 30-day trash window; §8 requires them to
 * outlive any backup the user might restore.
 */
@Entity(
    tableName = "deletion_markers",
    indices = [Index("deletedAt")],
)
data class DeletionMarkerEntity(
    @PrimaryKey
    @ColumnInfo(name = "noteId")
    val noteId: String,

    /** When the note was permanently deleted (UTC epoch millis). */
    @ColumnInfo(name = "deletedAt")
    val deletedAt: Long,

    /** The note's `updatedAt` at deletion time, for merge comparisons against old backups. */
    @ColumnInfo(name = "lastKnownUpdatedAt")
    val lastKnownUpdatedAt: Long,

    /** Marker format version. Independent of Room schema changes so backups stay compatible. */
    @ColumnInfo(name = "schemaVersion")
    val schemaVersion: Int,
) {
    companion object {
        const val CURRENT_FORMAT_VERSION = 2
    }
}
