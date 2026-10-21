package com.example.privatenotes.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WrappedSecretTest {

    private val nonce = ByteArray(WrappedSecret.NONCE_LENGTH) { it.toByte() }

    @Test
    fun `seal writes the version byte first and round trips the payload`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)

        val blob = WrappedSecret.seal(nonce, payload)

        assertEquals(WrappedSecret.CURRENT_VERSION, blob[0])
        assertEquals(WrappedSecret.NONCE_LENGTH + 1 + payload.size, blob.size)

        val parsed = WrappedSecret.parse(blob)
        assertArrayEquals(nonce, parsed.nonce)
        assertArrayEquals(payload, parsed.ciphertext)
    }

    @Test
    fun `seal rejects a wrong length nonce`() {
        assertThrows(IllegalArgumentException::class.java) {
            WrappedSecret.seal(ByteArray(8), byteArrayOf(1))
        }
    }

    @Test
    fun `seal rejects an empty payload`() {
        assertThrows(IllegalArgumentException::class.java) {
            WrappedSecret.seal(nonce, ByteArray(0))
        }
    }

    @Test
    fun `parse rejects an empty blob`() {
        assertThrows(DatabaseKeyException.KeyCorrupted::class.java) {
            WrappedSecret.parse(ByteArray(0))
        }
    }

    @Test
    fun `parse rejects a truncated blob`() {
        assertThrows(DatabaseKeyException.KeyCorrupted::class.java) {
            WrappedSecret.parse(ByteArray(WrappedSecret.NONCE_LENGTH + 1))
        }
    }

    @Test
    fun `parse rejects an unknown format version`() {
        val blob = WrappedSecret.seal(nonce, byteArrayOf(9, 9)).also { it[0] = 0x7F }

        val error = assertThrows(DatabaseKeyException.KeyCorrupted::class.java) {
            WrappedSecret.parse(blob)
        }
        assertTrue(error.message!!.contains("version"))
    }
}
