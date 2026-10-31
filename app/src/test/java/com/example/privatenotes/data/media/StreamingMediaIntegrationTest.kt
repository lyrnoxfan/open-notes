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
        assertEquals(48L*1024*1024, entity.size)
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
            media.videoSource(restored).use { source ->
                val data = ByteArray(MediaRules.CHUNK_SIZE)
                var offset = 0L
                while (offset < restored.size) offset += source.readAt(offset, data, 0, data.size)
            } // every requested restored chunk is checked before it reaches playback
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
        assertEquals(before, notes.findById(id)); assertTrue(db.mediaDao().all().isEmpty())
        assertEquals(0L, db.openHelper.readableDatabase.query("SELECT count(*) FROM media_chunks").use { it.moveToFirst();it.getLong(0) })
        // The streaming editor path must accept images larger than the legacy inline-JSON cap.
        val imageBytes = 9 * 1024 * 1024
        val image = object : InputStream() {
            var at=0
            val signature=byteArrayOf(-119,80,78,71,13,10,26,10)
            override fun read(): Int = if (at >= imageBytes) -1 else if (at < signature.size) signature[at++].toInt() and 255 else { at++; 1 }
        }
        media.importStream(id,"large.png","image/png",image)
        assertEquals(imageBytes.toLong(), media.list(id).single().size)
        assertEquals(36, db.mediaDao().chunks(media.list(id).single().id).size)
    }
    @Test fun moreThanHundredItemsRoundTripThroughStreamingArchive() = runBlocking {
        val id = notes.createNote("many", "body")
        repeat(101) { media.importStream(id, "item-$it.mp4", "video/mp4", Generated(16)) }
        val original = media.list(id)
        assertEquals(101, original.size)
        val out = ByteArrayOutputStream()
        StreamingMediaArchive(db).write(out, "test-pass".toCharArray(), "test", 1000)
        db.clearAllTables()
        StreamingMediaArchive(db).restore(out.toByteArray().inputStream(), "test-pass".toCharArray())
        assertEquals(original, media.list(id))
        assertEquals("body", notes.findById(id)!!.content)
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

    @Test fun automaticArchiveUsesDiskStreamForLargeMediaWithAndWithoutPassword() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val id = notes.createNote("automatic", "before-media-after")
        media.importStream(id, "48MiB.mp4", "video/mp4", Generated(48*1024*1024))
        val original = media.list(id).single()
        val service = BackupArchiveService(BackupSnapshotReader(db, clock), BackupJson(), AppVersionProvider(context),
            streamingProvider = { StreamingMediaArchive(db) }, cacheDirectory = { context.cacheDir })
        for (password in listOf(charArrayOf(), "test-pass".toCharArray())) {
            val prepared = service.prepare(password)
            val file = prepared.file
            try {
                assertTrue(file.length() > 48L*1024*1024)
                assertEquals(password.isNotEmpty(), prepared.encrypted)
                assertTrue(BackupFileNames.isBackupFile(prepared.fileName))
                val head = file.inputStream().use { input -> ByteArray(8).also { input.read(it) } }
                assertTrue(StreamingMediaArchive.isArchive(head))
                assertEquals(password.isNotEmpty(), StreamingMediaArchive.isEncryptedArchive(head))
                db.clearAllTables()
                file.inputStream().use { StreamingMediaArchive(db).restore(it, password) }
                assertEquals(original, media.list(id).single())
                assertEquals("before-media-after", notes.findById(id)!!.content)
                if (password.isEmpty()) {
                    val before = db.backupDao().allNotes()
                    RandomAccessFile(file, "rw").use { it.seek(it.length()/2); it.writeByte(17) }
                    try { file.inputStream().use { StreamingMediaArchive(db).restore(it, password) }; fail("modified media") }
                    catch (_: Exception) {}
                    assertEquals(before, db.backupDao().allNotes())
                    assertEquals(original, media.list(id).single())
                }
            } finally { prepared.close() }
            assertFalse(file.exists())
        }
    }

    @Test fun inlineMediaAndBodyCommitOrRollBackTogether() = runBlocking {
        val id = notes.createNote("original", "before-after")
        val before = notes.findById(id)
        try {
            media.importStream(id, "partial.mp4", "video/mp4", Generated(700000), "new title") {
                throw IOException("injected failure after media chunk insertion")
            }
            fail("injected import failure")
        } catch (_: IOException) {}
        assertEquals(before, notes.findById(id))
        assertTrue(media.list(id).isEmpty())
        assertEquals(0L, db.openHelper.readableDatabase.query("SELECT count(*) FROM media_chunks").use { it.moveToFirst(); it.getLong(0) })
        val mediaId = media.importStream(id, "ok.mp4", "video/mp4", Generated(700000), "new title") { "before[$it]after" }
        assertEquals("before[$mediaId]after", notes.findById(id)!!.content)
        val saved = notes.findById(id); val item = media.list(id).single()
        try {
            media.removeInline(id, mediaId, "new title") { throw IOException("injected failure after delete") }
            fail("injected removal failure")
        } catch (_: IOException) {}
        assertEquals(saved, notes.findById(id)); assertEquals(item, media.list(id).single())
        media.videoSource(item).close()
        media.removeInline(id, mediaId, "new title") { "beforeafter" }
        assertEquals("beforeafter", notes.findById(id)!!.content)
        assertTrue(media.list(id).isEmpty())
        assertEquals(0L, db.openHelper.readableDatabase.query("SELECT count(*) FROM media_chunks").use { it.moveToFirst(); it.getLong(0) })
    }

    @Test fun videoOpensWithoutReadingUnrequestedPayloadAndRejectsCorruptChunkOnRead() = runBlocking {
        val id = notes.createNote("lazy", "body")
        media.importStream(id, "local.mp4", "video/mp4", Generated(3 * MediaRules.CHUNK_SIZE))
        val item = media.list(id).single()
        assertEquals(0, db.mediaDao().unhashedCount(item.id))
        // Corrupt the last local chunk without changing its recorded hash. Opening and reading
        // the head must not scan it. Seeking to it must still detect corruption.
        db.openHelper.writableDatabase.execSQL(
            "UPDATE media_chunks SET bytes = zeroblob(?) WHERE mediaId = ? AND position = 2",
            arrayOf(MediaRules.CHUNK_SIZE, item.id))
        media.videoSource(item).use { source ->
            val header = ByteArray(12)
            assertEquals(12, source.readAt(0, header, 0, header.size))
            assertEquals("ftyp", String(header, 4, 4))
            try {
                source.readAt(2L * MediaRules.CHUNK_SIZE, header, 0, header.size)
                fail("corrupt chunk must not reach decoder")
            } catch (_: IOException) {}
        }
    }

    @Test fun legacyHashesBackfillOnlyAfterWholeFileVerificationAndRollBackOnDamage() = runBlocking {
        val id = notes.createNote("legacy", "body")
        media.importStream(id, "old.mp4", "video/mp4", Generated(2 * MediaRules.CHUNK_SIZE))
        val item = media.list(id).single()
        val sql = db.openHelper.writableDatabase
        sql.execSQL("UPDATE media_chunks SET sha256 = '' WHERE mediaId = ?", arrayOf(item.id))
        media.videoSource(item).close()
        assertEquals(0, db.mediaDao().unhashedCount(item.id))
        sql.execSQL("UPDATE media_chunks SET sha256 = '', bytes = zeroblob(?) WHERE mediaId = ? AND position = 1",
            arrayOf(MediaRules.CHUNK_SIZE, item.id))
        try { media.videoSource(item).close(); fail("damaged legacy media") }
        catch (_: IllegalStateException) {}
        assertEquals(1, db.mediaDao().unhashedCount(item.id))
    }

}
