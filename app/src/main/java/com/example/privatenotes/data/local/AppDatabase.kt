package com.example.privatenotes.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The app's single source of truth.
 *
 * Instances must come from [EncryptedDatabaseProvider]: it is the only place that attaches the
 * SQLCipher factory, so no plaintext database can be created by accident.
 */
@Database(
    entities = [
        NoteEntity::class,
        com.example.privatenotes.data.media.NoteMediaEntity::class,
        com.example.privatenotes.data.media.MediaChunkEntity::class,
        TagEntity::class,
        NoteTagCrossRef::class,
        DeletionMarkerEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    /** Installed by the encrypted provider; inspecting state must never reopen a retired handle. */
    internal var transactionState: (() -> Boolean)? = null

    override fun inTransaction(): Boolean = transactionState?.invoke() ?: super.inTransaction()

    abstract fun mediaDao(): com.example.privatenotes.data.media.MediaDao

    abstract fun noteDao(): NoteDao
    abstract fun tagDao(): TagDao
    abstract fun deletionMarkerDao(): DeletionMarkerDao

    /** Full-table access reserved for backup export and merge import. */
    abstract fun backupDao(): BackupDao

    companion object {
        const val NAME = "private_notes.db"

        /** Schema version, exposed so deletion markers can record which version made them. */
        const val SCHEMA_VERSION = 2
    }
}
