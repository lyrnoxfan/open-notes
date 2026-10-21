package com.example.privatenotes.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A note (§5.1).
 *
 * [id] is a UUID string, not an auto-increment integer: notes get exported to JSON backups,
 * imported back, and merged by identity, so the identifier has to survive outside this
 * database.
 *
 * Timestamps are UTC epoch **milliseconds**. Seconds would collide: §9.5 resolves import
 * conflicts by comparing `updatedAt`, and two edits within the same second are indistinguishable
 * at second resolution.
 */
@Entity(
    tableName = "notes",
    indices = [
        Index("updatedAt"),
        Index("deletedAt"),
    ],
)
data class NoteEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "content")
    val content: String,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long,

    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long,

    @ColumnInfo(name = "deletedAt")
    val deletedAt: Long? = null,
)
