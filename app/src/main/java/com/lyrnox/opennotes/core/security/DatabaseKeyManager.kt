package com.lyrnox.opennotes.core.security

import java.security.SecureRandom

/** Supplies the raw passphrase SQLCipher is keyed with. */
fun interface PassphraseProvider {
    fun passphrase(): ByteArray
}

/**
 * Owns the lifecycle of the SQLCipher passphrase.
 *
 * Create-on-first-use:
 *  1. generate 32 bytes from [SecureRandom],
 *  2. wrap with the Keystore-backed [SecretCipher],
 *  3. persist the wrapped blob,
 *  4. read it back and unwrap it to prove the round trip works,
 *  5. only then hand the passphrase to the database layer.
 *
 * Step 4 matters: if persistence silently failed, proceeding would create a database
 * that can never be opened again.
 */
class DatabaseKeyManager(
    private val store: WrappedKeyStore,
    private val cipher: SecretCipher,
    private val random: SecureRandom = SecureRandom(),
) : PassphraseProvider {

    @Synchronized
    override fun passphrase(): ByteArray {
        val stored = store.read()
        return if (stored == null) createAndPersist() else unwrapStored(stored)
    }

    /** True when a passphrase already exists — i.e. the database has been created before. */
    fun isInitialised(): Boolean = store.read() != null

    private fun unwrapStored(blob: ByteArray): ByteArray {
        val plaintext = cipher.unwrap(blob)
        if (plaintext.size != PASSPHRASE_BYTES) {
            throw DatabaseKeyException.KeyCorrupted(
                "Passphrase must be $PASSPHRASE_BYTES bytes, unwrapped ${plaintext.size}",
            )
        }
        return plaintext
    }

    private fun createAndPersist(): ByteArray {
        val fresh = ByteArray(PASSPHRASE_BYTES).also(random::nextBytes)
        store.write(cipher.wrap(fresh))

        val roundTrip = store.read()
            ?: throw DatabaseKeyException.KeyUnavailable("Wrapped key vanished immediately after being written")
        val verified = unwrapStored(roundTrip)
        if (!verified.contentEquals(fresh)) {
            throw DatabaseKeyException.KeyCorrupted("Round trip of the freshly created passphrase did not match")
        }
        return fresh
    }

    companion object {
        /** 256-bit key material for SQLCipher. */
        const val PASSPHRASE_BYTES = 32
    }
}
