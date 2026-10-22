package com.example.privatenotes.feature.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.work.WorkInfo
import java.io.File
import com.example.privatenotes.AppContainer
import com.example.privatenotes.data.backup.BackupDirectory
import com.example.privatenotes.data.backup.BackupDocumentInfo
import com.example.privatenotes.data.backup.BackupErrorKind
import com.example.privatenotes.data.backup.BackupException
import com.example.privatenotes.data.backup.BackupFileKind
import com.example.privatenotes.data.backup.BackupFileNames
import com.example.privatenotes.data.backup.BackupImporter
import com.example.privatenotes.data.backup.BackupScheduler
import com.example.privatenotes.data.backup.BackupStatus
import com.example.privatenotes.data.backup.BackupStatusStore
import com.example.privatenotes.data.backup.ImportOutcome
import com.example.privatenotes.data.backup.LocalBackupManager
import com.example.privatenotes.data.backup.WebDavBackupManager
import com.example.privatenotes.data.backup.WebDavConfigValidator
import com.example.privatenotes.data.backup.WebDavVerification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class BackupOperation {
    SAVING_CONFIG,
    TESTING_CONNECTION,
    LOCAL_BACKUP,
    REMOTE_BACKUP,
    INSPECTING_IMPORT,
    IMPORTING,
    LISTING_REMOTE,
    DOWNLOADING_REMOTE,
}

sealed interface BackupNotice {
    data class Info(val text: String) : BackupNotice
    data class Failure(val kind: BackupErrorKind, val detail: String?) : BackupNotice
}

/** One remote backup as the restore dialog shows it. */
data class RemoteFileRow(
    val fileName: String,
    val sizeBytes: Long,
    val modifiedAt: Long?,
    val encrypted: Boolean,
)

data class PendingImport(
    val uri: Uri,
    val info: BackupDocumentInfo,
    /** Set when the file was staged in the cache by a remote restore; deleted when done. */
    val stagedFile: File? = null,
)

data class BackupSettingsUiState(
    val localDirectoryLabel: String? = null,
    val localConfigured: Boolean = false,
    val localAuthorized: Boolean = false,
    val localLastSuccessAt: Long? = null,
    val localLastFailure: com.example.privatenotes.data.backup.BackupFailure? = null,
    val localWorkState: WorkInfo.State? = null,
    val webDavBaseUrlInput: String = "",
    val webDavUsernameInput: String = "",
    val webDavRemoteDirectoryInput: String = "",
    val webDavPasswordInput: String = "",
    val webDavPasswordSaved: Boolean = false,
    val remoteConfigured: Boolean = false,
    val remoteLastSuccessAt: Long? = null,
    val remoteLastFailure: com.example.privatenotes.data.backup.BackupFailure? = null,
    val remoteWorkState: WorkInfo.State? = null,
    val operation: BackupOperation? = null,
    val message: BackupNotice? = null,
    val pendingImport: PendingImport? = null,
    val importReport: ImportOutcome? = null,
    /** Remote files offered for restore, newest first. */
    val remoteFiles: List<RemoteFileRow>? = null,
    val autoSyncEnabled: Boolean = false,
    /** 0 means keep every local backup file. */
    val localRetention: Int = BackupStatus.DEFAULT_LOCAL_RETENTION,
) {
    val busy: Boolean get() = operation != null
    val canBackupLocal: Boolean get() = localConfigured && localAuthorized
}

/**
 * State for the backup settings page (§9.6).
 *
 * Manual encrypted backups run here, with the password held only in this call chain. Scheduled
 * work never receives a password: it is not stored, and WorkManager input data is persisted to
 * disk.
 */
class BackupSettingsViewModel(
    private val statusStore: BackupStatusStore,
    private val directory: BackupDirectory,
    private val localBackupManager: LocalBackupManager,
    private val webDavBackupManager: WebDavBackupManager,
    private val importer: BackupImporter,
    private val scheduler: BackupScheduler,
    /** Where a downloaded backup is staged before being merged; the app cache directory. */
    private val cacheDirProvider: () -> File = { File(System.getProperty("java.io.tmpdir") ?: ".") },
) : ViewModel() {

    private val _state = MutableStateFlow(BackupSettingsUiState())
    val uiState: StateFlow<BackupSettingsUiState> = _state.asStateFlow()

    private var formInitialised = false

    init {
        applyStatus(statusStore.status.value)
        formInitialised = true
        observeStatusAndWork()
    }

    fun onBaseUrlChange(value: String) {
        _state.update { it.copy(webDavBaseUrlInput = value) }
    }

    fun onUsernameChange(value: String) {
        _state.update { it.copy(webDavUsernameInput = value) }
    }

    fun onRemoteDirectoryChange(value: String) {
        _state.update { it.copy(webDavRemoteDirectoryInput = value) }
    }

    fun onPasswordChange(value: String) {
        _state.update { it.copy(webDavPasswordInput = value) }
    }

    fun refreshAuthorization() {
        _state.update {
            it.copy(localAuthorized = it.localConfigured && runCatching { directory.isAuthorized() }.getOrDefault(false))
        }
    }

    fun onLocalDirectorySelected(uri: String, displayName: String?) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { statusStore.setLocalDirectory(uri, displayName) }
                runCatching { scheduler.ensurePeriodicWork() }
                refreshAuthorization()
                _state.update { it.copy(message = BackupNotice.Info("备份目录已设置")) }
            } catch (e: Exception) {
                noticeFailure(asBackupException(e))
            }
        }
    }

    fun onDirectoryPermissionFailed() {
        _state.update {
            it.copy(
                localAuthorized = false,
                message = BackupNotice.Failure(BackupErrorKind.PERMISSION_LOST, "请重新选择备份目录"),
            )
        }
    }

    fun saveWebDav() {
        if (_state.value.busy) return
        val current = _state.value
        val password = current.webDavPasswordInput.toCharArray()
        viewModelScope.launch {
            setOperation(BackupOperation.SAVING_CONFIG)
            try {
                WebDavConfigValidator.validate(
                    current.webDavBaseUrlInput,
                    current.webDavUsernameInput,
                    current.webDavRemoteDirectoryInput,
                )
                val normalizedDirectory = WebDavConfigValidator.normalizedRemoteDirectory(
                    current.webDavRemoteDirectoryInput,
                )
                withContext(Dispatchers.IO) {
                    statusStore.saveWebDav(
                        baseUrl = current.webDavBaseUrlInput.trim(),
                        username = current.webDavUsernameInput.trim(),
                        remoteDirectory = normalizedDirectory,
                        password = password,
                    )
                }
                _state.update { it.copy(webDavPasswordInput = "") }
                runCatching { scheduler.ensurePeriodicWork() }
                val hasPassword = statusStore.webDavSettings().hasPassword
                _state.update {
                    it.copy(
                        message = BackupNotice.Info(
                            if (hasPassword) "WebDAV 配置已保存" else "WebDAV 配置已保存；请填写密码后再测试连接",
                        ),
                    )
                }
            } catch (e: Exception) {
                noticeFailure(asBackupException(e))
            } finally {
                password.fill('\u0000')
                setOperation(null)
            }
        }
    }

    fun testConnection() {
        if (_state.value.busy) return
        val current = _state.value
        val password = current.webDavPasswordInput.toCharArray()
        viewModelScope.launch {
            setOperation(BackupOperation.TESTING_CONNECTION)
            try {
                WebDavConfigValidator.validate(
                    current.webDavBaseUrlInput,
                    current.webDavUsernameInput,
                    current.webDavRemoteDirectoryInput,
                )
                val saved = statusStore.webDavSettings()
                val destinationChanged = current.webDavBaseUrlInput.trim() != saved.baseUrl ||
                    current.webDavUsernameInput.trim() != saved.username ||
                    WebDavConfigValidator.normalizedRemoteDirectory(current.webDavRemoteDirectoryInput) !=
                    saved.remoteDirectory
                if (password.isEmpty() && destinationChanged) {
                    throw BackupException(
                        BackupErrorKind.INVALID_CONFIG,
                        "请先输入新服务器的密码",
                    )
                }
                withContext(Dispatchers.IO) {
                    webDavBackupManager.testConnection(
                        baseUrl = current.webDavBaseUrlInput,
                        username = current.webDavUsernameInput,
                        remoteDirectory = current.webDavRemoteDirectoryInput,
                        draftPassword = password,
                    )
                }
                _state.update { it.copy(message = BackupNotice.Info("连接成功，远端目录可访问")) }
            } catch (e: Exception) {
                noticeFailure(asBackupException(e))
            } finally {
                password.fill('\u0000')
                setOperation(null)
            }
        }
    }

    fun backupLocal(password: CharArray?) {
        if (_state.value.busy) {
            password?.fill('\u0000')
            return
        }
        viewModelScope.launch {
            setOperation(BackupOperation.LOCAL_BACKUP)
            try {
                val result = withContext(Dispatchers.IO) { localBackupManager.backup(password) }
                _state.update {
                    it.copy(
                        message = BackupNotice.Info(
                            buildString {
                                append("本地备份完成：")
                                append(result.fileName)
                                if (result.encrypted) append("（已加密）")
                            },
                        ),
                    )
                }
            } catch (e: Exception) {
                noticeFailure(asBackupException(e))
            } finally {
                password?.fill('\u0000')
                setOperation(null)
            }
        }
    }

    fun backupRemote(password: CharArray?) {
        if (_state.value.busy) {
            password?.fill('\u0000')
            return
        }
        viewModelScope.launch {
            setOperation(BackupOperation.REMOTE_BACKUP)
            try {
                val result = withContext(Dispatchers.IO) { webDavBackupManager.backup(password) }
                val verification = if (result.verification == WebDavVerification.FULL) "已校验内容" else "已校验长度"
                _state.update {
                    it.copy(
                        message = BackupNotice.Info(
                            "WebDAV 备份完成：${result.fileName}（$verification）",
                        ),
                    )
                }
            } catch (e: Exception) {
                noticeFailure(asBackupException(e))
            } finally {
                password?.fill('\u0000')
                setOperation(null)
            }
        }
    }

    /** Turns the after-each-article upload on or off. Persisted immediately. */
    fun setAutoSyncEnabled(enabled: Boolean) {
        statusStore.setAutoSyncEnabled(enabled)
    }

    fun setLocalRetention(count: Int) {
        statusStore.setLocalRetention(count)
    }

    /** Asks the server what it has, newest first, so the user can choose what to merge back. */
    fun listRemoteBackups() {
        if (_state.value.busy) return
        viewModelScope.launch {
            setOperation(BackupOperation.LISTING_REMOTE)
            try {
                val files = withContext(Dispatchers.IO) { webDavBackupManager.listRemote() }
                _state.update {
                    it.copy(
                        remoteFiles = files.map { file ->
                            RemoteFileRow(
                                fileName = file.fileName,
                                sizeBytes = file.sizeBytes,
                                modifiedAt = file.lastModifiedAt,
                                encrypted = file.fileName.endsWith(".pnb"),
                            )
                        },
                        message = if (files.isEmpty()) {
                            BackupNotice.Info("服务器上还没有备份")
                        } else {
                            null
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupException) {
                _state.update { it.copy(message = BackupNotice.Failure(e.kind, e.detail ?: e.message)) }
            } catch (e: Exception) {
                _state.update { it.copy(message = BackupNotice.Failure(BackupErrorKind.UNKNOWN, e.message)) }
            } finally {
                setOperation(null)
            }
        }
    }

    fun dismissRemoteFiles() {
        _state.update { it.copy(remoteFiles = null) }
    }

    /**
     * Downloads one remote backup into the cache and hands it to the normal import path.
     *
     * The merge rules are the importer's, unchanged: this only removes the "export to a file and
     * pick it with the file picker" step. The pick in the remote-files dialog is the
     * confirmation, and a password typed there is reused for an encrypted file - asking for the
     * same password twice in a row would only train the user to retype without thinking. A
     * missing or wrong password falls back to the same prompt the local import flow uses.
     */
    fun downloadAndImport(fileName: String, password: CharArray?) {
        if (_state.value.busy) {
            password?.fill('\u0000')
            return
        }
        _state.update { it.copy(remoteFiles = null) }
        viewModelScope.launch {
            setOperation(BackupOperation.DOWNLOADING_REMOTE)
            // Staging holds one restore at a time: leftovers from an abandoned flow are removed
            // here, and the current file itself once the import reaches a terminal state.
            clearStaging()
            val staged = try {
                withContext(Dispatchers.IO) {
                    val bytes = webDavBackupManager.download(fileName)
                    val file = File(cacheDirProvider(), fileName.substringAfterLast('/'))
                    file.writeBytes(bytes)
                    file
                }
            } catch (e: CancellationException) {
                password?.fill('\u0000')
                throw e
            } catch (e: Exception) {
                noticeFailure(asBackupException(e))
                setOperation(null)
                return@launch
            }
            try {
                setOperation(BackupOperation.INSPECTING_IMPORT)
                val uri = Uri.fromFile(staged)
                val pending = PendingImport(uri, inspectInfo(uri), stagedFile = staged)
                if (pending.info.kind == BackupFileKind.ENCRYPTED && (password == null || password.isEmpty())) {
                    _state.update { it.copy(pendingImport = pending) }
                    setOperation(null)
                } else {
                    performImport(pending, password)
                }
            } catch (e: CancellationException) {
                password?.fill('\u0000')
                throw e
            } catch (e: Exception) {
                noticeFailure(asBackupException(e))
                setOperation(null)
                runCatching { staged.delete() }
            }
        }
    }

    fun inspectImport(uri: Uri) {
        if (_state.value.busy) return
        viewModelScope.launch {
            setOperation(BackupOperation.INSPECTING_IMPORT)
            try {
                _state.update { it.copy(pendingImport = PendingImport(uri, inspectInfo(uri))) }
            } catch (e: Exception) {
                noticeFailure(asBackupException(e))
            } finally {
                setOperation(null)
            }
        }
    }

    /** Reads and validates the envelope so the UI knows whether a password is needed. */
    private suspend fun inspectInfo(uri: Uri): BackupDocumentInfo {
        val info = withContext(Dispatchers.IO) { importer.inspect(uri) }
        if (info.kind == BackupFileKind.UNKNOWN) {
            throw BackupException(
                BackupErrorKind.INVALID_FORMAT,
                "所选文件不是私密笔记备份",
            )
        }
        return info
    }

    fun confirmImport(password: CharArray?) {
        val pending = _state.value.pendingImport ?: run {
            password?.fill('\u0000')
            return
        }
        if (_state.value.busy) {
            password?.fill('\u0000')
            return
        }
        // Close the dialog before the merge: it can take a while, and leaving an enabled
        // "import" button over a running transaction invites a second press.
        _state.update { it.copy(pendingImport = null) }
        viewModelScope.launch { performImport(pending, password) }
    }

    /**
     * Runs the merge for one pending import.
     *
     * Returns true when the flow reached a terminal state - success, or a failure retyping
     * cannot fix - and false when the password was wrong and the prompt has been put back up
     * for another attempt against the same file.
     */
    private suspend fun performImport(pending: PendingImport, password: CharArray?): Boolean {
        setOperation(BackupOperation.IMPORTING)
        val terminal = try {
            val outcome = withContext(Dispatchers.IO) { importer.import(pending.uri, password) }
            _state.update {
                it.copy(
                    importReport = outcome,
                    message = BackupNotice.Info("导入完成"),
                )
            }
            true
        } catch (e: Exception) {
            val failure = asBackupException(e)
            val retypeable = failure.kind == BackupErrorKind.WRONG_PASSWORD_OR_CORRUPT &&
                pending.info.kind == BackupFileKind.ENCRYPTED
            if (retypeable) {
                // Let the user retype a mistyped password without re-picking the file.
                _state.update { it.copy(pendingImport = pending) }
            }
            noticeFailure(failure)
            !retypeable
        } finally {
            password?.fill('\u0000')
            setOperation(null)
        }
        // A downloaded backup lives only until its flow ends; a retype keeps the file.
        if (terminal) pending.stagedFile?.let { staged -> runCatching { staged.delete() } }
        return terminal
    }

    /** The staging directory only ever holds downloaded restore files; keep nothing stale. */
    private fun clearStaging() {
        cacheDirProvider().listFiles()?.forEach { file ->
            if (file.isFile && BackupFileNames.isBackupFile(file.name)) {
                runCatching { file.delete() }
            }
        }
    }

    fun cancelImport() {
        _state.update { it.copy(pendingImport = null) }
    }

    fun dismissImportReport() {
        _state.update { it.copy(importReport = null) }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun observeStatusAndWork() {
        viewModelScope.launch {
            combine(
                statusStore.status,
                scheduler.observeLocalWork().catch { emit(null) },
                scheduler.observeRemoteWork().catch { emit(null) },
            ) { status, localWork, remoteWork ->
                Triple(status, localWork, remoteWork)
            }
                .collect { (status, localWork, remoteWork) ->
                    applyStatus(status, localWork, remoteWork)
                }
        }
    }

    private fun applyStatus(
        status: BackupStatus,
        localWork: WorkInfo.State? = _state.value.localWorkState,
        remoteWork: WorkInfo.State? = _state.value.remoteWorkState,
    ) {
        _state.update { current ->
            current.copy(
                localDirectoryLabel = status.localDirectoryName?.takeIf { it.isNotBlank() }
                    ?: status.localDirectoryUri?.let { Uri.parse(it).lastPathSegment },
                localConfigured = status.localConfigured,
                localAuthorized = status.localConfigured &&
                    runCatching { directory.isAuthorized() }.getOrDefault(false),
                localLastSuccessAt = status.localLastSuccessAt,
                localLastFailure = status.localLastFailure,
                localWorkState = localWork,
                webDavBaseUrlInput = if (formInitialised) current.webDavBaseUrlInput else status.webDavBaseUrl,
                webDavUsernameInput = if (formInitialised) current.webDavUsernameInput else status.webDavUsername,
                webDavRemoteDirectoryInput = if (formInitialised) {
                    current.webDavRemoteDirectoryInput
                } else {
                    status.webDavRemoteDirectory
                },
                webDavPasswordSaved = status.webDavPasswordSaved,
                remoteConfigured = status.webDavConfigured,
                remoteLastSuccessAt = status.remoteLastSuccessAt,
                remoteLastFailure = status.remoteLastFailure,
                remoteWorkState = remoteWork,
                autoSyncEnabled = status.autoSyncEnabled,
                localRetention = status.localRetention,
            )
        }
    }

    private fun setOperation(operation: BackupOperation?) {
        _state.update { it.copy(operation = operation) }
    }

    private fun noticeFailure(e: BackupException) {
        _state.update {
            it.copy(message = BackupNotice.Failure(e.kind, (e.detail ?: e.message)?.take(500)))
        }
    }

    private fun asBackupException(e: Exception): BackupException {
        if (e is CancellationException) throw e
        return e as? BackupException ?: BackupException(BackupErrorKind.UNKNOWN, e.message ?: "未知错误", e)
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                BackupSettingsViewModel(
                    statusStore = container.backupStatusStore,
                    directory = container.backupDirectory,
                    localBackupManager = container.localBackupManager,
                    webDavBackupManager = container.webDavBackupManager,
                    importer = container.backupImporter,
                    scheduler = container.backupScheduler,
                    cacheDirProvider = { container.cacheDirectory },
                )
            }
        }
    }
}
