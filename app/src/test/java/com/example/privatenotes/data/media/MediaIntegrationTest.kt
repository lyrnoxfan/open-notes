package com.example.privatenotes.data.media

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.privatenotes.data.local.AppDatabase
import com.example.privatenotes.data.repository.RoomNoteRepository
import com.example.privatenotes.data.repository.TimeSource
import com.example.privatenotes.data.backup.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaIntegrationTest {
    private lateinit var db: AppDatabase
    private lateinit var notes: RoomNoteRepository
    private lateinit var media: MediaRepository
    private val clock = TimeSource { 1000L }
    private val json = BackupJson()
    @Before fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        notes = RoomNoteRepository(db, clock)
        media = MediaRepository(db, context, clock)
    }
    @After fun close() { db.close() }
    private fun png(id: String = "image", size: Int = 700000): BackupMedia {
        val bytes = ByteArray(size) { (it % 251).toByte() }
        byteArrayOf(-119,80,78,71,13,10,26,10).copyInto(bytes)
        return BackupMedia(id, "测试.png", "image/png", bytes.size, MediaRules.digest(bytes), Base64.getEncoder().encodeToString(bytes))
    }
    @Test fun chunksAndTextSaveAndTrashRestorePreserveMedia() = runBlocking {
        val id = notes.createNote("标题", "正文")
        val value = png()
        media.add(id, value)
        assertEquals(3, db.mediaDao().chunks(value.id).size)
        assertTrue(db.mediaDao().chunks(value.id).all { it.bytes.size <= MediaRules.CHUNK_SIZE })
        notes.updateNote(id, "新标题", "新正文")
        assertEquals(value.base64, MediaRules.encode(media.list(id).single(), media.bytes(media.list(id).single())).base64)
        notes.moveToTrash(id)
        assertEquals(1, media.list(id).size)
        notes.restoreFromTrash(id)
        assertEquals(1, media.list(id).size)
        notes.deletePermanentlyAll(listOf(id))
        assertTrue(db.mediaDao().all().isEmpty())
        assertTrue(db.mediaDao().chunks(value.id).isEmpty())
    }
    @Test fun plaintextAndEncryptedBackupRestoreSameBytes() = runBlocking {
        val id = notes.createNote("图文", "正文")
        media.add(id, png())
        val snapshot = BackupSnapshotReader(db, clock).read("test")
        val encoded = json.encode(snapshot)
        val restored = json.decode(BackupCrypto.decrypt(BackupCrypto.encrypt(encoded, "test-pass".toCharArray()), "test-pass".toCharArray()))
        assertEquals(snapshot, json.decode(encoded))
        db.clearAllTables()
        BackupMerger(db, json).merge(restored)
        assertEquals(snapshot.notes.single().media.single().base64,
            MediaRules.encode(media.list(id).single(), media.bytes(media.list(id).single())).base64)
    }
    @Test fun mediaRemovalIsTimestampedAndNewerBackupReplacesMedia() = runBlocking {
        val id = notes.createNote("n", "text")
        media.add(id, png())
        val old = BackupSnapshotReader(db, clock).read("test")
        media.remove(id, "image")
        val removed = notes.findById(id)!!.updatedAt
        assertTrue(removed > old.notes.single().updatedAt)
        BackupMerger(db, json).merge(old)
        assertTrue(media.list(id).isEmpty())
        val newer = old.copy(notes = old.notes.map { it.copy(updatedAt = removed + 1) })
        BackupMerger(db, json).merge(newer)
        assertEquals(1, media.list(id).size)
    }
    @Test fun equalTimestampMediaDifferenceReportsConflict() = runBlocking {
        val id = notes.createNote("n", "text")
        media.add(id, png())
        val snapshot = BackupSnapshotReader(db, clock).read("test")
        val report = BackupMerger(db, json).merge(snapshot.copy(notes = snapshot.notes.map { it.copy(media = emptyList()) }))
        assertTrue(report.hasConflicts)
        assertEquals(1, media.list(id).size)
    }
    @Test fun invalidMediaRejectedBeforeAnyImportWrites() = runBlocking {
        val id = notes.createNote("n", "text")
        val snapshot = BackupSnapshotReader(db, clock).read("test")
        val invalid = snapshot.copy(notes = snapshot.notes.map { it.copy(title = "bad", updatedAt = 2000,
            media = listOf(png().copy(sha256 = "bad"))) })
        try { BackupMerger(db, json).merge(invalid); fail("expected rejection") }
        catch (_: BackupException) { }
        assertEquals("n", notes.findById(id)!!.title)
        assertTrue(media.list(id).isEmpty())
    }
    @Test fun newerV1BackupDoesNotEraseLocalAttachments() = runBlocking {
        val id = notes.createNote("old", "text")
        media.add(id, png())
        val snapshot = BackupSnapshotReader(db, clock).read("test")
        val legacy = snapshot.copy(schemaVersion = 1, notes = snapshot.notes.map {
            it.copy(content = "legacy edit", updatedAt = it.updatedAt + 10, media = emptyList())
        })
        BackupMerger(db, json).merge(legacy)
        assertEquals("legacy edit", notes.findById(id)!!.content)
        assertEquals(1, media.list(id).size)
        assertEquals(png().base64, MediaRules.encode(media.list(id).single(), media.bytes(media.list(id).single())).base64)
    }
    @Test fun schemaOneRemainsImportableAndCannotPretendToCarryMedia() = runBlocking {
        val id = notes.createNote("old", "text")
        val snapshot = BackupSnapshotReader(db, clock).read("test").copy(schemaVersion = 1)
        assertEquals(snapshot, json.decode(json.encode(snapshot)))
        try { json.validate(snapshot.copy(notes = snapshot.notes.map { it.copy(media = listOf(png())) })); fail("expected rejection") }
        catch (_: BackupException) { }
        assertNotNull(notes.findById(id))
    }
    @Test fun collidingMediaIdentityRollsBackEntireImport() = runBlocking {
        val one = notes.createNote("one", "one")
        val two = notes.createNote("two", "two")
        media.add(one, png())
        val snapshot = BackupSnapshotReader(db, clock).read("test")
        val conflict = snapshot.copy(notes = listOf(snapshot.notes.first { it.id == two }.copy(title = "bad", updatedAt = 3000,
            media = listOf(png()))))
        try { BackupMerger(db, json).merge(conflict); fail("expected collision") }
        catch (_: android.database.sqlite.SQLiteConstraintException) { }
        assertEquals("two", notes.findById(two)!!.title)
        assertEquals(1, media.list(one).size)
        assertTrue(media.list(two).isEmpty())
    }
    @Test fun chunkReadRejectsMissingPosition() = runBlocking {
        val id = notes.createNote("n", "text")
        val p = png()
        db.mediaDao().insert(NoteMediaEntity(p.id, id, p.name, p.mime, p.size, p.sha256))
        db.mediaDao().insertChunks(listOf(MediaChunkEntity(p.id, 1, byteArrayOf(1))))
        try { media.bytes(media.list(id).single()); fail("expected rejection") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun boundedReadsAndSignaturesRejectInvalidInputs() {
        assertThrows(IllegalArgumentException::class.java) { MediaRules.readBounded(ByteArray(0).inputStream()) }
        assertThrows(IllegalArgumentException::class.java) { MediaRules.readBounded(ByteArray(MediaRules.LEGACY_MAX_BYTES + 1).inputStream()) }
        assertThrows(IllegalArgumentException::class.java) { MediaRules.validate(png().copy(base64 = "bad")) }
        assertFalse(MediaRules.matchesSignature("image/png", "not an image".toByteArray()))
        assertTrue(MediaRules.matchesSignature("video/mp4", byteArrayOf(0,0,0,20) + "ftypisom".toByteArray()))
    }
    @Test fun countLimitAndDeletedNoteCannotAcceptMedia() = runBlocking {
        val id = notes.createNote("n", "text")
        repeat(MediaRules.MAX_COUNT) { media.add(id, png("m$it", 8)) }
        try { media.add(id, png("overflow", 8)); fail("expected limit") } catch (_: IllegalArgumentException) { }
        assertEquals(10, media.list(id).size)
        notes.moveToTrash(id)
        try { media.add(id, png("deleted", 8)); fail("expected rejection") } catch (_: IllegalStateException) { }
    }
}
