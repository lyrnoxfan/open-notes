package com.example.privatenotes.data.backup

import com.example.privatenotes.data.repository.TimeSource
import kotlinx.coroutines.CancellationException

data class LocalBackupResult(
    val fileName: String,
    val encrypted: Boolean,
    val sizeBytes: Long,
)

/**
 * One local backup run (§9.2).
 *
 * The sequence is: snapshot, serialise, optionally encrypt, write, read back and compare,
 * then and only then record success. Old files are pruned afterwards and only beyond the
 * retention window; the newest backup is never deleted to make room for itself.
 */
class LocalBackupManager(
    private val archive: BackupArchiveService,
    private val directory: BackupDirectory,
    private val statusStore: BackupStatusStore,
    private val timeSource: TimeSource,
    private val retention: Int = DEFAULT_RETENTION,
) {

    fun isConfigured(): Boolean = directory.isConfigured()

    suspend fun backup(password: CharArray? = null): LocalBackupResult {
        if (!directory.isConfigured()) {
            password?.fill('\u0000')
            throw BackupException(BackupErrorKind.NOT_CONFIGURED, "No local backup directory is configured")
        }
        try {
            val archived = archive.create(password)
            val stored = directory.writeBackup(
                baseName = archived.fileName,
                mimeType = if (archived.encrypted) MIME_ENCRYPTED else MIME_PLAINTEXT,
                bytes = archived.bytes,
            )
            statusStore.markLocalSuccess(timeSource.nowMillis())
            pruneOldBackups()
            return LocalBackupResult(
                fileName = stored.name,
                encrypted = archived.encrypted,
                sizeBytes = stored.sizeBytes,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupException) {
            statusStore.markLocalFailure(e.kind, timeSource.nowMillis(), e.detail ?: e.message)
            throw e
        } catch (e: Exception) {
            statusStore.markLocalFailure(BackupErrorKind.UNKNOWN, timeSource.nowMillis(), e.message)
            throw BackupException(BackupErrorKind.UNKNOWN, "Local backup failed", e)
        } finally {
            password?.fill('\u0000')
        }
    }

    /**
     * Retention failure must not turn a successful, verified backup into a failure. The new
     * file is already the newest and is never in the deleted range.
     */
    private suspend fun pruneOldBackups() {
        try {
            val files = directory.listBackupFiles().sortedByDescending { it.name }
            files.drop(retention).forEach { directory.delete(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Retention failure must not turn a successful, verified backup into a failure.
        }
    }

    companion object {
        /** 30 daily files is a month of history without unbounded growth. */
        const val DEFAULT_RETENTION = 30

        private const val MIME_PLAINTEXT = "application/json"
        private const val MIME_ENCRYPTED = "application/octet-stream"
    }
}
