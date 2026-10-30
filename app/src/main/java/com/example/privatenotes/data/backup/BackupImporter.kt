package com.example.privatenotes.data.backup

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.FileInputStream

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
    private val streamingProvider: (() -> StreamingMediaArchive)? = null,
    private val stagingDirectory: File = File(System.getProperty("java.io.tmpdir")),
) {

    suspend fun import(uri: Uri, password: CharArray?): ImportOutcome {
        val head = source.open(uri).use { input -> ByteArray(8).also { bytes -> var at = 0; while (at < bytes.size) { val count = input.read(bytes, at, bytes.size - at); if (count <= 0) break; at += count } } }
        val streaming = StreamingMediaArchive.isArchive(head)
        var encryptedJsonTemp: File? = null
        val stagedLegacy = if (streaming) null else {
            if (!stagingDirectory.exists() && !stagingDirectory.mkdirs()) throw BackupException(BackupErrorKind.STORAGE, "Could not create temporary import storage")
            val jsonInput = if (BackupCrypto.isEncrypted(head)) {
                val secret = password ?: throw BackupException(BackupErrorKind.INVALID_FORMAT, "A password is required for an encrypted backup")
                val temp = File.createTempFile("legacy-pnb-", ".json", stagingDirectory)
                encryptedJsonTemp = temp
                source.open(uri).use { encrypted -> BackupCrypto.decryptToTemporaryFile(encrypted, secret, temp) }
                FileInputStream(temp)
            } else source.open(uri)
            try {
                jsonInput.use { input ->
                    try {
                        LegacyJsonMediaStager().stage(input, BackupJson(), stagingDirectory)
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: BackupException) {
                        throw failure
                    } catch (failure: IllegalStateException) {
                        throw BackupException(BackupErrorKind.INVALID_FORMAT, "Backup JSON is malformed", failure)
                    } catch (failure: SerializationException) {
                        throw BackupException(BackupErrorKind.INVALID_FORMAT, "Backup JSON is malformed", failure)
                    } catch (failure: IllegalArgumentException) {
                        throw BackupException(BackupErrorKind.INVALID_FORMAT, "Backup JSON is malformed", failure)
                    }
                }
            } finally { encryptedJsonTemp?.delete(); encryptedJsonTemp = null }
        }

        val safetyBackup = try { if (localBackupManager.isConfigured()) {
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
        } } catch (failure: Throwable) { stagedLegacy?.close(); throw failure }

        try {
            val report = try {
                if (streaming) {
                    val service = streamingProvider?.invoke() ?: throw BackupException(BackupErrorKind.INVALID_FORMAT, "流式备份恢复不可用")
                    try { source.open(uri).use { service.restore(it, password ?: charArrayOf()) } }
                    catch (failure: javax.crypto.AEADBadTagException) { throw BackupException(BackupErrorKind.WRONG_PASSWORD_OR_CORRUPT, "密码错误或备份已损坏", failure) }
                } else merger.mergeStaged(checkNotNull(stagedLegacy))
            } catch (e: CancellationException) { throw e }
            catch (e: BackupException) { throw e }
            catch (e: Exception) { throw BackupException(BackupErrorKind.STORAGE, "Import failed and was rolled back", e) }
            return ImportOutcome(report = report, safetyBackupFileName = safetyBackup)
        } finally { stagedLegacy?.close(); encryptedJsonTemp?.delete() }
    }

    /** Used by the settings screen to decide whether to prompt for a password. */
    suspend fun inspect(uri: Uri): BackupDocumentInfo = source.inspect(uri)
}
