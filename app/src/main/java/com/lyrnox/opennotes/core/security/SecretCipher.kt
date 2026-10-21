package com.lyrnox.opennotes.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps small secrets with a key the app cannot export.
 *
 * Implementations must be authenticated encryption: tampering has to surface as an
 * exception, never as silently different plaintext.
 */
interface SecretCipher {
    fun wrap(plaintext: ByteArray): ByteArray
    fun unwrap(blob: ByteArray): ByteArray
}

/**
 * AES-256-GCM backed by the Android Keystore.
 *
 * The key material lives in the device's secure hardware-backed keystore and is
 * non-exportable, so a copied data directory yields ciphertext with no usable key.
 */
class AndroidKeystoreSecretCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : SecretCipher {

    /**
     * Resolved on first use, not in the constructor.
     *
     * Looking the provider up eagerly would put a Keystore round trip on the app's main
     * thread during startup, and would also make this class unusable anywhere the
     * `AndroidKeyStore` provider does not exist (JVM tests, for one).
     */
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    override fun wrap(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        require(iv.size == GCM_IV_BYTES) { "Unexpected GCM IV length: ${iv.size}" }
        val ciphertext = cipher.doFinal(plaintext)
        return WrappedSecret.seal(iv, ciphertext)
    }

    override fun unwrap(blob: ByteArray): ByteArray {
        val sealed = WrappedSecret.parse(blob)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(GCM_TAG_BITS, sealed.iv))
            cipher.doFinal(sealed.ciphertext)
        } catch (e: GeneralSecurityException) {
            throw DatabaseKeyException.KeyUnavailable("Wrapped key failed authentication", e)
        } catch (e: IllegalArgumentException) {
            throw DatabaseKeyException.KeyCorrupted("Malformed wrapped key blob", e)
        }
    }

    private fun existingKey(): SecretKey {
        val entry = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
            ?: throw DatabaseKeyException.KeyUnavailable("Keystore alias '$keyAlias' is missing")
        return entry.secretKey
    }

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val DEFAULT_KEY_ALIAS = "open_notes_db_key_v1"

        private const val KEY_SIZE_BITS = 256
        private const val GCM_TAG_BITS = 128
        private const val GCM_IV_BYTES = 12
    }
}
