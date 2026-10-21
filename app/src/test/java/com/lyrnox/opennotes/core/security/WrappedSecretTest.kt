package com.lyrnox.opennotes.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WrappedSecretTest {

    private val iv = ByteArray(WrappedSecret.IV_LENGTH) { it.toByte() }

    @Test
    fun `seal puts the version byte first and round trips the payload`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)

        val blob = WrappedSecret.seal(iv, payload)

        assertEquals(WrappedSecret.CURRENT_VERSION, blob[0])
        assertEquals(WrappedSecret.IV_LENGTH + 1 + payload.size, blob.size)

        val parsed = WrappedSecret.parse(blob)
        assertArrayEquals(iv, parsed.iv)
        assertArrayEquals(payload, parsed.ciphertext)
    }

    @Test
    fun `seal rejects a wrong length iv`() {
        assertThrows(IllegalArgumentException::class.java) {
            WrappedSecret.seal(ByteArray(8), byteArrayOf(1))
        }
    }

    @Test
    fun `seal rejects an empty payload`() {
        assertThrows(IllegalArgumentException::class.java) {
            WrappedSecret.seal(iv, ByteArray(0))
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
            WrappedSecret.parse(ByteArray(WrappedSecret.IV_LENGTH + 1))
        }
    }

    @Test
    fun `parse rejects an unknown format version`() {
        val blob = WrappedSecret.seal(iv, byteArrayOf(9, 9)).also { it[0] = 0x7F }

        val error = assertThrows(DatabaseKeyException.KeyCorrupted::class.java) {
            WrappedSecret.parse(blob)
        }
        assertEquals(true, error.message!!.contains("version"))
    }
}
