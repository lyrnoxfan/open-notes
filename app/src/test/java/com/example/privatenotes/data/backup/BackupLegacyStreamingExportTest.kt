package com.example.privatenotes.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.privatenotes.data.local.AppDatabase
import com.example.privatenotes.data.local.NoteEntity
import com.example.privatenotes.data.media.MediaChunkEntity
import com.example.privatenotes.data.media.NoteMediaEntity
import com.example.privatenotes.data.repository.TimeSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupLegacyStreamingExportTest {
    private lateinit var database: AppDatabase
    private lateinit var cache: File
    private lateinit var archive: BackupArchiveService

    @Before fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        cache = File(context.cacheDir, "legacy-export-test-${System.nanoTime()}").apply { mkdirs() }
        val snapshot = BackupSnapshotReader(database, TimeSource { 1_700_000_000_000L })
        archive = BackupArchiveService(snapshot, BackupJson(), AppVersionProvider(context), cacheDirectory = { cache })
    }

    @After fun tearDown() { database.close(); cache.deleteRecursively() }

    @Test fun `production legacy export streams one 9 MiB media and more than 40 MiB total`() = runBlocking {
        database.noteDao().insert(NoteEntity("n9", "9 MiB", "body", 1, 2))
        database.noteDao().insert(NoteEntity("n32", "32 MiB", "body", 1, 2))
        insertSyntheticMedia("m9", "n9", 9L * MIB)
        insertSyntheticMedia("m32", "n32", 32L * MIB)

        archive.prepareLegacyJson(null).use { prepared ->
            assertTrue(prepared.fileName.endsWith(".json"))
            assertTrue("legacy schema-2 JSON includes 41 MiB media as Base64", prepared.file.length() > 54L * MIB)
            RandomAccessFile(prepared.file, "r").use { file ->
                val head = ByteArray(90)
                file.readFully(head)
                val prefix = String(head, Charsets.UTF_8)
                assertTrue(prefix.startsWith("{\"format\":\"private-notes-backup\""))
                file.seek(file.length() - 1)
                assertTrue(file.read() == '}'.code)
            }
        }
        assertTrue("successful prepared export is deleted when closed", cache.listFiles().orEmpty().isEmpty())
    }

    @Test fun `failed media validation removes partial legacy export`() = runBlocking {
        database.noteDao().insert(NoteEntity("n", "note", "body", 1, 2))
        insertSyntheticMedia("bad", "n", 1024L, corruptDigest = true)
        try {
            archive.prepareLegacyJson(null)
            throw AssertionError("corrupt media unexpectedly exported")
        } catch (_: IllegalArgumentException) {
            assertTrue("failed temp export is removed", cache.listFiles().orEmpty().isEmpty())
        }
    }

    private suspend fun insertSyntheticMedia(id: String, noteId: String, size: Long, corruptDigest: Boolean = false) {
        val dao = database.mediaDao()
        val hash = MessageDigest.getInstance("SHA-256")
        val count = ((size - 1) / CHUNK + 1).toInt()
        repeat(count) { index ->
            val bytes = syntheticChunk(size, index)
            hash.update(bytes)
        }
        val digest = hash.digest().joinToString("") { "%02x".format(it) }
        val entity = NoteMediaEntity(id, noteId, "$id.png", "image/png", size, if (corruptDigest) "0".repeat(64) else digest)
        dao.insert(entity)
        var pending = ArrayList<MediaChunkEntity>(16)
        repeat(count) { index ->
            pending += MediaChunkEntity(id, index, syntheticChunk(size, index))
            if (pending.size == 16 || index == count - 1) {
                dao.insertChunks(pending)
                pending = ArrayList(16)
            }
        }
    }

    private fun syntheticChunk(size: Long, index: Int): ByteArray {
        val offset = index.toLong() * CHUNK
        val length = minOf(CHUNK.toLong(), size - offset).toInt()
        return ByteArray(length) { i ->
            val absolute = offset + i
            if (absolute < PNG_SIGNATURE.size) PNG_SIGNATURE[absolute.toInt()] else 0x42
        }
    }

    companion object {
        private const val MIB = 1024L * 1024
        private const val CHUNK = 256 * 1024
        private val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    }
}
