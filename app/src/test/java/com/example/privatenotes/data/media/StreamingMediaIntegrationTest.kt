package com.example.privatenotes.data.media

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.privatenotes.data.local.AppDatabase
import com.example.privatenotes.data.repository.*
import com.example.privatenotes.data.backup.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.*

/** Synthetic capacity bytes, not a claim that arbitrary filler is a playable MP4. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StreamingMediaIntegrationTest {
    private lateinit var db: AppDatabase
    private lateinit var notes: RoomNoteRepository
    private lateinit var media: MediaRepository
    private val clock = TimeSource { 1000L }
    @Before fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        notes = RoomNoteRepository(db, clock); media = MediaRepository(db, context, clock)
    }
    @After fun close() { db.close() }
    private class Generated(private val length: Int, private val failAt: Int = Int.MAX_VALUE) : InputStream() {
        var at = 0
        override fun read(): Int { val a = ByteArray(1); return if (read(a,0,1)<0) -1 else a[0].toInt() and 255 }
        override fun read(b: ByteArray, offset: Int, count: Int): Int {
            if (at >= failAt) throw IOException("synthetic read failure")
            if (at >= length) return -1
            val n = minOf(count, length-at, failAt-at)
            for (i in 0 until n) b[offset+i] = when(val p=at+i) {
                in 4..7 -> "ftyp"[p-4].code.toByte()
                else -> (p % 251).toByte()
            }
            at += n; return n
        }
    }
    @Test fun fortyEightMiBVideoStreamsThroughEncryptedArchiveAndRestoresSameHash() = runBlocking {
        val id = notes.createNote("capacity-canary", "private-body-canary")
        media.importStream(id, "48MiB.mp4", "video/mp4", Generated(48*1024*1024))
        val entity = media.list(id).single()
        assertEquals(48*1024*1024, entity.size)
        assertEquals(192, db.mediaDao().chunks(entity.id).size)
        val source = media.videoSource(entity)
        val head = ByteArray(12); assertEquals(12, source.readAt(0, head, 0, 12)); source.close()
        assertEquals("ftyp", String(head,4,4))
        try { BackupSnapshotReader(db, clock).read("test"); fail("legacy backup must not silently omit large video") }
        catch (_: BackupException) {}
        val file = File.createTempFile("media-capacity", ".pnm")
        try {
            file.outputStream().use { StreamingMediaArchive(db).write(it, "test-pass".toCharArray(), "test", 1000) }
            assertTrue(file.length() > 48L*1024*1024)
            // No whole-archive buffer is used by export/restore.
            db.clearAllTables()
            file.inputStream().use { StreamingMediaArchive(db).restore(it, "test-pass".toCharArray()) }
            assertEquals("private-body-canary", notes.findById(id)!!.content)
            val restored = media.list(id).single()
            assertEquals(entity, restored)
            media.videoSource(restored).close() // streams SHA verification over every restored byte
            val before = db.backupDao().allNotes()
            RandomAccessFile(file, "rw").use { it.setLength(it.length()-7) }
            try { file.inputStream().use { StreamingMediaArchive(db).restore(it, "test-pass".toCharArray()) }; fail("truncation") }
            catch (_: Exception) {}
            assertEquals(before, db.backupDao().allNotes())
            assertEquals(entity, media.list(id).single())
        } finally { file.delete() }
    }
    @Test fun failedImportAndOverLimitImageRollBackChunksMetadataAndNoteTime() = runBlocking {
        val id = notes.createNote("n", "body")
        val before = notes.findById(id)
        try { media.importStream(id, "broken.mp4", "video/mp4", Generated(16*1024*1024,9*1024*1024)); fail("read failure") }
        catch (_: IOException) {}
        assertEquals(before, notes.findById(id)); assertTrue(db.mediaDao().all().isEmpty())
        val image = object : InputStream() {
            var at=0
            val signature=byteArrayOf(-119,80,78,71,13,10,26,10)
            override fun read(): Int = if(at>MediaRules.LEGACY_MAX_BYTES) -1 else (if(at<8) signature[at++].toInt() and 255 else {at++; 1})
        }
        try { media.importStream(id,"large.png","image/png",image); fail("limit") }
        catch (_: IllegalArgumentException) {}
        assertEquals(before, notes.findById(id)); assertTrue(db.mediaDao().all().isEmpty())
        assertEquals(0L, db.openHelper.readableDatabase.query("SELECT count(*) FROM media_chunks").use { it.moveToFirst();it.getLong(0) })
    }
    @Test fun authenticatedArchiveRejectsWrongPasswordAndTruncatedPayloadBeforeCommit() = runBlocking {
        val id = notes.createNote("restored", "original")
        media.importStream(id,"tiny.mp4","video/mp4",Generated(700000))
        val out = ByteArrayOutputStream()
        StreamingMediaArchive(db).write(out,"correct-pass".toCharArray(),"test",1000)
        val bytes = out.toByteArray()
        db.clearAllTables(); val local = notes.createNote("local", "keep")
        for (bad in listOf(bytes.copyOf(bytes.size-30), bytes.copyOf().also { it[it.size/2]=(it[it.size/2].toInt() xor 1).toByte() }, bytes+byteArrayOf(1))) {
            try { StreamingMediaArchive(db).restore(bad.inputStream(),"correct-pass".toCharArray()); fail("must reject") }
            catch (_: Exception) {}
            assertNull(notes.findById(id)); assertNotNull(notes.findById(local)); assertTrue(db.mediaDao().all().isEmpty())
        }
        try { StreamingMediaArchive(db).restore(bytes.inputStream(),"wrong-pass".toCharArray()); fail("password") }
        catch (_: Exception) {}
        assertEquals(listOf(local), db.backupDao().allNotes().map { it.id })
    }
    @Test fun cancelledStreamRollsBackAndFailedExportDoesNotChangeDatabase() = runBlocking {
        val id = notes.createNote("failure-canary", "keep-private")
        val before = notes.findById(id)
        val cancelled = object : InputStream() {
            private val source = Generated(2*1024*1024)
            override fun read(): Int = error("bulk reads only")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (source.at >= MediaRules.CHUNK_SIZE) throw kotlinx.coroutines.CancellationException("synthetic cancel")
                return source.read(b,off,len)
            }
        }
        try { media.importStream(id,"cancel.mp4","video/mp4",cancelled); fail("cancel") }
        catch (_: kotlinx.coroutines.CancellationException) {}
        assertEquals(before,notes.findById(id)); assertTrue(db.mediaDao().all().isEmpty())
        assertEquals(0L, db.openHelper.readableDatabase.query("SELECT count(*) FROM media_chunks").use { it.moveToFirst();it.getLong(0) })
        media.importStream(id,"kept.mp4","video/mp4",Generated(2*1024*1024))
        val original = media.list(id).single()
        val partial = ByteArrayOutputStream()
        val noSpace = object : OutputStream() {
            override fun write(b: Int) {
                if(partial.size() >= 600000) throw IOException("synthetic no-space")
                partial.write(b)
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                if(partial.size()+len > 600000) throw IOException("synthetic no-space")
                partial.write(b,off,len)
            }
        }
        try { StreamingMediaArchive(db).write(noSpace,"test-password".toCharArray(),"test",1000); fail("output failure") }
        catch (_: IOException) {}
        assertFalse(partial.toByteArray().toString(Charsets.ISO_8859_1).contains("keep-private"))
        assertEquals(original,media.list(id).single())
        media.videoSource(original).close()
        // An incomplete output is not restorable and cannot alter local content.
        try { StreamingMediaArchive(db).restore(partial.toByteArray().inputStream(),"test-password".toCharArray()); fail("partial export") }
        catch (_: Exception) {}
        assertEquals(original,media.list(id).single())
        assertEquals("keep-private",notes.findById(id)!!.content)
    }

}
