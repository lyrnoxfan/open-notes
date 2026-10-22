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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The retention setting: how many backup files to keep, with 0 meaning "keep every one".
 *
 * Each backup already contains every note - the snapshot reads allNotes() - so this is about the
 * number of history files on disk, never about how many notes a backup carries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalRetentionTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var statusStore: BackupStatusStore
    private lateinit var directory: FakeBackupDirectory
    private lateinit var manager: LocalBackupManager
    private val now = 1_730_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(BackupStatusStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        statusStore = BackupStatusStore(context, FakeSecretCipher())
        statusStore.setLocalDirectory("content://test/tree", "Backups")
        directory = FakeBackupDirectory()
        val archive = BackupArchiveService(
            snapshotReader = BackupSnapshotReader(database, TimeSource { now }),
            json = BackupJson(),
            appVersionProvider = AppVersionProvider(context),
        )
        manager = LocalBackupManager(archive, directory, statusStore, TimeSource { now })
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun keepsEveryFileWhenRetentionIsZero() = runBlocking {
        statusStore.setLocalRetention(0)
        repeat(5) { manager.backup() }
        assertEquals("0 means unlimited history", 5, directory.files.size)
    }

    @Test
    fun keepsTheConfiguredNumberOfFiles() = runBlocking {
        statusStore.setLocalRetention(3)
        repeat(6) { manager.backup() }
        assertEquals("only the newest 3 files remain", 3, directory.files.size)
        assertTrue("the newest file is never pruned", directory.files.isNotEmpty())
    }

    @Test
    fun defaultIsThirtyAndClampsNegative() {
        assertEquals(30, statusStore.status.value.localRetention)
        statusStore.setLocalRetention(90)
        assertEquals(90, statusStore.status.value.localRetention)
        statusStore.setLocalRetention(-5)
        assertEquals("negative values clamp to 0 (keep all)", 0, statusStore.status.value.localRetention)
    }
}
