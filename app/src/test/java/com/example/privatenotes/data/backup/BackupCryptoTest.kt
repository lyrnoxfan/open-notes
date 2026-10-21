package com.example.privatenotes.data.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {

    private val password = "correct horse battery staple".toCharArray()
    private val plaintext = """{"format":"private-notes-backup","notes":[{"content":"private text"}]}"""
        .toByteArray(Charsets.UTF_8)

    @Test
    fun `encrypted backups round trip without the plaintext appearing on disk`() {
        val encrypted = BackupCrypto.encrypt(plaintext, password, iterations = 10_000)

        assertTrue(BackupCrypto.isEncrypted(encrypted))
        assertArrayEquals(plaintext, BackupCrypto.decrypt(encrypted, password))
        assertFalse(
            "ciphertext must not contain the note body",
            String(encrypted, Charsets.ISO_8859_1).contains("private text"),
        )
    }

    @Test
    fun `the wrong password is rejected`() {
        val encrypted = BackupCrypto.encrypt(plaintext, password, iterations = 10_000)

        val failure = assertThrows(BackupException::class.java) {
            BackupCrypto.decrypt(encrypted, "wrong password".toCharArray())
        }

        assertEquals(BackupErrorKind.WRONG_PASSWORD_OR_CORRUPT, failure.kind)
    }

    @Test
    fun `tampering with the ciphertext is rejected`() {
        val encrypted = BackupCrypto.encrypt(plaintext, password, iterations = 10_000)
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 0x01).toByte()

        val failure = assertThrows(BackupException::class.java) {
            BackupCrypto.decrypt(encrypted, password)
        }

        assertEquals(BackupErrorKind.WRONG_PASSWORD_OR_CORRUPT, failure.kind)
    }

    @Test
    fun `tampering with the header is rejected instead of deriving another key`() {
        val encrypted = BackupCrypto.encrypt(plaintext, password, iterations = 10_000)
        encrypted[14] = (encrypted[14].toInt() xor 0x7F).toByte() // inside the salt

        val failure = assertThrows(BackupException::class.java) {
            BackupCrypto.decrypt(encrypted, password)
        }

        assertEquals(BackupErrorKind.WRONG_PASSWORD_OR_CORRUPT, failure.kind)
    }

    @Test
    fun `an unsupported iteration count is refused`() {
        val encrypted = BackupCrypto.encrypt(plaintext, password, iterations = 10_000)
        // iterations are bytes 9..12, big-endian
        encrypted[9] = 0
        encrypted[10] = 0
        encrypted[11] = 0
        encrypted[12] = 1

        val failure = assertThrows(BackupException::class.java) {
            BackupCrypto.decrypt(encrypted, password)
        }

        assertEquals(BackupErrorKind.CORRUPT_FILE, failure.kind)
    }

    @Test
    fun `a plaintext file is not accepted as an encrypted backup`() {
        val failure = assertThrows(BackupException::class.java) {
            BackupCrypto.decrypt(plaintext, password)
        }

        assertEquals(BackupErrorKind.INVALID_FORMAT, failure.kind)
    }

    @Test
    fun `an empty password is refused`() {
        val failure = assertThrows(BackupException::class.java) {
            BackupCrypto.encrypt(plaintext, CharArray(0), iterations = 10_000)
        }

        assertEquals(BackupErrorKind.INVALID_FORMAT, failure.kind)
    }

    @Test
    fun `two encryptions of the same body differ because of the random salt and iv`() {
        val first = BackupCrypto.encrypt(plaintext, password, iterations = 10_000)
        val second = BackupCrypto.encrypt(plaintext, password, iterations = 10_000)

        assertFalse(first.contentEquals(second))
    }
}
