package com.example.privatenotes.security

/**
 * On-disk layout of a wrapped secret:
 *
 * ```
 *   byte 0        format version (currently 1)
 *   bytes 1..12   GCM nonce
 *   bytes 13..    ciphertext followed by the 16-byte GCM tag
 * ```
 *
 * The version byte lets the wrapping scheme change later without a migration that has to
 * guess what it is looking at.
 */
object WrappedSecret {

    const val CURRENT_VERSION: Byte = 1
    const val NONCE_LENGTH = 12
    private const val HEADER_LENGTH = 1 + NONCE_LENGTH

    class Sealed(val nonce: ByteArray, val ciphertext: ByteArray)

    fun seal(nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        require(nonce.size == NONCE_LENGTH) { "Nonce must be $NONCE_LENGTH bytes, got ${nonce.size}" }
        require(ciphertext.isNotEmpty()) { "Refusing to seal an empty ciphertext" }
        return ByteArray(HEADER_LENGTH + ciphertext.size).also { out ->
            out[0] = CURRENT_VERSION
            nonce.copyInto(out, destinationOffset = 1)
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
        return Sealed(
            nonce = blob.copyOfRange(1, HEADER_LENGTH),
            ciphertext = blob.copyOfRange(HEADER_LENGTH, blob.size),
        )
    }
}
