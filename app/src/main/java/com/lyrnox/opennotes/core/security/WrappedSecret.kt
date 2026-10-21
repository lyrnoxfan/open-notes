package com.lyrnox.opennotes.core.security

/**
 * On-disk layout of a wrapped secret:
 *
 * ```
 *   byte 0        format version (currently 1)
 *   bytes 1..12   GCM IV
 *   bytes 13..    ciphertext followed by the 16-byte GCM tag
 * ```
 *
 * The version byte exists so the wrapping scheme can evolve without a data migration
 * that has to guess what it is looking at.
 */
object WrappedSecret {

    const val CURRENT_VERSION: Byte = 1
    const val IV_LENGTH = 12
    private const val HEADER_LENGTH = 1 + IV_LENGTH

    class Sealed(val iv: ByteArray, val ciphertext: ByteArray)

    fun seal(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        require(iv.size == IV_LENGTH) { "IV must be $IV_LENGTH bytes, got ${iv.size}" }
        require(ciphertext.isNotEmpty()) { "Refusing to seal an empty ciphertext" }
        return ByteArray(HEADER_LENGTH + ciphertext.size).also { out ->
            out[0] = CURRENT_VERSION
            iv.copyInto(out, destinationOffset = 1)
            ciphertext.copyInto(out, destinationOffset = HEADER_LENGTH)
        }
    }

    fun parse(blob: ByteArray): Sealed {
        if (blob.isEmpty()) throw DatabaseKeyException.KeyCorrupted("Wrapped secret is empty")
        val version = blob[0]
        if (version != CURRENT_VERSION) {
            throw DatabaseKeyException.KeyCorrupted("Unsupported wrapped-secret version: $version")
        }
        if (blob.size <= HEADER_LENGTH) throw DatabaseKeyException.KeyCorrupted("Wrapped secret is truncated")
        val iv = blob.copyOfRange(1, HEADER_LENGTH)
        val ciphertext = blob.copyOfRange(HEADER_LENGTH, blob.size)
        return Sealed(iv, ciphertext)
    }
}
