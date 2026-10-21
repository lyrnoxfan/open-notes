package com.example.privatenotes.data.backup

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.privatenotes.data.local.AppDatabase
import com.example.privatenotes.data.local.EncryptedDatabaseProvider
import com.example.privatenotes.data.repository.RoomNoteRepository
import com.example.privatenotes.data.repository.RoomTagRepository
import com.example.privatenotes.data.repository.TimeSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Backup behaviour against the real encrypted database (§12 备份恢复).
 *
 * The host tests prove the format and the merge rules on an in-memory copy; these run the same
 * paths through SQLCipher and the real DAOs, which is where a restore actually has to work.
 */
@RunWith(AndroidJUnit4::class)
class BackupDeviceTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val passphrase = ByteArray(32) { (it + 5).toByte() }

    private val time = object : TimeSource {
        override fun nowMillis(): Long = 5_000L
    }

    private val archive = BackupArchiveService(
        BackupSnapshotReader(openDatabase(), time),
        BackupJson(),
        AppVersionProvider(context),
    )

    @Before
    fun cleanBefore() {
        context.deleteDatabase(DB)
    }

    @After
    fun cleanAfter() {
        context.deleteDatabase(DB)
    }

    private fun openDatabase(file: String = DB): AppDatabase =
        EncryptedDatabaseProvider.openWithPassphrase(context, passphrase, file)

    @Test
    fun backupRestoresNoteTagAndLink() = runBlocking {
        val source = openDatabase()
        val notes = RoomNoteRepository(source, time)
        val tags = RoomTagRepository(source, time)
        val noteId: String
        val tagId: String
        try {
            noteId = notes.createNote("买菜", "牛奶和面包")
            tagId = tags.ensureTag("生活")!!
            tags.attachTag(noteId, tagId)
        } finally {
            source.close()
        }

        val reader = BackupArchiveService(
            BackupSnapshotReader(openDatabase(), time),
            BackupJson(),
            AppVersionProvider(context),
        )
        val backup = reader.create(password = null)

        // Fresh install: the file is gone, so the merge runs against an empty database.
        context.deleteDatabase(DB)
        val target = openDatabase()
        try {
            val report = BackupMerger(target, BackupJson()).merge(reader.read(backup.bytes, null))
            assertTrue("the restored note is new to an empty database", report.insertedNotes >= 1)

            val restoredNotes = RoomNoteRepository(target, time)
            val restoredTags = RoomTagRepository(target, time)
            val note = target.backupDao().allNotes().single()
            assertEquals("买菜", note.title)
            assertEquals("牛奶和面包", note.content)
            assertEquals(1, target.backupDao().allTags().size)
            assertEquals("生活", target.backupDao().allTags().single().name)
            assertEquals(
                "the tag link comes back, otherwise the note is restored unfiled",
                listOf(note.id to tagId),
                target.backupDao().allNoteTags().map { it.noteId to it.tagId },
            )
            assertNotNull(restoredNotes.findById(noteId))
            assertNotNull(restoredTags.findByName("生活"))
        } finally {
            target.close()
        }
    }

    @Test
    fun encryptedBackupRejectsWrongPassword() = runBlocking {
        val password = "correct horse".toCharArray()
        val wrong = "battery staple".toCharArray()

        val source = openDatabase()
        try {
            RoomNoteRepository(source, time).createNote("私密", "只属于我")
        } finally {
            source.close()
        }

        val reader = BackupArchiveService(
            BackupSnapshotReader(openDatabase(), time),
            BackupJson(),
            AppVersionProvider(context),
        )
        val backup = reader.create(password)
        assertEquals(BackupFileKind.ENCRYPTED, reader.kindOf(backup.bytes))

        val target = openDatabase()
        try {
            val failure = assertThrows(BackupException::class.java) {
                reader.read(backup.bytes, wrong)
            }
            assertEquals(BackupErrorKind.WRONG_PASSWORD_OR_CORRUPT, failure.kind)
            assertEquals("the local note is untouched", 1, target.backupDao().allNotes().size)
        } finally {
            target.close()
        }
    }

    @Test
    fun revokedSafDirectoryIsReported() = runBlocking {
        val store = BackupStatusStore(context)
        store.setLocalDirectory(
            "content://com.android.externalstorage.documents/tree/primary%3ANotes",
            "Notes",
        )
        val directory = SafBackupDirectory(context, store)

        // The grant is not held by this process, so every operation must refuse rather than
        // pretend to have written a backup.
        assertTrue("without a persisted grant the directory is not usable", !directory.isAuthorized())
        val failure = assertThrows(BackupException::class.java) {
            runBlocking { directory.writeBackup("backup", "application/json", ByteArray(4)) }
        }
        assertEquals(BackupErrorKind.PERMISSION_LOST, failure.kind)
        store.clearLocalDirectory()
    }

    @Test
    fun unreachableWebDavIsANetworkFailure() = runBlocking {
        val client = WebDavClient(
            http = WebDavClient.defaultHttpClient(),
            settings = WebDavSettings(
                baseUrl = "https://127.0.0.1:1/dav",
                username = "user",
                remoteDirectory = "private-notes",
                hasPassword = true,
            ),
            password = "secret",
        )
        val failure = assertThrows(BackupException::class.java) {
            runBlocking { client.testConnection() }
        }
        assertEquals(BackupErrorKind.NETWORK, failure.kind)
    }

    private companion object {
        const val DB = "backup_device_test.db"
    }
}
