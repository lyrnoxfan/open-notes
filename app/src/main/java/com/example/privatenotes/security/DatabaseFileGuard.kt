package com.example.privatenotes.security

import java.io.File

/**
 * Detects a plaintext SQLite database sitting at the encrypted database path.
 *
 * Every plaintext SQLite file starts with the same 16-byte magic string; a SQLCipher file
 * starts with a random salt. The check is exact, not heuristic.
 */
object DatabaseFileGuard {

    private val PLAINTEXT_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

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
     * Runs before the database opens. A plaintext file here means an older or broken build
     * wrote unencrypted data; refusing loudly is the only safe response (§6.1).
     */
    fun requireNoPlaintextDatabase(file: File) {
        if (isPlaintextSqlite(file)) {
            throw PlaintextDatabaseException(
                "Plaintext SQLite database found at ${file.name}. Refusing to open it: the store must be " +
                    "encrypted from creation, so this file has to be removed or migrated deliberately.",
            )
        }
    }

    /** Runs once the database is open: the file must not look like plaintext SQLite. */
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
