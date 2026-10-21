package com.lyrnox.opennotes.core.security

import java.io.File

/**
 * Detects a plaintext SQLite database sitting at an encrypted database's path.
 *
 * Every legitimate SQLite file starts with the 16-byte magic string below. A SQLCipher
 * file starts with random salt instead, so the check is exact rather than heuristic.
 */
object DatabaseFileGuard {

    private val PLAINTEXT_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    /** Bytes to read from the head of a file to identify it. */
    const val SNIFF_BYTES = 16

    fun isPlaintextSqlite(file: File): Boolean {
        if (!file.isFile) return false
        if (file.length() < PLAINTEXT_MAGIC.size) return false
        val head = ByteArray(PLAINTEXT_MAGIC.size)
        file.inputStream().use { stream ->
            var read = 0
            while (read < head.size) {
                val n = stream.read(head, read, head.size - read)
                if (n <= 0) return false
                read += n
            }
        }
        return head.contentEquals(PLAINTEXT_MAGIC)
    }

    /**
     * Called before the database is opened. A plaintext file at that path means an older or
     * broken build wrote unencrypted data; refusing loudly is the only safe response.
     */
    fun requireNoPlaintextDatabase(file: File) {
        if (isPlaintextSqlite(file)) {
            throw PlaintextDatabaseException(
                "Plaintext SQLite database found at ${file.name}. Refusing to open it; the store must be " +
                    "encrypted from creation, so this file has to be removed or migrated deliberately.",
            )
        }
    }

    /** Called once the database is open: the file must not look like plaintext SQLite. */
    fun requireEncryptedDatabase(file: File) {
        if (!file.isFile) {
            throw PlaintextDatabaseException("Database file ${file.name} does not exist after opening")
        }
        if (isPlaintextSqlite(file)) {
            throw PlaintextDatabaseException(
                "Database ${file.name} was written as plaintext SQLite despite the SQLCipher factory",
            )
        }
    }
}
