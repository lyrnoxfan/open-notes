package com.example.privatenotes.data.backup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

data class BackupDocumentInfo(
    val displayName: String?,
    val sizeBytes: Long?,
    val kind: BackupFileKind,
)

/**
 * Reads a user-picked backup file through SAF.
 *
 * The size cap is enforced while reading, not only from the provider's reported size: a
 * provider can lie or report nothing, and an unbounded read of a hostile file would end in an
 * OOM rather than a useful error.
 */
interface BackupDocumentSource {
    suspend fun inspect(uri: Uri): BackupDocumentInfo
    suspend fun open(uri: Uri): java.io.InputStream = java.io.ByteArrayInputStream(read(uri))
    suspend fun read(uri: Uri): ByteArray
}

class AndroidBackupDocumentSource(context: Context) : BackupDocumentSource {

    private val resolver = context.applicationContext.contentResolver

    override suspend fun inspect(uri: Uri): BackupDocumentInfo = withContext(Dispatchers.IO) {
        var name: String? = null
        var size: Long? = null
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex >= 0) name = cursor.getString(nameIndex)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                    }
                }
        } catch (e: SecurityException) {
            throw storageFailure("没有读取该文件的权限", e)
        } catch (e: IOException) {
            throw storageFailure("无法读取该文件", e)
        }

        val head = try {
            resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(256)
                var read = 0
                while (read < buffer.size) {
                    val n = input.read(buffer, read, buffer.size - read)
                    if (n <= 0) break
                    read += n
                }
                buffer.copyOf(read)
            } ?: throw storageFailure("无法打开该文件")
        } catch (e: SecurityException) {
            throw storageFailure("没有读取该文件的权限", e)
        } catch (e: IOException) {
            throw storageFailure("无法读取该文件", e)
        }

        BackupDocumentInfo(
            displayName = name,
            sizeBytes = size,
            kind = when {
                StreamingMediaArchive.isEncryptedArchive(head) || BackupCrypto.isEncrypted(head) -> BackupFileKind.ENCRYPTED
                StreamingMediaArchive.isArchive(head) -> BackupFileKind.PLAINTEXT_JSON
                String(head, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n').startsWith("{") ->
                    BackupFileKind.PLAINTEXT_JSON
                else -> BackupFileKind.UNKNOWN
            },
        )
    }

    override suspend fun open(uri: Uri): java.io.InputStream = resolver.openInputStream(uri) ?: throw storageFailure("无法打开该文件")

    override suspend fun read(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        try {
            resolver.openInputStream(uri)?.use { input ->
                readBoundedBackupDocument(input)
            } ?: throw storageFailure("无法打开该文件")
        } catch (e: SecurityException) {
            throw storageFailure("没有读取该文件的权限", e)
        } catch (e: IOException) {
            throw storageFailure("无法读取该文件", e)
        }
    }

    private fun storageFailure(message: String, cause: Throwable? = null): BackupException =
        BackupException(BackupErrorKind.STORAGE, message, cause)
}


/** Reads legacy JSON/PNB only up to its explicit safe in-memory import boundary. */
internal fun readBoundedBackupDocument(
    input: java.io.InputStream,
    maxBytes: Long = BackupLimits.MAX_FILE_BYTES,
): ByteArray {
    require(maxBytes >= 0) { "maxBytes must be non-negative" }
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        if (total > maxBytes - count) {
            throw BackupException(
                BackupErrorKind.INVALID_FORMAT,
                "备份文件超过 ${maxBytes} 字节，无法导入",
            )
        }
        output.write(buffer, 0, count)
        total += count
    }
    return output.toByteArray()
}
