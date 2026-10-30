package com.example.privatenotes.data.backup

import com.example.privatenotes.data.repository.TimeSource
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType

data class RemoteBackupResult(
    val fileName: String,
    val encrypted: Boolean,
    val sizeBytes: Long,
    val verification: WebDavVerification,
)

/**
 * One WebDAV backup run (§9.3).
 *
 * Upload and verification are one unit: the success timestamp is written only after the remote
 * object has been read back (or, for servers that refuse GET, at least length-checked).
 */
class WebDavBackupManager(
    private val archive: BackupArchiveService,
    private val statusStore: BackupStatusStore,
    private val clientFactory: (WebDavSettings, String) -> WebDavRemote,
    private val timeSource: TimeSource,
) {

    fun isConfigured(): Boolean = statusStore.status.value.webDavConfigured

    suspend fun testConnection(
        baseUrl: String,
        username: String,
        remoteDirectory: String,
        draftPassword: CharArray?,
    ) {
        WebDavConfigValidator.validate(baseUrl, username, remoteDirectory)
        val password = resolvePassword(draftPassword)
        val settings = WebDavSettings(
            baseUrl = baseUrl.trim(),
            username = username.trim(),
            remoteDirectory = WebDavConfigValidator.normalizedRemoteDirectory(remoteDirectory),
            hasPassword = true,
        )
        clientFactory(settings, password).testConnection()
    }

    suspend fun backup(password: CharArray? = null): RemoteBackupResult {
        if (!statusStore.status.value.webDavConfigured) {
            password?.fill('\u0000')
            throw BackupException(BackupErrorKind.NOT_CONFIGURED, "WebDAV is not configured")
        }
        try {
            val settings = statusStore.webDavSettings()
            WebDavConfigValidator.validate(settings.baseUrl, settings.username, settings.remoteDirectory)
            val credentials = statusStore.webDavCredentials()
            archive.prepare(password).use { archived ->
            val fileName = archived.fileName
            val client = clientFactory(settings, credentials.password)
            val uploaded = client.uploadFile(
                fileName = fileName,
                file = archived.file,
                contentType = if (archived.encrypted || archived.fileName.endsWith(".pns")) {
                    "application/octet-stream".toMediaType()
                } else {
                    "application/json".toMediaType()
                },
            )
            val verification = try {
                client.verifyFile(fileName, archived.file)
            } catch (e: BackupException) {
                // A remote copy that failed verification is worse than no copy: a later restore
                // could pick it. Remove only this file, and only after it was just uploaded.
                client.deleteQuietly(fileName)
                throw e
            }
            statusStore.markRemoteSuccess(timeSource.nowMillis())
            return RemoteBackupResult(
                fileName = uploaded.remoteFileName,
                encrypted = archived.encrypted,
                sizeBytes = uploaded.sizeBytes,
                verification = verification,
            )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupException) {
            statusStore.markRemoteFailure(e.kind, timeSource.nowMillis(), e.detail ?: e.message)
            throw e
        } catch (e: Exception) {
            statusStore.markRemoteFailure(BackupErrorKind.UNKNOWN, timeSource.nowMillis(), e.message)
            throw BackupException(BackupErrorKind.UNKNOWN, "WebDAV backup failed", e)
        } finally {
            password?.fill('\u0000')
        }
    }

    /**
     * Backup files already on the server, newest first.
     *
     * Used by the restore flow: the user picks which one to merge back, rather than the app
     * guessing that the newest file is the one they want.
     */
    suspend fun listRemote(): List<WebDavRemoteFile> {
        if (!statusStore.status.value.webDavConfigured) {
            throw BackupException(BackupErrorKind.NOT_CONFIGURED, "WebDAV is not configured")
        }
        val settings = statusStore.webDavSettings()
        WebDavConfigValidator.validate(settings.baseUrl, settings.username, settings.remoteDirectory)
        val client = clientFactory(settings, statusStore.webDavCredentials().password)
        return client.listBackups()
    }

    /** Downloads one remote backup. The caller decides what to do with the bytes. */
    suspend fun download(fileName: String): ByteArray {
        if (!statusStore.status.value.webDavConfigured) {
            throw BackupException(BackupErrorKind.NOT_CONFIGURED, "WebDAV is not configured")
        }
        val settings = statusStore.webDavSettings()
        WebDavConfigValidator.validate(settings.baseUrl, settings.username, settings.remoteDirectory)
        val client = clientFactory(settings, statusStore.webDavCredentials().password)
        val bytes = client.download(fileName)
        if (bytes.size > BackupLimits.MAX_FILE_BYTES) {
            throw BackupException(BackupErrorKind.INVALID_FORMAT, "远程备份文件过大，拒绝导入")
        }
        return bytes
    }

    suspend fun downloadTo(fileName: String, file: java.io.File) {
        if (!isConfigured()) throw BackupException(BackupErrorKind.NOT_CONFIGURED, "WebDAV is not configured")
        val settings = statusStore.webDavSettings()
        WebDavConfigValidator.validate(settings.baseUrl, settings.username, settings.remoteDirectory)
        clientFactory(settings, statusStore.webDavCredentials().password).downloadTo(fileName, file)
    }

    private fun resolvePassword(draftPassword: CharArray?): String {
        if (!draftPassword.isNullOrEmpty()) return String(draftPassword!!)
        if (!statusStore.status.value.webDavPasswordSaved) {
            throw BackupException(BackupErrorKind.INVALID_CONFIG, "请输入 WebDAV 密码")
        }
        return statusStore.webDavCredentials().password
    }
}
