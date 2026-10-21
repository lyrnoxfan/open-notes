package com.lyrnox.opennotes.core.security

/** Raised when the database passphrase can neither be recovered nor safely created. */
sealed class DatabaseKeyException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** The wrapped key cannot be unwrapped: Keystore entry gone, invalidated, or tampered with. */
    class KeyUnavailable(message: String, cause: Throwable? = null) : DatabaseKeyException(message, cause)

    /** The unwrapped material is structurally wrong (truncated, wrong length, bad version). */
    class KeyCorrupted(message: String, cause: Throwable? = null) : DatabaseKeyException(message, cause)
}

/**
 * Raised when a plaintext SQLite file sits where the encrypted database belongs.
 *
 * This is a hard stop on purpose: opening it would mean the data was readable on disk,
 * even for an instant, which is exactly the state this app must never produce.
 */
class PlaintextDatabaseException(message: String) : IllegalStateException(message)
