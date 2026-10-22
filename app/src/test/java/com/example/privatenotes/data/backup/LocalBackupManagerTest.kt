package com.example.privatenotes.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.privatenotes.data.local.AppDatabase
import com.example.privatenotes.data.repository.TimeSource
import com.example.privatenotes.security.FakeSecretCipher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalBackupManagerTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var statusStore: BackupStatusStore
    private lateinit var directory: FakeBackupDirectory
    private lateinit var archive: BackupArchiveService
    private lateinit var manager: LocalBackupManager
    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(BackupStatusStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        statusStore = BackupStatusStore(context, FakeSecretCipher())
        statusStore.setLocalDirectory("content://test/tree", "Backups")
        directory = FakeBackupDirectory()
        archive = BackupArchiveService(
            snapshotReader = BackupSnapshotReader(database, TimeSource { now }),
            json = BackupJson(),
            appVersionProvider = AppVersionProvider(context),
        )
        manager = LocalBackupManager(archive, directory, statusStore, TimeSource { now }, fixedRetention = 30)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a verified write records success`() = runBlocking {
        val result = manager.backup()

        assertFalse(result.encrypted)
        assertTrue(result.fileName.endsWith(".json"))
        assertNotNull(directory.files[result.fileName])
        assertEquals(now, statusStore.status.value.localLastSuccessAt ?: -1L)
        assertEquals(null, statusStore.status.value.localLastFailure)
    }

    @Test
    fun `an encrypted backup is written with the pnb envelope`() = runBlocking {
        val result = manager.backup("password123".toCharArray())

        assertTrue(result.encrypted)
        assertTrue(result.fileName.endsWith(".pnb"))
        val bytes = directory.files.getValue(result.fileName)
        assertTrue(BackupCrypto.isEncrypted(bytes))
        val plaintext = BackupCrypto.decrypt(bytes, "password123".toCharArray())
        assertTrue(String(plaintext, Charsets.UTF_8).contains("private-notes-backup"))
    }

    @Test
    fun `a failed verified write leaves no file and records the failure`() = runBlocking {
        directory.failure = BackupException(BackupErrorKind.PERMISSION_LOST, "revoked")

        val failure = runCatching { manager.backup() }.exceptionOrNull() as BackupException

        assertEquals(BackupErrorKind.PERMISSION_LOST, failure.kind)
        assertTrue(directory.files.isEmpty())
        assertEquals(BackupErrorKind.PERMISSION_LOST, statusStore.status.value.localLastFailure?.kind)
        assertEquals(null, statusStore.status.value.localLastSuccessAt)
    }

    @Test
    fun `retention deletes only the oldest files and keeps the newest`() = runBlocking {
        repeat(35) { index ->
            directory.files["private-notes-20200101T0000${index.toString().padStart(2, '0')}Z-old$index.json"] =
                byteArrayOf(1)
        }

        val result = manager.backup()

        assertEquals(30, directory.files.size)
        assertTrue("the new backup must survive pruning", result.fileName in directory.files)
        assertEquals(6, directory.deleted.size)
    }

    @Test
    fun `an unconfigured directory is refused before any write`() = runBlocking {
        directory.configured = false

        val failure = runCatching { manager.backup() }.exceptionOrNull() as BackupException

        assertEquals(BackupErrorKind.NOT_CONFIGURED, failure.kind)
        assertTrue(directory.files.isEmpty())
        assertEquals(null, statusStore.status.value.localLastFailure)
    }
}
