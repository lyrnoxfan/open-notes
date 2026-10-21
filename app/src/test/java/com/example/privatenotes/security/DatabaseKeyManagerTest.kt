package com.example.privatenotes.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class DatabaseKeyManagerTest {

    private val cipher = FakeSecretCipher()

    private fun manager(store: WrappedKeyStore, random: SecureRandom = SecureRandom()) =
        DatabaseKeyManager(store, cipher, random)

    @Test
    fun `first use creates a 32 byte passphrase and persists only the wrapped form`() {
        val store = MemoryWrappedKeyStore()

        val passphrase = manager(store).passphrase()

        assertEquals(DatabaseKeyManager.PASSPHRASE_BYTES, passphrase.size)
        assertEquals(1, store.writeCount)

        val stored = store.blob!!
        assertFalse("plaintext key must never be persisted verbatim", containsSequence(stored, passphrase))
        assertEquals(WrappedSecret.CURRENT_VERSION, stored[0])
    }

    @Test
    fun `subsequent calls return the same passphrase`() {
        val store = MemoryWrappedKeyStore()
        val first = manager(store).passphrase()
        val second = manager(store).passphrase()

        assertArrayEquals(first, second)
        assertEquals("the key is generated once, not per open", 1, store.writeCount)
    }

    @Test
    fun `isInitialised reflects whether a key already exists`() {
        val store = MemoryWrappedKeyStore()
        val keyManager = manager(store)

        assertFalse(keyManager.isInitialised())
        keyManager.passphrase()
        assertTrue(keyManager.isInitialised())

        store.clear()
        assertFalse(keyManager.isInitialised())
    }

    @Test
    fun `a failed write propagates instead of handing out an unsaved key`() {
        val store = MemoryWrappedKeyStore(failOnWrite = true)

        assertThrows(IllegalStateException::class.java) { manager(store).passphrase() }
        assertNull("nothing may be persisted when the write failed", store.blob)
    }

    @Test
    fun `a stored blob of the wrong length is reported as corruption`() {
        val store = MemoryWrappedKeyStore()
        store.write(cipher.wrap(ByteArray(16)))

        assertThrows(DatabaseKeyException.KeyCorrupted::class.java) { manager(store).passphrase() }
    }

    @Test
    fun `a stored blob that fails authentication is reported as unavailable`() {
        val store = MemoryWrappedKeyStore(blob = byteArrayOf(1, 2, 3))
        val failingCipher = object : SecretCipher {
            override fun wrap(plaintext: ByteArray): ByteArray = byteArrayOf(1)
            override fun unwrap(blob: ByteArray): ByteArray =
                throw DatabaseKeyException.KeyUnavailable("tampered")
        }

        assertThrows(DatabaseKeyException.KeyUnavailable::class.java) {
            DatabaseKeyManager(store, failingCipher).passphrase()
        }
    }

    @Test
    fun `two fresh installs do not share a key`() {
        val a = manager(MemoryWrappedKeyStore(), SecureRandom()).passphrase()
        val b = manager(MemoryWrappedKeyStore(), SecureRandom()).passphrase()

        assertFalse("passphrases must be randomly generated", a.contentEquals(b))
    }

    private fun containsSequence(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        outer@ for (start in 0..haystack.size - needle.size) {
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) continue@outer
            }
            return true
        }
        return false
    }
}
