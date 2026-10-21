package com.example.privatenotes.security

/** The database passphrase can neither be recovered nor safely created. */
sealed class DatabaseKeyException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /**
     * The wrapping key is gone: the Keystore entry was invalidated or destroyed.
     *
     * The database file is still on disk and still intact. It must not be replaced, and a
     * fresh key must never be minted behind the user's back — the data would be unreachable
     * without anyone noticing why.
     */
    class KeyUnavailable(message: String, cause: Throwable? = null) : DatabaseKeyException(message, cause)

    /** The unwrapped material is structurally wrong: truncated, wrong length, bad version. */
    class KeyCorrupted(message: String, cause: Throwable? = null) : DatabaseKeyException(message, cause)
}

/**
 * A plaintext SQLite file sits where the encrypted database belongs.
 *
 * Hard stop on purpose: opening it would mean the notes were readable on disk, which is the
 * one state this app must never produce.
 */
class PlaintextDatabaseException(message: String) : IllegalStateException(message)
