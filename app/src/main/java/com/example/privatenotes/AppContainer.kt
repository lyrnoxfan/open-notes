package com.example.privatenotes

import android.content.Context
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
import com.example.privatenotes.data.backup.SafBackupDirectory
import com.example.privatenotes.data.backup.WebDavBackupManager
import com.example.privatenotes.data.backup.WebDavClient
import com.example.privatenotes.data.local.AppDatabase
import com.example.privatenotes.data.local.EncryptedDatabaseProvider
import com.example.privatenotes.data.repository.NoteRepository
import com.example.privatenotes.data.repository.RoomNoteRepository
import com.example.privatenotes.data.repository.TimeSource
import com.example.privatenotes.security.AndroidKeystoreSecretCipher
import com.example.privatenotes.security.DatabaseKeyManager
import com.example.privatenotes.security.SharedPreferencesWrappedKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

    val keyManager: DatabaseKeyManager = DatabaseKeyManager(
        store = SharedPreferencesWrappedKeyStore(appContext),
        cipher = AndroidKeystoreSecretCipher(),
    )

    val database: AppDatabase by lazy {
        EncryptedDatabaseProvider.open(appContext, keyManager)
    }

    val noteRepository: NoteRepository by lazy {
        RoomNoteRepository(database, timeSource)
    }

    // ---------------------------------------------------------------- backups

    private val appVersionProvider: AppVersionProvider by lazy { AppVersionProvider(appContext) }

    private val backupJson: BackupJson by lazy { BackupJson() }

    val backupStatusStore: BackupStatusStore by lazy { BackupStatusStore(appContext) }

    private val backupSnapshotReader: BackupSnapshotReader by lazy {
        BackupSnapshotReader(database, timeSource)
    }

    private val backupArchiveService: BackupArchiveService by lazy {
        BackupArchiveService(backupSnapshotReader, backupJson, appVersionProvider)
    }

    private val backupMerger: BackupMerger by lazy { BackupMerger(database, backupJson) }

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

    val backupImporter: BackupImporter by lazy {
        BackupImporter(
            source = backupDocumentSource,
            archive = backupArchiveService,
            merger = backupMerger,
            localBackupManager = localBackupManager,
        )
    }

    val backupScheduler: BackupScheduler by lazy {
        BackupScheduler(appContext, backupStatusStore, timeSource)
    }
}
