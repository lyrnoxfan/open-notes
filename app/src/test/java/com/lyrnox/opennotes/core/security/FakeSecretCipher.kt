package com.lyrnox.opennotes.core.security

/**
 * Stand-in for the Keystore cipher in host tests.
 *
 * It deliberately produces a reversible-but-different representation so tests can assert
 * that the plaintext passphrase never reaches the persisted blob.
 */
class FakeSecretCipher : SecretCipher {

    override fun wrap(plaintext: ByteArray): ByteArray =
        ByteArray(WrappedSecret.IV_LENGTH) { 0x0A } .let { iv ->
            WrappedSecret.seal(iv, plaintext.reversedArray())
        }

    override fun unwrap(blob: ByteArray): ByteArray {
        val sealed = WrappedSecret.parse(blob)
        return sealed.ciphertext.reversedArray()
    }
}

/** In-memory [WrappedKeyStore]; [failOnWrite] simulates a failed disk write. */
class MemoryWrappedKeyStore(
    var blob: ByteArray? = null,
    var failOnWrite: Boolean = false,
) : WrappedKeyStore {

    var writeCount: Int = 0
        private set

    override fun read(): ByteArray? = blob?.copyOf()

    override fun write(blob: ByteArray) {
        if (failOnWrite) throw IllegalStateException("simulated write failure")
        writeCount++
        this.blob = blob.copyOf()
    }

    override fun clear() {
        blob = null
    }
}
