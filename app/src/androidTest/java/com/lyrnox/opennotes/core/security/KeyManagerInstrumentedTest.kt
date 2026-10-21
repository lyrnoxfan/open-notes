package com.lyrnox.opennotes.core.security

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real Android Keystore, which only exists on a device. */
@RunWith(AndroidJUnit4::class)
class KeyManagerInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun store() = SharedPreferencesWrappedKeyStore(context)

    private fun manager() = DatabaseKeyManager(
        store = store(),
        cipher = AndroidKeystoreSecretCipher(ALIAS),
    )

    @Before
    fun clearStoredKey() {
        store().clear()
    }

    @Test
    fun passphraseIsStableAcrossManagerInstances() {
        val first = manager().passphrase()
        val second = manager().passphrase()

        assertEquals(DatabaseKeyManager.PASSPHRASE_BYTES, first.size)
        assertArrayEquals("the same wrapped key must unwrap to the same passphrase", first, second)
    }

    @Test
    fun wrappedBlobIsNotThePlaintextKey() {
        val keyManager = manager()

        val passphrase = keyManager.passphrase()
        val stored = store().read()!!

        assertTrue(stored.isNotEmpty())
        assertFalse("the stored blob must be wrapped, never the raw key", stored.contentEquals(passphrase))
        assertEquals(WrappedSecret.CURRENT_VERSION, stored[0])
    }

    @Test
    fun losingTheWrappedKeyMintsANewPassphrase() {
        val original = manager().passphrase()

        store().clear()
        val replacement = manager().passphrase()

        assertEquals(DatabaseKeyManager.PASSPHRASE_BYTES, replacement.size)
        assertNotEquals(
            "a cleared key store must not resurrect the old passphrase",
            original.toHex(),
            replacement.toHex(),
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private const val ALIAS = "open_notes_test_key_v1"
    }
}
