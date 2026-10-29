package com.example.privatenotes

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.privatenotes.data.media.*
import com.example.privatenotes.data.backup.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

/** Isolated synthetic install only. SQLCipher, real bitmap decoder, real MediaPlayer and UI. */
@ScriptDriven
class MediaDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val container get() = ApplicationProvider.getApplicationContext<PrivateNotesApp>().container
    private fun payload(id: String, mime: String, bytes: ByteArray) = BackupMedia(id,
        if (mime.startsWith("image")) "测试.png" else "测试.mp4", mime, bytes.size,
        MediaRules.digest(bytes), Base64.getEncoder().encodeToString(bytes))

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
            assertEquals(2, upgraded.openHelper.writableDatabase.version)
            assertFalse(context.getDatabasePath(name).readBytes().toString(Charsets.ISO_8859_1).contains("MEDIA-MIGRATION-PRIVATE-CANARY"))
        } finally { upgraded.close(); context.deleteDatabase(name); key.fill(0) }
    }
    @Test fun streamedTwelveMiBMediaArchiveAndInterruptedImportRollBackOnSqlCipher() = runBlocking {
        val id = container.noteRepository.createNote("stream capacity", "private")
        val repo = container.mediaRepository
        val bytes = ByteArray(12*1024*1024) { (it % 251).toByte() }
        "ftyp".toByteArray().copyInto(bytes,4)
        repo.importStream(id,"capacity.mp4","video/mp4",bytes.inputStream())
        val original = repo.list(id).single()
        assertEquals(bytes.size, original.size)
        val file = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"synthetic-capacity.pnm")
        try {
            file.outputStream().use { StreamingMediaArchive(container.database).write(it,"test-password".toCharArray(),"test",1000) }
            container.database.clearAllTables()
            file.inputStream().use { StreamingMediaArchive(container.database).restore(it,"test-password".toCharArray()) }
            assertEquals(original, repo.list(id).single())
            repo.videoSource(original).close()
            java.io.RandomAccessFile(file,"rw").use { it.setLength(it.length()-10) }
            val before = container.database.backupDao().allNotes()
            try { file.inputStream().use { StreamingMediaArchive(container.database).restore(it,"test-password".toCharArray()) }; fail("truncated archive") }
            catch (_: Exception) {}
            assertEquals(before,container.database.backupDao().allNotes())
            assertEquals(original, repo.list(id).single())
        } finally { file.delete(); bytes.fill(0) }
    }
    @Test fun encryptedStorageBackupRestoresAndRealViewersOpen() {
        val image = ByteArrayOutputStream().also { output ->
            val bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.GREEN)
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); bitmap.recycle()
        }.toByteArray()
        val video = InstrumentationRegistry.getInstrumentation().context.assets.open("media/sample.mp4").use { it.readBytes() }
        val id = runBlocking {
            val id = container.noteRepository.createNote("附件验收", "正文")
            val repo = container.mediaRepository
            repo.add(id, payload("test-image", "image/png", image))
            repo.add(id, payload("test-video", "video/mp4", video))
            val json = BackupJson()
            val snapshot = BackupSnapshotReader(container.database, container.timeSource).read("media-test")
            val restored = json.decode(BackupCrypto.decrypt(BackupCrypto.encrypt(json.encode(snapshot), "test-password".toCharArray()), "test-password".toCharArray()))
            container.database.clearAllTables()
            BackupMerger(container.database, json).merge(restored)
            val values = repo.list(id)
            assertEquals(2, values.size)
            assertArrayEquals(image, repo.bytes(values.first { it.id == "test-image" }))
            assertArrayEquals(video, repo.bytes(values.first { it.id == "test-video" }))
            id
        }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("note_row_$id").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("note_row_$id").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("media_test-image").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("media_test-image").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("media_image").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("media_image").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("media_test-video").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithTag("media_play_pause").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("media_play_pause").performClick()
        val progressed = runCatching {
            compose.waitUntil(20000) {
                compose.onAllNodesWithTag("media_position").fetchSemanticsNodes().firstOrNull()
                    ?.config?.get(SemanticsProperties.Text)?.joinToString { it.text }?.startsWith("1 /") == true
            }
        }.isSuccess
        val position = compose.onAllNodesWithTag("media_position").fetchSemanticsNodes().firstOrNull()
            ?.config?.get(SemanticsProperties.Text)?.joinToString { it.text } ?: "缺少播放进度控件"
        assertTrue("MediaPlayer 未播放到 1 秒，当前进度：$position", progressed)
        compose.onNodeWithText("关闭").performClick()
        runBlocking {
            container.noteRepository.moveToTrash(id)
            assertEquals(2, container.mediaRepository.list(id).size)
            container.noteRepository.restoreFromTrash(id)
            assertEquals(2, container.mediaRepository.list(id).size)
            container.noteRepository.deletePermanentlyAll(listOf(id))
            assertTrue(container.mediaRepository.list(id).isEmpty())
            assertTrue(container.database.mediaDao().chunks("test-video").isEmpty())
        }
    }
}
