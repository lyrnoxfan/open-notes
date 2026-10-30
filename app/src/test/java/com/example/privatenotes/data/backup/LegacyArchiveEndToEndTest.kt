package com.example.privatenotes.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.privatenotes.data.local.AppDatabase
import com.example.privatenotes.data.local.NoteEntity
import com.example.privatenotes.data.media.MediaChunkEntity
import com.example.privatenotes.data.media.MediaRules
import com.example.privatenotes.data.media.NoteMediaEntity
import com.example.privatenotes.data.repository.TimeSource
import com.example.privatenotes.security.FakeSecretCipher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.io.RandomAccessFile

/** Exercises the actual archive -> file source -> BackupImporter -> Room path. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyArchiveEndToEndTest {
    private lateinit var context: Context
    private lateinit var sourceDb: AppDatabase
    private lateinit var targetDb: AppDatabase
    private lateinit var cache: File
    private lateinit var json: BackupJson

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        sourceDb = database()
        targetDb = database()
        cache = File(context.cacheDir, "legacy-e2e-${System.nanoTime()}").apply { mkdirs() }
        json = BackupJson()
    }

    @After fun tearDown() {
        sourceDb.close(); targetDb.close(); cache.deleteRecursively()
    }

    @Test fun `legacy JSON export and actual import round trip media over old item and aggregate caps`() = runBlocking {
        seedOverLegacyCaps(sourceDb)
        val archive = archive(sourceDb)
        archive.prepareLegacyJson(null).use { prepared ->
            assertTrue(prepared.file.length() > 40L * MIB)
            val source = FileSource(prepared.file)
            importer(source, archive).import(source.uri, null)
        }
        assertRestored(targetDb)
    }

    @Test fun `legacy PNB export and actual authenticated import round trip media over old item and aggregate caps`() = runBlocking {
        seedOverLegacyCaps(sourceDb)
        val password = "legacy-roundtrip-password".toCharArray()
        val archive = archive(sourceDb)
        archive.prepareLegacyJson(password.copyOf()).use { prepared ->
            assertTrue(prepared.fileName.endsWith(".pnb"))
            assertTrue(prepared.file.length() > 40L * MIB)
            val source = FileSource(prepared.file)
            importer(source, archive).import(source.uri, password.copyOf())
        }
        assertRestored(targetDb)
    }

    @Test fun `tampered PNB tag is rejected before any target database write`() = runBlocking {
        seedOneLargeMedia(sourceDb, 9L * MIB)
        val archive = archive(sourceDb)
        val password = "legacy-tamper-password".toCharArray()
        archive.prepareLegacyJson(password.copyOf()).use { prepared ->
            val corrupt = File(cache, "tag-corrupt.pnb")
            prepared.file.copyTo(corrupt, overwrite = true)
            RandomAccessFile(corrupt, "rw").use { file ->
                file.seek(file.length() - 1)
                val last = file.read().also { check(it >= 0) }
                file.seek(file.length() - 1)
                file.write(last xor 1)
            }
            val source = FileSource(corrupt)
            val failure = runCatching { importer(source, archive).import(source.uri, password.copyOf()) }.exceptionOrNull()
            assertTrue("tag corruption must be rejected", failure is BackupException)
            assertEquals(BackupErrorKind.WRONG_PASSWORD_OR_CORRUPT, (failure as BackupException).kind)
        }
        assertEquals(0, targetDb.noteDao().countActive())
        assertEquals(0, targetDb.mediaDao().all().size)
    }

    @Test fun `interrupted legacy JSON read leaves target unchanged and removes staging`() = runBlocking {
        seedOneLargeMedia(sourceDb, 9L * MIB)
        val archive = archive(sourceDb)
        archive.prepareLegacyJson(null).use { prepared ->
            val source = FileSource(prepared.file, failAfterBytes = 1024 * 1024)
            val failure = runCatching { importer(source, archive).import(source.uri, null) }.exceptionOrNull()
            assertTrue("simulated source interruption must propagate", failure is IOException)
        }
        assertEquals(0, targetDb.noteDao().countActive())
        assertEquals(0, targetDb.mediaDao().all().size)
        assertTrue("partial staging directories are cleaned", cache.listFiles().orEmpty().none { it.name.startsWith("legacy-stage-") })
    }

    private fun database() = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries().build()

    private fun archive(db: AppDatabase) = BackupArchiveService(
        BackupSnapshotReader(db, TimeSource { 1_700_000_000_000L }),
        json,
        AppVersionProvider(context),
        cacheDirectory = { cache },
    )

    private fun importer(source: FileSource, archive: BackupArchiveService): BackupImporter {
        val local = LocalBackupManager(archive, FakeBackupDirectory(configured = false), BackupStatusStore(context, FakeSecretCipher()), TimeSource { 1_700_000_000_000L })
        return BackupImporter(source, archive, BackupMerger(targetDb, json), local, stagingDirectory = cache)
    }

    private suspend fun seedOverLegacyCaps(db: AppDatabase) {
        seedOneLargeMedia(db, 9L * MIB, "note9", "media9")
        seedOneLargeMedia(db, 32L * MIB, "note32", "media32")
    }

    private suspend fun seedOneLargeMedia(db: AppDatabase, size: Long, noteId: String = "large", mediaId: String = "large-media") {
        db.noteDao().insert(NoteEntity(noteId, "note-$noteId", "body", 1, 2))
        val chunks = MediaRules.chunkCount(size)
        val digest = MessageDigest.getInstance("SHA-256")
        for (index in 0 until chunks) digest.update(bytes(size, index))
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        db.mediaDao().insert(NoteMediaEntity(mediaId, noteId, "$mediaId.png", "image/png", size, sha))
        val pending = ArrayList<MediaChunkEntity>(16)
        for (index in 0 until chunks) {
            pending += MediaChunkEntity(mediaId, index, bytes(size, index))
            if (pending.size == 16 || index == chunks - 1) {
                db.mediaDao().insertChunks(pending)
                pending.clear()
            }
        }
    }

    private fun bytes(size: Long, index: Int): ByteArray {
        val offset = index.toLong() * MediaRules.CHUNK_SIZE
        val length = minOf(MediaRules.CHUNK_SIZE.toLong(), size - offset).toInt()
        return ByteArray(length) { i ->
            val absolute = offset + i
            if (absolute < PNG.size) PNG[absolute.toInt()] else ((absolute * 31 + index * 17) and 0xff).toByte()
        }
    }

    private suspend fun assertRestored(db: AppDatabase) {
        assertEquals(2, db.noteDao().countActive())
        val media = db.mediaDao().all().associateBy { it.id }
        assertEquals(setOf("media9", "media32"), media.keys)
        assertEquals(9L * MIB, media.getValue("media9").size)
        assertEquals(32L * MIB, media.getValue("media32").size)
        for (item in media.values) {
            val chunks = db.mediaDao().chunks(item.id)
            assertEquals(MediaRules.chunkCount(item.size), chunks.size)
            val digest = MessageDigest.getInstance("SHA-256")
            chunks.sortedBy { it.position }.forEach { digest.update(it.bytes) }
            assertEquals(item.sha256, digest.digest().joinToString("") { "%02x".format(it) })
        }
    }

    private class FileSource(private val file: File, private val failAfterBytes: Int? = null) : BackupDocumentSource {
        val uri: Uri = Uri.fromFile(file)
        override suspend fun inspect(uri: Uri): BackupDocumentInfo {
            val head = FileInputStream(file).use { it.readNBytes(8) }
            val kind = if (BackupCrypto.isEncrypted(head)) BackupFileKind.ENCRYPTED else BackupFileKind.PLAINTEXT_JSON
            return BackupDocumentInfo(file.name, file.length(), kind)
        }
        override suspend fun open(uri: Uri): InputStream {
            val input = FileInputStream(file)
            val limit = failAfterBytes ?: return input
            return object : FilterInputStream(input) {
                private var total = 0
                override fun read(): Int {
                    if (total >= limit) throw IOException("simulated source interruption")
                    return super.read().also { if (it >= 0) total++ }
                }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (total >= limit) throw IOException("simulated source interruption")
                    val n = super.read(b, off, minOf(len, limit - total))
                    if (n > 0) total += n
                    return n
                }
            }
        }
        override suspend fun read(uri: Uri): ByteArray = file.readBytes()
    }

    companion object {
        private const val MIB = 1024L * 1024
        private val PNG = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    }
}
