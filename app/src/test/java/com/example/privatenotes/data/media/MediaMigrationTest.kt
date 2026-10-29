package com.example.privatenotes.data.media

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.privatenotes.data.local.AppDatabase
import com.example.privatenotes.data.local.migrations.Migrations
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaMigrationTest {
    @Test fun v1DatabaseUpgradesWithoutLosingNotesTagsOrMarkers() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "media-migration-test.db"
        context.deleteDatabase(name)
        val oldSchema = JSONObject(File("schemas/com.example.privatenotes.data.local.AppDatabase/1.json").readText()).getJSONObject("database")
        val old = context.openOrCreateDatabase(name, 0, null)
        try {
            val entities = oldSchema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indexes = entity.getJSONArray("indices")
                for (j in 0 until indexes.length()) old.execSQL(indexes.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            old.execSQL("INSERT INTO notes VALUES ('old','标题','旧正文',1,2,NULL)")
            old.execSQL("INSERT INTO tags VALUES ('tag','标签',1,2)")
            old.execSQL("INSERT INTO note_tag_refs VALUES ('old','tag')")
            old.execSQL("INSERT INTO deletion_markers VALUES ('gone',3,2,1)")
            old.version = 1
        } finally { old.close() }
        val upgraded = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*Migrations.ALL).allowMainThreadQueries().build()
        try {
            assertEquals("旧正文", upgraded.noteDao().findById("old")!!.content)
            assertEquals(1, upgraded.backupDao().allTags().size)
            assertEquals(1, upgraded.backupDao().allNoteTags().size)
            assertEquals(1, upgraded.backupDao().allDeletionMarkers().size)
            assertTrue(upgraded.mediaDao().all().isEmpty())
            assertEquals(2, upgraded.openHelper.writableDatabase.version)
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }
}
