package com.lyrnox.opennotes.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.lyrnox.opennotes.data.local.dao.FolderDao
import com.lyrnox.opennotes.data.local.dao.NoteDao
import com.lyrnox.opennotes.data.local.dao.TagDao
import com.lyrnox.opennotes.data.local.entity.FolderEntity
import com.lyrnox.opennotes.data.local.entity.NoteEntity
import com.lyrnox.opennotes.data.local.entity.NoteTagCrossRef
import com.lyrnox.opennotes.data.local.entity.TagEntity

/**
 * The app's single source of truth.
 *
 * Instances must be produced by [EncryptedDatabaseProvider]: it is the only place that
 * attaches the SQLCipher factory, so no plaintext database can be created by accident.
 */
@Database(
    entities = [
        NoteEntity::class,
        FolderEntity::class,
        TagEntity::class,
        NoteTagCrossRef::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class OpenNotesDatabase : RoomDatabase() {

    abstract fun noteDao(): NoteDao
    abstract fun folderDao(): FolderDao
    abstract fun tagDao(): TagDao

    companion object {
        const val NAME = "open_notes.db"
    }
}
