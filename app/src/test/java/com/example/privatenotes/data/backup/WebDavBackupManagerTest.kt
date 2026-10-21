package com.example.privatenotes.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.privatenotes.data.local.AppDatabase
import com.example.privatenotes.data.repository.TimeSource
import com.example.privatenotes.security.FakeSecretCipher
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebDavBackupManagerTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var statusStore: BackupStatusStore
    private lateinit var archive: BackupArchiveService
    private lateinit var remote: FakeWebDavRemote
    private lateinit var manager: WebDavBackupManager
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
        archive = BackupArchiveService(
            snapshotReader = BackupSnapshotReader(database, TimeSource { now }),
            json = BackupJson(),
            appVersionProvider = AppVersionProvider(context),
        )
        remote = FakeWebDavRemote()
        manager = WebDavBackupManager(
            archive = archive,
            statusStore = statusStore,
            clientFactory = { _, password ->
                remote.lastPassword = password
                remote
            },
            timeSource = TimeSource { now },
        )
        statusStore.saveWebDav(
            baseUrl = "https://example.com/dav/",
            username = "user",
            remoteDirectory = "notes",
            password = "saved-pass".toCharArray(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a successful upload records the verification and the timestamp`() = runBlocking {
        val result = manager.backup()

        assertFalse(result.encrypted)
        assertEquals(WebDavVerification.FULL, result.verification)
        assertEquals("saved-pass", remote.lastPassword)
        assertTrue(result.fileName.endsWith(".json"))
        assertTrue(String(remote.uploadedBytes!!, Charsets.UTF_8).contains("private-notes-backup"))
        assertEquals(now, statusStore.status.value.remoteLastSuccessAt ?: -1L)
        assertNull(statusStore.status.value.remoteLastFailure)
        assertEquals(result.fileName, remote.verifiedFileName)
    }

    @Test
    fun `an encrypted upload uses the pnb envelope and clears the password array`() = runBlocking {
        val password = "password123".toCharArray()

        val result = manager.backup(password)

        assertTrue(result.encrypted)
        assertTrue(result.fileName.endsWith(".pnb"))
        assertTrue(BackupCrypto.isEncrypted(remote.uploadedBytes!!))
        assertArrayEquals(CharArray(password.size), password)
        val plaintext = BackupCrypto.decrypt(remote.uploadedBytes!!, "password123".toCharArray())
        assertTrue(String(plaintext, Charsets.UTF_8).contains("private-notes-backup"))
    }

    @Test
    fun `a failed read-back deletes the just uploaded file and records a failure`() = runBlocking {
        remote.verifyFailure = BackupException(BackupErrorKind.STORAGE, "mismatch")

        val failure = runCatching { manager.backup() }.exceptionOrNull() as BackupException

        assertEquals(BackupErrorKind.STORAGE, failure.kind)
        assertTrue(remote.deleted)
        assertEquals(BackupErrorKind.STORAGE, statusStore.status.value.remoteLastFailure?.kind)
        assertNull(statusStore.status.value.remoteLastSuccessAt)
    }

    @Test
    fun `an unconfigured server is refused before uploading`() = runBlocking {
        statusStore.clearWebDavPassword()

        val failure = runCatching { manager.backup() }.exceptionOrNull() as BackupException

        assertEquals(BackupErrorKind.NOT_CONFIGURED, failure.kind)
        assertNull(remote.uploadedBytes)
    }

    @Test
    fun `test connection accepts a draft password without saving it`() = runBlocking {
        manager.testConnection(
            baseUrl = "https://example.com/dav/",
            username = "user",
            remoteDirectory = "/notes/",
            draftPassword = "draft-pass".toCharArray(),
        )

        assertEquals("draft-pass", remote.lastPassword)
        assertTrue(remote.tested)
        assertTrue(statusStore.status.value.webDavPasswordSaved)
    }

    private class FakeWebDavRemote : WebDavRemote {
        var uploadedBytes: ByteArray? = null
        var verifiedFileName: String? = null
        var lastPassword: String? = null
        var deleted = false
        var tested = false
        var verifyFailure: BackupException? = null

        override suspend fun testConnection() {
            tested = true
        }

        override suspend fun upload(
            fileName: String,
            bytes: ByteArray,
            contentType: MediaType,
        ): WebDavUploadResult {
            uploadedBytes = bytes.copyOf()
            return WebDavUploadResult(fileName, "https://example.com/dav/notes/", bytes.size.toLong())
        }

        override suspend fun verify(fileName: String, expected: ByteArray): WebDavVerification {
            verifiedFileName = fileName
            verifyFailure?.let { throw it }
            return WebDavVerification.FULL
        }

        override suspend fun deleteQuietly(fileName: String) {
            deleted = true
        }
    }
}
