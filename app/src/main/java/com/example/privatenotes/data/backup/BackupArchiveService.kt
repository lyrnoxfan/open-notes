package com.example.privatenotes.data.backup

/**
 * Writes new backups as bounded-memory stream files when the application provider is present.
 * The ByteArray methods are retained for reading legacy JSON/PNB and isolated compatibility tests.
 * New files use PNPLAIN3 (unencrypted) or PNMEDIA3 (authenticated encryption).
 */
class BackupArchiveService(
    private val snapshotReader: BackupSnapshotReader,
    private val json: BackupJson,
    private val appVersionProvider: AppVersionProvider,
    private val streamingProvider: (() -> StreamingMediaArchive)? = null,
    private val cacheDirectory: (() -> java.io.File)? = null,
) {

    /** Disk-backed, bounded-memory archive used by automatic local and remote backups. */
    suspend fun prepare(password: CharArray?): PreparedBackup {
        val file = java.io.File.createTempFile("notes-backup-", ".tmp", cacheDirectory?.invoke())
        try {
            val streaming = streamingProvider?.invoke()
            if (streaming != null) {
                val encrypted = !password.isNullOrEmpty()
                file.outputStream().use { streaming.write(it, password ?: charArrayOf(), appVersionProvider.versionName(), System.currentTimeMillis()) }
                val name = BackupFileNames.create(System.currentTimeMillis(), encrypted).substringBeforeLast('.') + if (encrypted) ".pnm" else ".pns"
                return PreparedBackup(file, name, encrypted)
            }
            // Compatibility fallback for callers without the newer archive provider. Stream
            // legacy schema-2 JSON/PNB directly to disk rather than materializing Base64 payloads.
            file.delete()
            return prepareLegacyJson(password)
        } catch (failure: Throwable) { file.delete(); throw failure }
    }

    /**
     * Create a legacy schema-2 JSON or PNB file using bounded-memory media streams.
     * The ByteArray-returning [create] method remains for small compatibility/test callers only.
     */
    suspend fun prepareLegacyJson(password: CharArray?): PreparedBackup {
        val file = java.io.File.createTempFile("notes-legacy-backup-", ".tmp", cacheDirectory?.invoke())
        val encrypted = !password.isNullOrEmpty()
        try {
            java.io.FileOutputStream(file).use { raw ->
                val limited = LimitedOutputStream(raw, BackupLimits.MAX_FILE_BYTES)
                val payload = if (encrypted) BackupCrypto.encryptingOutputStream(limited, password!!) else limited
                java.io.OutputStreamWriter(payload, Charsets.UTF_8).use { writer ->
                    snapshotReader.writeStreaming(appVersionProvider.versionName(), json, writer)
                }
            }
            if (file.length() > BackupLimits.MAX_FILE_BYTES) {
                throw BackupException(BackupErrorKind.STORAGE, "备份文件超过 ${BackupLimits.MAX_FILE_BYTES} 字节")
            }
            val timestamp = System.currentTimeMillis()
            return PreparedBackup(file, BackupFileNames.create(timestamp, encrypted), encrypted)
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        }
    }

    private class LimitedOutputStream(
        output: java.io.OutputStream,
        private val maximum: Long,
    ) : java.io.FilterOutputStream(output) {
        private var written = 0L
        override fun write(value: Int) {
            ensureCapacity(1)
            out.write(value)
            written++
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            ensureCapacity(length.toLong())
            out.write(bytes, offset, length)
            written += length
        }
        private fun ensureCapacity(additional: Long) {
            if (additional < 0 || written > maximum - additional) {
                throw BackupException(BackupErrorKind.STORAGE, "备份文件超过 ${maximum} 字节")
            }
        }
    }

    suspend fun create(password: CharArray?): ArchivedBackup {
        val document = snapshotReader.read(appVersionProvider.versionName())
        val plaintext = json.encode(document)
        val encrypted = !password.isNullOrEmpty()
        val bytes = if (encrypted) BackupCrypto.encrypt(plaintext, password!!) else plaintext
        if (bytes.size > BackupLimits.MAX_FILE_BYTES) {
            throw BackupException(
                BackupErrorKind.STORAGE,
                "备份文件超过 ${BackupLimits.MAX_FILE_BYTES} 字节，无法保证可恢复",
            )
        }
        return ArchivedBackup(
            document = document,
            bytes = bytes,
            encrypted = encrypted,
        )
    }

    /**
     * Detects the envelope, decrypts when needed, parses and validates.
     *
     * @throws BackupException with [BackupErrorKind.WRONG_PASSWORD_OR_CORRUPT] for a bad
     * password or a tampered ciphertext, and [BackupErrorKind.INVALID_FORMAT] for anything that
     * is neither one of our formats nor valid backup JSON.
     */
    fun read(bytes: ByteArray, password: CharArray?): BackupDocument {
        if (bytes.size > BackupLimits.MAX_FILE_BYTES) {
            throw BackupException(
                BackupErrorKind.INVALID_FORMAT,
                "备份文件超过 ${BackupLimits.MAX_FILE_BYTES} 字节",
            )
        }
        val kind = kindOf(bytes)
        return when (kind) {
            BackupFileKind.ENCRYPTED -> {
                if (password.isNullOrEmpty()) {
                    throw BackupException(
                        BackupErrorKind.WRONG_PASSWORD_OR_CORRUPT,
                        "This backup is encrypted and needs a password",
                    )
                }
                // The null check above is on a custom extension, which does not smart-cast.
                val plaintext = BackupCrypto.decrypt(bytes, password!!)
                json.decode(plaintext)
            }

            BackupFileKind.PLAINTEXT_JSON -> json.decode(bytes)

            BackupFileKind.UNKNOWN -> throw BackupException(
                BackupErrorKind.INVALID_FORMAT,
                "File is neither a JSON backup nor an encrypted backup",
            )
        }
    }

    fun kindOf(bytes: ByteArray): BackupFileKind {
        if (BackupCrypto.isEncrypted(bytes)) return BackupFileKind.ENCRYPTED
        val text = String(bytes, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return if (text.startsWith("{")) BackupFileKind.PLAINTEXT_JSON else BackupFileKind.UNKNOWN
    }
}

data class ArchivedBackup(
    val document: BackupDocument,
    val bytes: ByteArray,
    val encrypted: Boolean,
    val fileName: String = BackupFileNames.create(document.exportedAt, encrypted),
)

enum class BackupFileKind {
    PLAINTEXT_JSON,
    ENCRYPTED,
    UNKNOWN,
}

/**
 * Timestamped names keep older backups addressable; files are never overwritten in place (§9.2).
 *
 * UTC is used on purpose: a local-time name would jump backwards when the user travels, and the
 * backup history is sorted by name.
 */
object BackupFileNames {

    private val formatter = java.time.format.DateTimeFormatter
        .ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(java.time.ZoneOffset.UTC)

    const val PREFIX = "private-notes"

    fun create(exportedAt: Long, encrypted: Boolean, uniqueSuffix: String = randomSuffix()): String {
        val stamp = formatter.format(java.time.Instant.ofEpochMilli(exportedAt))
        return "$PREFIX-$stamp-$uniqueSuffix.${extension(encrypted)}"
    }

    /**
     * A short random component keeps two files created in the same second distinct, including
     * on a WebDAV server where the app cannot list before writing (§9.3).
     */
    fun randomSuffix(): String {
        val bytes = ByteArray(3).also(java.security.SecureRandom()::nextBytes)
        return bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

    fun withCollisionSuffix(fileName: String, index: Int): String {
        if (index <= 1) return fileName
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0) return "$fileName-$index"
        return fileName.substring(0, dot) + "-" + index + fileName.substring(dot)
    }

    fun extension(encrypted: Boolean): String = if (encrypted) "pnb" else "json"

    fun isBackupFile(name: String): Boolean =
        name.startsWith("$PREFIX-") && (name.endsWith(".json") || name.endsWith(".pnb") || name.endsWith(".pnm") || name.endsWith(".pns"))
}

/** Never retained beyond one operation; contains ciphertext when encryption was selected. */
data class PreparedBackup(val file: java.io.File, val fileName: String, val encrypted: Boolean) : java.io.Closeable {
    override fun close() { file.delete() }
}
