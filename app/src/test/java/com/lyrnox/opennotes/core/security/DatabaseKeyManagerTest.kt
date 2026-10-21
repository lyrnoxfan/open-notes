package com.lyrnox.opennotes.core.security

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

    private fun deterministicRandom(): SecureRandom = SecureRandom().apply {
        setSeed(42L)
    }

    @Test
    fun `first use creates a 32 byte passphrase and persists only the wrapped form`() {
        val store = MemoryWrappedKeyStore()
        val manager = DatabaseKeyManager(store, cipher, deterministicRandom())

        val passphrase = manager.passphrase()

        assertEquals(DatabaseKeyManager.PASSPHRASE_BYTES, passphrase.size)
        assertEquals(1, store.writeCount)

        val stored = store.blob!!
        assertFalse("plaintext key must never be persisted verbatim", containsSequence(stored, passphrase))
        assertTrue("stored blob should carry the wrapped-secret version byte", stored[0] == WrappedSecret.CURRENT_VERSION)
    }

    @Test
    fun `subsequent calls return the same passphrase`() {
        val store = MemoryWrappedKeyStore()
        val first = DatabaseKeyManager(store, cipher, deterministicRandom()).passphrase()
        val second = DatabaseKeyManager(store, cipher, deterministicRandom()).passphrase()

        assertArrayEquals(first, second)
        assertEquals("the key must be generated once, not per open", 1, store.writeCount)
    }

    @Test
    fun `isInitialised reflects whether a key already exists`() {
        val store = MemoryWrappedKeyStore()
        val manager = DatabaseKeyManager(store, cipher, deterministicRandom())

        assertFalse(manager.isInitialised())
        manager.passphrase()
        assertTrue(manager.isInitialised())

        store.clear()
        assertFalse(manager.isInitialised())
    }

    @Test
    fun `a failed write propagates instead of handing out an unsaved key`() {
        val store = MemoryWrappedKeyStore(failOnWrite = true)
        val manager = DatabaseKeyManager(store, cipher, deterministicRandom())

        assertThrows(IllegalStateException::class.java) { manager.passphrase() }
        assertNull("nothing may be persisted when the write failed", store.blob)
    }

    @Test
    fun `a stored blob of the wrong length is reported as corruption`() {
        val store = MemoryWrappedKeyStore()
        store.write(cipher.wrap(ByteArray(16)))

        val manager = DatabaseKeyManager(store, cipher, deterministicRandom())

        assertThrows(DatabaseKeyException.KeyCorrupted::class.java) { manager.passphrase() }
    }

    @Test
    fun `a stored blob that fails authentication is reported as unavailable`() {
        val store = MemoryWrappedKeyStore()
        val failingCipher = object : SecretCipher {
            override fun wrap(plaintext: ByteArray): ByteArray = byteArrayOf(1)
            override fun unwrap(blob: ByteArray): ByteArray =
                throw DatabaseKeyException.KeyUnavailable("tampered")
        }
        store.write(byteArrayOf(1, 2, 3))

        val manager = DatabaseKeyManager(store, failingCipher, deterministicRandom())

        assertThrows(DatabaseKeyException.KeyUnavailable::class.java) { manager.passphrase() }
    }

    @Test
    fun `two fresh installs do not share a key`() {
        val a = DatabaseKeyManager(MemoryWrappedKeyStore(), cipher, SecureRandom()).passphrase()
        val b = DatabaseKeyManager(MemoryWrappedKeyStore(), cipher, SecureRandom()).passphrase()

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
