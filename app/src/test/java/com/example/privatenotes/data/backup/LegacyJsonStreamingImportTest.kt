package com.example.privatenotes.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.privatenotes.data.local.AppDatabase
import com.example.privatenotes.data.local.NoteEntity
import com.example.privatenotes.data.media.BackupMedia
import com.example.privatenotes.data.media.MediaRules
import com.example.privatenotes.data.media.NoteMediaEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.FileOutputStream
import java.io.OutputStreamWriter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyJsonStreamingImportTest {
    private lateinit var db: AppDatabase
    private lateinit var dir: File
    private val json = BackupJson()

    @Before fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        dir = File(context.cacheDir, "legacy-import-test-${System.nanoTime()}").apply { mkdirs() }
    }
    @After fun tearDown() { db.close(); dir.deleteRecursively() }

    @Test fun `legacy schema 2 import streams one media larger than 8 MiB into chunks`() = runBlocking {
        val bytes = jpeg(9 * 1024 * 1024)
        val metadata = BackupMedia("media_1", "large.jpg", "image/jpeg", bytes.size.toLong(), MediaRules.digest(bytes), "")
        val doc = backupDocument(notes = listOf(backupNote("large", title = "large").copy(media = listOf(metadata))))
        val file = File(dir, "backup.json")
        FileOutputStream(file).use { out -> OutputStreamWriter(out, Charsets.UTF_8).use { json.writeStreaming(doc, it) { bytes.inputStream() } } }

        LegacyJsonMediaStager().stage(FileInputStream(file), json, dir).use { staged ->
            assertEquals(bytes.size.toLong(), staged.media.single().decodedBytes)
            val report = BackupMerger(db, json).mergeStaged(staged)
            assertEquals(1, report.insertedNotes)
            val entity = db.mediaDao().forNote("large").single()
            assertEquals(bytes.size.toLong(), entity.size)
            val chunks = db.mediaDao().chunks(entity.id)
            assertEquals(MediaRules.chunkCount(entity.size), chunks.size)
            assertEquals(bytes.size.toLong(), chunks.sumOf { it.bytes.size.toLong() })
        }
    }

    @Test fun `invalid media is rejected before database changes`() = runBlocking {
        val broken = File(dir, "broken.json")
        broken.writeText("""{"format":"private-notes-backup","schemaVersion":2,"appVersion":"test","exportedAt":10000,"notes":[{"id":"n","title":"new","content":"body","createdAt":1,"updatedAt":2,"media":[{"id":"m","name":"bad.jpg","mime":"image/jpeg","size":9,"sha256":"${"0".repeat(64)}","base64":"not base64!"}]}],"tags":[],"noteTags":[],"deletionMarkers":[]}""")
        assertThrows(BackupException::class.java) { LegacyJsonMediaStager().stage(FileInputStream(broken), json, dir) }
        assertEquals(0, db.noteDao().countActive())
        assertEquals(0, dir.listFiles().orEmpty().count { it.name.startsWith("legacy-stage-") })
    }

    @Test fun `interrupted source read removes staged files and leaves database untouched`() = runBlocking {
        val bytes = jpeg(9 * 1024 * 1024)
        val metadata = BackupMedia("media_1", "large.jpg", "image/jpeg", bytes.size.toLong(), MediaRules.digest(bytes), "")
        val doc = backupDocument(notes = listOf(backupNote("interrupted", title = "interrupted").copy(media = listOf(metadata))))
        val file = File(dir, "valid.json")
        FileOutputStream(file).use { out -> OutputStreamWriter(out, Charsets.UTF_8).use { json.writeStreaming(doc, it) { bytes.inputStream() } } }
        val source = object : FilterInputStream(FileInputStream(file)) {
            private var consumed = 0
            override fun read(): Int {
                if (consumed >= 1024) throw IOException("simulated interrupted source")
                return super.read().also { if (it >= 0) consumed++ }
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (consumed >= 1024) throw IOException("simulated interrupted source")
                val count = super.read(b, off, minOf(len, 1024 - consumed))
                if (count > 0) consumed += count
                return count
            }
        }
        assertThrows(IOException::class.java) { LegacyJsonMediaStager().stage(source, json, dir) }
        assertEquals(0, db.noteDao().countActive())
        assertEquals(0, dir.listFiles().orEmpty().count { it.name.startsWith("legacy-stage-") })
    }

    @Test fun `database failure during staged merge rolls the note insert back`() = runBlocking {
        db.backupDao().insertNote(NoteEntity("existing", "existing", "", 1, 2))
        db.mediaDao().insert(NoteMediaEntity("duplicate", "existing", "x.jpg", "image/jpeg", 1, "0".repeat(64)))
        val bytes = jpeg(9 * 1024 * 1024)
        val meta = BackupMedia("duplicate", "large.jpg", "image/jpeg", bytes.size.toLong(), MediaRules.digest(bytes), "")
        val doc = backupDocument(notes = listOf(backupNote("new", title = "new").copy(media = listOf(meta))))
        val file = File(dir, "conflict.json")
        FileOutputStream(file).use { out -> OutputStreamWriter(out, Charsets.UTF_8).use { json.writeStreaming(doc, it) { bytes.inputStream() } } }
        LegacyJsonMediaStager().stage(FileInputStream(file), json, dir).use { staged ->
            assertNotNull(runCatching { BackupMerger(db, json).mergeStaged(staged) }.exceptionOrNull())
        }
        assertEquals(null, db.noteDao().findById("new"))
        assertNotNull(db.noteDao().findById("existing"))
    }

    private fun jpeg(size: Int): ByteArray = ByteArray(size).also { it[0] = 0xff.toByte(); it[1] = 0xd8.toByte(); it[2] = 0xff.toByte() }
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
