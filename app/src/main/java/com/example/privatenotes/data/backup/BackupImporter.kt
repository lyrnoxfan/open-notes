package com.example.privatenotes.data.backup

import android.net.Uri
import kotlinx.coroutines.CancellationException

data class ImportOutcome(
    val report: MergeReport,
    val safetyBackupFileName: String?,
)

/**
 * Import orchestration (§9.5).
 *
 * Order matters: read and fully validate first, then take a safety backup of the current
 * database, then merge in one transaction. A rejected or corrupt file therefore never touches
 * either the database or the backup directory.
 */
class BackupImporter(
    private val source: BackupDocumentSource,
    private val archive: BackupArchiveService,
    private val merger: BackupMerger,
    private val localBackupManager: LocalBackupManager,
) {

    suspend fun import(uri: Uri, password: CharArray?): ImportOutcome {
        val bytes = source.read(uri)
        val document = archive.read(bytes, password)

        val safetyBackup = if (localBackupManager.isConfigured()) {
            try {
                localBackupManager.backup(password = null).fileName
            } catch (e: BackupException) {
                throw BackupException(
                    BackupErrorKind.SAFETY_BACKUP_FAILED,
                    "Import was cancelled because the pre-import safety backup failed",
                    e,
                    detail = e.detail ?: e.message,
                )
            }
        } else {
            null
        }

        val report = try {
            merger.merge(document)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            // The merge runs in a Room transaction; any throwable rolled it back. Report it as
            // a storage-level failure instead of pretending the import was partial.
            throw BackupException(BackupErrorKind.STORAGE, "Import failed and was rolled back", e)
        }
        return ImportOutcome(report = report, safetyBackupFileName = safetyBackup)
    }

    /** Used by the settings screen to decide whether to prompt for a password. */
    suspend fun inspect(uri: Uri): BackupDocumentInfo = source.inspect(uri)
}
