package com.example.privatenotes.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps small secrets with a key the app itself cannot export.
 *
 * Implementations must use authenticated encryption: tampering has to surface as an
 * exception, never as silently different plaintext.
 */
interface SecretCipher {
    fun wrap(plaintext: ByteArray): ByteArray
    fun unwrap(blob: ByteArray): ByteArray
}

/** AES-256-GCM backed by the Android Keystore. */
class AndroidKeystoreSecretCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : SecretCipher {

    /**
     * Resolved on first use, not in the constructor: this keeps a Keystore round trip off
     * the startup main thread, and keeps the class loadable where the provider does not
     * exist (JVM unit tests).
     */
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    override fun wrap(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val nonce = cipher.iv
        require(nonce.size == GCM_NONCE_BYTES) { "Unexpected GCM nonce length: ${nonce.size}" }
        return WrappedSecret.seal(nonce, cipher.doFinal(plaintext))
    }

    override fun unwrap(blob: ByteArray): ByteArray {
        val sealed = WrappedSecret.parse(blob)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(GCM_TAG_BITS, sealed.nonce))
            cipher.doFinal(sealed.ciphertext)
        } catch (e: GeneralSecurityException) {
            throw DatabaseKeyException.KeyUnavailable("Wrapped key failed authentication", e)
        } catch (e: IllegalArgumentException) {
            throw DatabaseKeyException.KeyCorrupted("Malformed wrapped key blob", e)
        }
    }

    private fun existingKey(): SecretKey =
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: throw DatabaseKeyException.KeyUnavailable("Keystore alias '$keyAlias' is missing")

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
        const val DEFAULT_KEY_ALIAS = "private_notes_db_key_v1"

        private const val KEY_SIZE_BITS = 256
        private const val GCM_TAG_BITS = 128
        private const val GCM_NONCE_BYTES = 12
    }
}
