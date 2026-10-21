package com.lyrnox.opennotes.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single note.
 *
 * Timestamps are epoch milliseconds (UTC) as `Long`: no timezone ambiguity in storage,
 * no converter to get wrong. Deletion is soft ([deletedAt]) so a future trash/undo flow
 * does not need a schema change.
 */
@Entity(
    tableName = "notes",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("folderId"),
        Index("updatedAt"),
        Index("deletedAt"),
        Index("pinned"),
    ],
)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "content")
    val content: String,

    @ColumnInfo(name = "folderId")
    val folderId: Long? = null,

    @ColumnInfo(name = "pinned", defaultValue = "0")
    val pinned: Boolean = false,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long,

    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long,

    @ColumnInfo(name = "deletedAt")
    val deletedAt: Long? = null,
)
