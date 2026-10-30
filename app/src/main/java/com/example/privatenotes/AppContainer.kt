package com.example.privatenotes

import android.content.Context
import java.io.File
import com.example.privatenotes.data.backup.AndroidBackupDocumentSource
import com.example.privatenotes.data.backup.AppVersionProvider
import com.example.privatenotes.data.backup.BackupArchiveService
import com.example.privatenotes.data.backup.BackupDirectory
import com.example.privatenotes.data.backup.BackupImporter
import com.example.privatenotes.data.backup.BackupJson
import com.example.privatenotes.data.backup.BackupMerger
import com.example.privatenotes.data.backup.BackupScheduler
import com.example.privatenotes.data.backup.BackupSnapshotReader
import com.example.privatenotes.data.backup.BackupStatusStore
import com.example.privatenotes.data.backup.LocalBackupManager
import com.example.privatenotes.data.backup.NoteSyncCoordinator
import com.example.privatenotes.data.backup.SafBackupDirectory
import com.example.privatenotes.data.backup.WebDavBackupManager
import com.example.privatenotes.data.backup.WebDavClient
import com.example.privatenotes.data.NoteSettings
import com.example.privatenotes.data.SharedPreferencesNoteSettings
import com.example.privatenotes.data.local.AppDatabase
import com.example.privatenotes.data.local.EncryptedDatabaseProvider
import com.example.privatenotes.data.repository.NoteRepository
import com.example.privatenotes.data.repository.RoomNoteRepository
import com.example.privatenotes.data.repository.RoomTagRepository
import com.example.privatenotes.data.repository.TagRepository
import com.example.privatenotes.data.repository.TimeSource
import com.example.privatenotes.security.AndroidAppLockAuthenticator
import com.example.privatenotes.security.AndroidKeystoreSecretCipher
import com.example.privatenotes.security.AppLock
import com.example.privatenotes.security.AppLockAuthenticator
import com.example.privatenotes.security.AppLockManager
import com.example.privatenotes.security.AppLockState
import com.example.privatenotes.security.AppLockSettings
import com.example.privatenotes.security.DatabaseKeyManager
import com.example.privatenotes.security.SharedPreferencesAppLockSettings
import com.example.privatenotes.security.SharedPreferencesWrappedKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual dependency graph.
 *
 * Everything is lazy so the Keystore key and the database are created on first real use,
 * rather than during startup before the UI can report a problem.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    /**
     * Outlives any single screen.
     *
     * The editor's final save has to complete even when the screen and its ViewModel are gone,
     * so it cannot run on `viewModelScope`.
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val timeSource: TimeSource = TimeSource.System

    /** Plain user preferences: the trash switch and its future neighbours. */
    val noteSettings: NoteSettings by lazy { SharedPreferencesNoteSettings(appContext) }

    val keyManager: DatabaseKeyManager = DatabaseKeyManager(
        store = SharedPreferencesWrappedKeyStore(appContext),
        cipher = AndroidKeystoreSecretCipher(),
    )

    private val databaseLock = Any()

    @Volatile
    private var databaseInstance: AppDatabase? = null

    private class DatabaseLease { @Volatile var retired = false }
    private var databaseLease: DatabaseLease? = null

    /**
     * The open database, or a freshly opened one.
     *
     * Not `by lazy`: §6.2 wants the connection closed while the app is locked, which means it has
     * to be reopenable later with the same key.
     *
     * Refuses to open while the app is locked. The lock closes the connection, but a collection
     * that was already running (a list query still inside its `WhileSubscribed` window, a backup
     * worker that started a moment earlier) could otherwise open it again right afterwards and
     * leave an unlocked connection behind a locked screen. Refusing is the only place that can
     * hold the invariant: every reader goes through this getter.
     */
    val database: AppDatabase
        get() {
            checkUnlocked()
            return databaseInstance ?: synchronized(databaseLock) {
                checkUnlocked()
                databaseInstance ?: run {
                    val lease = DatabaseLease()
                    EncryptedDatabaseProvider.open(appContext, keyManager, onOpenCheck = {
                        check(!lease.retired) { "database connection has been retired" }
                        checkUnlocked()
                    }).also {
                        databaseLease = lease
                        databaseInstance = it
                    }
                }
            }
        }

    private fun checkUnlocked() {
        check(appLock.state.value != AppLockState.Locked) {
            "database access while the app is locked"
        }
    }

    val mediaRepository: com.example.privatenotes.data.media.MediaRepository
        get() = com.example.privatenotes.data.media.MediaRepository(database, appContext, timeSource)

    val noteRepository: NoteRepository
        get() = RoomNoteRepository(database, timeSource)

    val appLockSettings: AppLockSettings = SharedPreferencesAppLockSettings(appContext)

    val appLockAuthenticator: AppLockAuthenticator = AndroidAppLockAuthenticator(appContext)

    /** Lock state machine; locking also drops the database connection. */
    val appLock: AppLock = AppLockManager(
        settings = appLockSettings,
        timeSource = timeSource,
        onLocked = { closeDatabase() },
    )

    /** Whether a database connection is currently held; used by the lock teardown test. */
    val isDatabaseOpen: Boolean
        get() = databaseInstance?.isOpen == true

    /**
     * Closes the database, if one is open.
     *
     * The instance is detached from the container *before* it is closed and the close itself runs
     * off the main thread. A getter racing with this call therefore opens a new instance instead
     * of touching the closing one.
     */
    fun closeDatabase() {
        val closing = synchronized(databaseLock) {
            // Room.close() is not terminal: a stale DAO can automatically open it again.
            // Retire the lease synchronously so old list flows cannot resurrect the connection.
            databaseLease?.retired = true
            databaseLease = null
            databaseInstance.also { databaseInstance = null }
        } ?: return

        applicationScope.launch {
            runCatching { closing.close() }
        }
    }

    /**
     * Rebuilt per access for the same reason as [noteRepository]: the database can be closed by
     * the lock and reopened later, and a cached repository would keep pointing at the old handle.
     */
    val tagRepository: TagRepository
        get() = RoomTagRepository(database, timeSource)

    /** Cache directory; a downloaded remote backup is staged here before the merge. */
    val cacheDirectory: File
        get() = File(appContext.cacheDir, "remote-restore").apply { mkdirs() }

    // ---------------------------------------------------------------- backups

    val appVersionName: String get() = appVersionProvider.versionName()
    private val appVersionProvider: AppVersionProvider by lazy { AppVersionProvider(appContext) }

    private val backupJson: BackupJson by lazy { BackupJson() }

    val backupStatusStore: BackupStatusStore by lazy { BackupStatusStore(appContext) }

    private val backupSnapshotReader: BackupSnapshotReader by lazy {
        // Provider, so constructing the backup services on a foreground start does not open the
        // database that §6.2 just closed for the lock screen.
        BackupSnapshotReader({ database }, timeSource)
    }

    private val backupArchiveService: BackupArchiveService by lazy {
        BackupArchiveService(backupSnapshotReader, backupJson, appVersionProvider,
            streamingProvider = { com.example.privatenotes.data.backup.StreamingMediaArchive(database) },
            cacheDirectory = { cacheDirectory })
    }

    private val backupMerger: BackupMerger by lazy { BackupMerger({ database }, backupJson) }

    private val safBackupDirectory: SafBackupDirectory by lazy {
        SafBackupDirectory(appContext, backupStatusStore)
    }

    val backupDirectory: BackupDirectory get() = safBackupDirectory

    val localBackupManager: LocalBackupManager by lazy {
        LocalBackupManager(backupArchiveService, safBackupDirectory, backupStatusStore, timeSource)
    }

    private val backupDocumentSource: AndroidBackupDocumentSource by lazy {
        AndroidBackupDocumentSource(appContext)
    }

    private val webDavHttpClient by lazy { WebDavClient.defaultHttpClient() }

    val webDavBackupManager: WebDavBackupManager by lazy {
        WebDavBackupManager(
            archive = backupArchiveService,
            statusStore = backupStatusStore,
            clientFactory = { settings, password -> WebDavClient(webDavHttpClient, settings, password) },
            timeSource = timeSource,
        )
    }

    /** Fires a WebDAV upload a few seconds after an article is saved, when the user enabled it. */
    val noteSyncCoordinator: NoteSyncCoordinator by lazy {
        NoteSyncCoordinator(
            statusStore = backupStatusStore,
            appLock = appLock,
            upload = { webDavBackupManager.backup(password = null) },
            scope = applicationScope,
        )
    }

    val backupImporter: BackupImporter by lazy {
        BackupImporter(
            source = backupDocumentSource,
            archive = backupArchiveService,
            merger = backupMerger,
            localBackupManager = localBackupManager,
            streamingProvider = { com.example.privatenotes.data.backup.StreamingMediaArchive(database) },
            stagingDirectory = appContext.cacheDir,
        )
    }

    val backupScheduler: BackupScheduler by lazy {
        BackupScheduler(appContext, backupStatusStore, timeSource)
    }
}
