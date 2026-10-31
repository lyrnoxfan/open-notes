package com.example.privatenotes

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.privatenotes.data.local.AppDatabase
import com.example.privatenotes.data.media.*
import com.example.privatenotes.data.backup.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Storage and migration tests without launching a Compose activity. */
@RunWith(AndroidJUnit4::class)
class MediaStorageDeviceTest {
    private val container get() = ApplicationProvider.getApplicationContext<PrivateNotesApp>().container
    @Test fun existingEncryptedV1DatabaseMigratesWithoutLosingText() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "media-migrate-cipher-test.db"
        context.deleteDatabase(name)
        val key = "synthetic-migration-test-passphrase".toByteArray()
        System.loadLibrary("sqlcipher")
        val schema = org.json.JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
            .open("media/schema-1.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        val helper = net.zetetic.database.sqlcipher.SupportOpenHelperFactory(key.copyOf(), null, false).create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        val entities = schema.getJSONArray("entities")
                        for (i in 0 until entities.length()) {
                            val e = entities.getJSONObject(i)
                            db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
                            val indexes = e.getJSONArray("indices")
                            for (j in 0 until indexes.length()) db.execSQL(indexes.getJSONObject(j).getString("createSql")
                                .replace("\${TABLE_NAME}", e.getString("tableName")))
                        }
                        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42,?)", arrayOf(schema.getString("identityHash")))
                        db.execSQL("INSERT INTO notes VALUES ('legacy','old','MEDIA-MIGRATION-PRIVATE-CANARY',1,2,NULL)")
                    }
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("unexpected")
                }).build())
        helper.writableDatabase.close(); helper.close()
        val upgraded = com.example.privatenotes.data.local.EncryptedDatabaseProvider.openWithPassphrase(context, key, name)
        try {
            assertEquals("MEDIA-MIGRATION-PRIVATE-CANARY", upgraded.noteDao().findById("legacy")!!.content)
            assertTrue(upgraded.mediaDao().all().isEmpty())
            assertEquals(AppDatabase.SCHEMA_VERSION, upgraded.openHelper.writableDatabase.version)
            assertFalse(context.getDatabasePath(name).readBytes().toString(Charsets.ISO_8859_1).contains("MEDIA-MIGRATION-PRIVATE-CANARY"))
        } finally { upgraded.close(); context.deleteDatabase(name); key.fill(0) }
    }
    @Test fun encryptedV2MediaMigratesAndLaterOpensWithoutFullPayloadScan() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "media-v2-cipher-test.db"
        context.deleteDatabase(name)
        val key = "synthetic-v2-test-passphrase".toByteArray()
        System.loadLibrary("sqlcipher")
        val schema = org.json.JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
            .open("media/schema-2.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        val bytes = ByteArray(3 * MediaRules.CHUNK_SIZE) { (it % 251).toByte() }
        "ftyp".toByteArray().copyInto(bytes, 4)
        val digest = MediaRules.digest(bytes)
        val helper = net.zetetic.database.sqlcipher.SupportOpenHelperFactory(key.copyOf(), null, false).create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        val entities = schema.getJSONArray("entities")
                        for (i in 0 until entities.length()) {
                            val e = entities.getJSONObject(i)
                            db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
                            val indexes = e.getJSONArray("indices")
                            for (j in 0 until indexes.length()) db.execSQL(indexes.getJSONObject(j).getString("createSql")
                                .replace("\${TABLE_NAME}", e.getString("tableName")))
                        }
                        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                        db.execSQL("INSERT INTO room_master_table VALUES(42,?)", arrayOf(schema.getString("identityHash")))
                        db.execSQL("INSERT INTO notes VALUES ('n','old','preserved body',1,2,NULL)")
                        db.execSQL("INSERT INTO note_media VALUES ('m','n','old.mp4','video/mp4',?,?)", arrayOf(bytes.size, digest))
                        repeat(3) { pos -> db.execSQL("INSERT INTO media_chunks VALUES ('m',?,?)",
                            arrayOf(pos, bytes.copyOfRange(pos * MediaRules.CHUNK_SIZE, (pos + 1) * MediaRules.CHUNK_SIZE))) }
                    }
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("unexpected")
                }).build())
        helper.writableDatabase.close(); helper.close()
        val upgraded = com.example.privatenotes.data.local.EncryptedDatabaseProvider.openWithPassphrase(context, key, name)
        try {
            val dao = upgraded.mediaDao()
            val item = dao.all().single()
            val repo = MediaRepository(upgraded, context, com.example.privatenotes.data.repository.TimeSource { 1000L })
            assertEquals(3, dao.unhashedCount(item.id))
            repo.videoSource(item).use { source ->
                val actual = ByteArray(bytes.size)
                var offset = 0
                while (offset < actual.size) offset += source.readAt(offset.toLong(), actual, offset, actual.size - offset)
                assertArrayEquals(bytes, actual)
            }
            assertEquals(0, dao.unhashedCount(item.id))
            assertEquals("preserved body", upgraded.noteDao().findById("n")!!.content)
            // A damaged unrequested tail must not be scanned when opening or reading the head.
            // This checks the performance-critical access pattern without flaky wall-clock limits.
            upgraded.openHelper.writableDatabase.execSQL("UPDATE media_chunks SET bytes=zeroblob(?) WHERE mediaId='m' AND position=2", arrayOf(MediaRules.CHUNK_SIZE))
            repo.videoSource(item).use { source ->
                val head = ByteArray(16)
                assertEquals(16, source.readAt(0, head, 0, 16))
                assertArrayEquals(bytes.copyOfRange(0, 16), head)
                try { source.readAt((2 * MediaRules.CHUNK_SIZE).toLong(), head, 0, 16); fail("tail corruption must be rejected") }
                catch (_: java.io.IOException) { }
            }
        } finally { upgraded.close(); context.deleteDatabase(name); key.fill(0); bytes.fill(0) }
    }
    @Test fun streamedTwelveMiBMediaArchiveAndInterruptedImportRollBackOnSqlCipher() = runBlocking {
        fun stage(name: String) = android.util.Log.i("MediaStorageStage", name)
        stage("create note")
        val id = container.noteRepository.createNote("stream capacity", "private")
        val repo = container.mediaRepository
        val bytes = ByteArray(12*1024*1024) { (it % 251).toByte() }
        "ftyp".toByteArray().copyInto(bytes,4)
        stage("import")
        repo.importStream(id,"capacity.mp4","video/mp4",bytes.inputStream())
        stage("import complete")
        val original = repo.list(id).single()
        assertEquals(bytes.size.toLong(), original.size)
        val file = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"synthetic-capacity.pnm")
        try {
            stage("backup write")
            file.outputStream().use { StreamingMediaArchive(container.database).write(it,"test-password".toCharArray(),"test",1000) }
            stage("backup complete; clearing")
            container.database.clearAllTables()
            stage("restore")
            file.inputStream().use { StreamingMediaArchive(container.database).restore(it,"test-password".toCharArray()) }
            stage("restore complete")
            assertEquals(original, repo.list(id).single())
            repo.videoSource(original).close()
            java.io.RandomAccessFile(file,"rw").use { it.setLength(it.length()-10) }
            val before = container.database.backupDao().allNotes()
            stage("truncated restore")
            try { file.inputStream().use { StreamingMediaArchive(container.database).restore(it,"test-password".toCharArray()) }; fail("truncated archive") }
            catch (_: Exception) {}
            stage("truncated restore finished")
            assertEquals(before,container.database.backupDao().allNotes())
            assertEquals(original, repo.list(id).single())
        } finally { file.delete(); bytes.fill(0) }
    }
}
