package com.example.privatenotes.data.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest

data class BackupDirectoryEntry(
    val name: String,
    val documentId: String,
    val sizeBytes: Long,
    val lastModified: Long,
)

data class StoredBackupFile(
    val name: String,
    val sizeBytes: Long,
)

/**
 * The small part of SAF the backup code needs.
 *
 * An interface keeps the daily job testable without a DocumentsProvider and makes the
 * "permission was revoked" case explicit instead of a generic IOException.
 */
interface BackupDirectory {
    fun displayName(): String?
    fun isConfigured(): Boolean
    fun isAuthorized(): Boolean
    suspend fun listBackupFiles(): List<BackupDirectoryEntry>
    suspend fun writeBackup(baseName: String, mimeType: String, bytes: ByteArray): StoredBackupFile
    suspend fun writeBackupFile(baseName: String, mimeType: String, file: java.io.File): StoredBackupFile {
        require(file.length() <= BackupLimits.MAX_FILE_BYTES) { "此备份目标未实现流式写入" }
        return writeBackup(baseName, mimeType, file.readBytes())
    }
    suspend fun delete(entry: BackupDirectoryEntry)
}

/**
 * SAF implementation backed by a persisted tree URI (§9.2).
 *
 * Writes are verified by reading the document back and comparing a SHA-256, not by trusting
 * that `flush()` reached the provider. A backup that cannot be read is not a backup.
 */
class SafBackupDirectory(
    context: Context,
    private val statusStore: BackupStatusStore,
) : BackupDirectory {

    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    override fun displayName(): String? = statusStore.status.value.localDirectoryName

    override fun isConfigured(): Boolean = treeUri() != null

    override fun isAuthorized(): Boolean {
        val tree = treeUri() ?: return false
        return try {
            resolver.persistedUriPermissions.any {
                it.uri == tree && it.isReadPermission && it.isWritePermission
            }
        } catch (e: SecurityException) {
            false
        }
    }

    override suspend fun listBackupFiles(): List<BackupDirectoryEntry> = withContext(Dispatchers.IO) {
        val tree = requireAuthorizedTree()
        val root = rootDocumentUri(tree)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getDocumentId(root),
        )
        val entries = mutableListOf<BackupDirectoryEntry>()
        try {
            resolver.query(
                childrenUri,
                arrayOf(
                    Document.COLUMN_DOCUMENT_ID,
                    Document.COLUMN_DISPLAY_NAME,
                    Document.COLUMN_SIZE,
                    Document.COLUMN_LAST_MODIFIED,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(Document.COLUMN_SIZE)
                val modifiedIndex = cursor.getColumnIndex(Document.COLUMN_LAST_MODIFIED)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex) ?: continue
                    if (!BackupFileNames.isBackupFile(name)) continue
                    entries += BackupDirectoryEntry(
                        name = name,
                        documentId = cursor.getString(idIndex),
                        sizeBytes = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else -1L,
                        lastModified = if (modifiedIndex >= 0) cursor.getLong(modifiedIndex) else 0L,
                    )
                }
            }
        } catch (e: SecurityException) {
            throw permissionLost(e)
        } catch (e: IOException) {
            throw storageFailure("Could not list the backup directory", e)
        }
        entries.sortedByDescending { it.name }
    }

    override suspend fun writeBackup(baseName: String, mimeType: String, bytes: ByteArray): StoredBackupFile {
        val file = java.io.File.createTempFile("legacy-backup-", ".tmp", appContext.cacheDir)
        return try { file.writeBytes(bytes); writeBackupFile(baseName, mimeType, file) } finally { file.delete() }
    }

    override suspend fun writeBackupFile(
        baseName: String,
        mimeType: String,
        file: java.io.File,
    ): StoredBackupFile = withContext(Dispatchers.IO) {
        val tree = requireAuthorizedTree()
        val root = rootDocumentUri(tree)
        val existingNames = try {
            listBackupFiles().mapTo(HashSet()) { it.name }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptySet<String>()
        }
        var candidate = baseName
        var suffix = 1
        while (candidate in existingNames) {
            suffix++
            candidate = BackupFileNames.withCollisionSuffix(baseName, suffix)
        }

        val documentUri = try {
            DocumentsContract.createDocument(resolver, root, mimeType, candidate)
        } catch (e: SecurityException) {
            throw permissionLost(e)
        } catch (e: IOException) {
            throw storageFailure("Could not create the backup file", e)
        } ?: throw storageFailure("The document provider refused to create the backup file")

        try {
            resolver.openOutputStream(documentUri, "w")?.use { output ->
                file.inputStream().use { it.copyTo(output) }
                output.flush()
            } ?: throw storageFailure("Could not open the backup file for writing")

            verifyWrittenFile(documentUri, file)
            StoredBackupFile(name = candidate, sizeBytes = file.length())
        } catch (e: BackupException) {
            runCatching { DocumentsContract.deleteDocument(resolver, documentUri) }
            throw e
        } catch (e: SecurityException) {
            runCatching { DocumentsContract.deleteDocument(resolver, documentUri) }
            throw permissionLost(e)
        } catch (e: IOException) {
            runCatching { DocumentsContract.deleteDocument(resolver, documentUri) }
            throw storageFailure("Could not write the backup file", e)
        }
    }

    override suspend fun delete(entry: BackupDirectoryEntry): Unit = withContext(Dispatchers.IO) {
        val tree = requireAuthorizedTree()
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, entry.documentId)
        try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (e: SecurityException) {
            throw permissionLost(e)
        } catch (e: IOException) {
            throw storageFailure("Could not delete an old backup", e)
        }
    }

    private fun verifyWrittenFile(documentUri: Uri, expected: java.io.File) {
        val expectedDigest = expected.inputStream().use { sha256Stream(it) }
        val actual = resolver.openInputStream(documentUri)?.use { sha256Stream(it) }
            ?: throw storageFailure("Could not reopen backup for verification")
        if (actual.first != expected.length() || !actual.second.contentEquals(expectedDigest.second)) {
            throw storageFailure("Backup file did not read back identical; the write was incomplete")
        }
    }

    private fun requireAuthorizedTree(): Uri {
        val tree = treeUri() ?: throw BackupException(
            BackupErrorKind.NOT_CONFIGURED,
            "No backup directory has been selected",
        )
        if (!isAuthorized()) {
            throw BackupException(
                BackupErrorKind.PERMISSION_LOST,
                "The backup directory permission is no longer valid",
            )
        }
        return tree
    }

    private fun treeUri(): Uri? =
        statusStore.status.value.localDirectoryUri
            ?.takeIf { it.isNotBlank() }
            ?.let { Uri.parse(it) }

    private fun rootDocumentUri(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private fun permissionLost(cause: Throwable): BackupException = BackupException(
        BackupErrorKind.PERMISSION_LOST,
        "The backup directory permission is no longer valid",
        cause,
    )
    private fun storageFailure(message: String, cause: Throwable? = null): BackupException =
        BackupException(BackupErrorKind.STORAGE, message, cause, retryable = true)
}

private fun ByteArray.sha256(): ByteArray = MessageDigest.getInstance("SHA-256").digest(this)
