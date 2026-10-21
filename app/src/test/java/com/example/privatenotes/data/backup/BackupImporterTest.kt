package com.example.privatenotes.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.privatenotes.data.local.AppDatabase
import com.example.privatenotes.data.local.NoteEntity
import com.example.privatenotes.data.repository.TimeSource
import com.example.privatenotes.security.FakeSecretCipher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupImporterTest {

    private lateinit var context: Context
    private lateinit var source: AppDatabase
    private lateinit var target: AppDatabase
    private lateinit var archive: BackupArchiveService
    private lateinit var statusStore: BackupStatusStore
    private lateinit var directory: FakeBackupDirectory
    private lateinit var json: BackupJson
    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(BackupStatusStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        source = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        target = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        json = BackupJson()
        statusStore = BackupStatusStore(context, FakeSecretCipher())
        directory = FakeBackupDirectory(configured = false)
        archive = BackupArchiveService(
            snapshotReader = BackupSnapshotReader(source, TimeSource { now }),
            json = json,
            appVersionProvider = AppVersionProvider(context),
        )
    }

    @After
    fun tearDown() {
        source.close()
        target.close()
    }

    @Test
    fun `an import validates, merges, and rolls into the target`() = runBlocking {
        source.noteDao().insert(
            NoteEntity(id = "n", title = "imported", content = "body", createdAt = 1L, updatedAt = 2L),
        )
        val bytes = archive.create(password = null).bytes
        val importer = createImporter(directory, bytes)

        val outcome = importer.import(FakeDocumentSource(bytes).uri, password = null)

        assertEquals(1, outcome.report.insertedNotes)
        assertNull("no safety backup when no directory is configured", outcome.safetyBackupFileName)
        assertEquals("imported", target.noteDao().findById("n")?.title)
    }

    @Test
    fun `a wrong password is rejected before the target is touched`() = runBlocking {
        source.noteDao().insert(
            NoteEntity(id = "n", title = "secret", content = "body", createdAt = 1L, updatedAt = 2L),
        )
        val bytes = archive.create("correct-password".toCharArray()).bytes
        val importer = createImporter(directory, bytes)

        val failure = runCatching {
            importer.import(FakeDocumentSource(bytes).uri, "wrong-password".toCharArray())
        }.exceptionOrNull() as BackupException

        assertEquals(BackupErrorKind.WRONG_PASSWORD_OR_CORRUPT, failure.kind)
        assertEquals(0, target.noteDao().countActive())
    }

    @Test
    fun `a corrupt file is rejected before the target is touched`() = runBlocking {
        val corrupt = "{not-json".toByteArray()
        val importer = createImporter(directory, corrupt)

        val failure = runCatching {
            importer.import(FakeDocumentSource(corrupt).uri, null)
        }.exceptionOrNull() as BackupException

        assertEquals(BackupErrorKind.INVALID_FORMAT, failure.kind)
        assertEquals(0, target.noteDao().countActive())
    }

    @Test
    fun `a failed pre-import safety backup cancels the import`() = runBlocking {
        source.noteDao().insert(
            NoteEntity(id = "n", title = "imported", content = "body", createdAt = 1L, updatedAt = 2L),
        )
        val bytes = archive.create(null).bytes
        val failingDirectory = FakeBackupDirectory(
            configured = true,
            failure = BackupException(BackupErrorKind.STORAGE, "disk full"),
        )
        val importer = createImporter(failingDirectory, bytes)

        val failure = runCatching {
            importer.import(FakeDocumentSource(bytes).uri, null)
        }.exceptionOrNull() as BackupException

        assertEquals(BackupErrorKind.SAFETY_BACKUP_FAILED, failure.kind)
        assertEquals(0, target.noteDao().countActive())
        assertTrue(target.deletionMarkerDao().count() == 0)
    }

    @Test
    fun `a verified safety backup is reported and written before the merge`() = runBlocking {
        source.noteDao().insert(
            NoteEntity(id = "n", title = "imported", content = "body", createdAt = 1L, updatedAt = 2L),
        )
        val bytes = archive.create(null).bytes
        val importer = createImporter(FakeBackupDirectory(configured = true), bytes)

        val outcome = importer.import(FakeDocumentSource(bytes).uri, null)

        assertNotNull(outcome.safetyBackupFileName)
        assertEquals(1, target.noteDao().countActive())
    }

    private fun createImporter(directory: FakeBackupDirectory, bytes: ByteArray): BackupImporter {
        val localManager = LocalBackupManager(
            archive = archive,
            directory = directory,
            statusStore = statusStore,
            timeSource = TimeSource { now },
        )
        return BackupImporter(
            source = FakeDocumentSource(bytes),
            archive = archive,
            merger = BackupMerger(target, json),
            localBackupManager = localManager,
        )
    }
}
