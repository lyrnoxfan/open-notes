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
 *
 * The safety backup is encrypted with the import password when one was given: the user who
 * just typed a password for a backup has said what they think a full copy of their notes
 * should look like on disk, and a silent plaintext copy would break that.
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
                // A copy: the manager zeroes the array it is handed, and the password still
                // belongs to the caller's call chain.
                localBackupManager.backup(password?.copyOf()).fileName
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
