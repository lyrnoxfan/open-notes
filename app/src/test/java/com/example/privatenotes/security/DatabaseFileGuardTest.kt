package com.example.privatenotes.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DatabaseFileGuardTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun fileWith(bytes: ByteArray): File =
        temporaryFolder.newFile().apply { writeBytes(bytes) }

    @Test
    fun `recognises a plaintext sqlite header`() {
        val file = fileWith("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) + ByteArray(64))

        assertTrue(DatabaseFileGuard.isPlaintextSqlite(file))
    }

    @Test
    fun `a random header is not plaintext sqlite`() {
        val file = fileWith(ByteArray(64) { (it * 31 + 7).toByte() })

        assertFalse(DatabaseFileGuard.isPlaintextSqlite(file))
    }

    @Test
    fun `a short file is not treated as plaintext`() {
        val file = fileWith("SQLite".toByteArray(Charsets.US_ASCII))

        assertFalse(DatabaseFileGuard.isPlaintextSqlite(file))
    }

    @Test
    fun `a missing file is not treated as plaintext`() {
        assertFalse(DatabaseFileGuard.isPlaintextSqlite(File(temporaryFolder.root, "absent.db")))
    }

    @Test
    fun `opening is refused when a plaintext database occupies the path`() {
        val file = fileWith("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) + ByteArray(128))

        val error = assertThrows(PlaintextDatabaseException::class.java) {
            DatabaseFileGuard.requireNoPlaintextDatabase(file)
        }
        assertTrue(error.message!!.contains(file.name))
    }

    @Test
    fun `opening is allowed when no file exists yet`() {
        DatabaseFileGuard.requireNoPlaintextDatabase(File(temporaryFolder.root, "fresh.db"))
    }

    @Test
    fun `an encrypted-looking file passes the post-open check`() {
        DatabaseFileGuard.requireEncryptedDatabase(fileWith(ByteArray(128) { (it * 17 + 3).toByte() }))
    }

    @Test
    fun `the post-open check rejects a plaintext file`() {
        val file = fileWith("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) + ByteArray(128))

        assertThrows(PlaintextDatabaseException::class.java) {
            DatabaseFileGuard.requireEncryptedDatabase(file)
        }
    }
}
