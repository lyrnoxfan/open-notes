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
        TagEntity::class,
        NoteTagCrossRef::class,
        DeletionMarkerEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun noteDao(): NoteDao
    abstract fun tagDao(): TagDao
    abstract fun deletionMarkerDao(): DeletionMarkerDao

    companion object {
        const val NAME = "private_notes.db"

        /** Schema version, exposed so deletion markers can record which version made them. */
        const val SCHEMA_VERSION = 1
    }
}
