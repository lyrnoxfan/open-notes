package com.example.privatenotes.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64

/** Durable home for the wrapped database passphrase. */
interface WrappedKeyStore {
    fun read(): ByteArray?
    fun write(blob: ByteArray)
    fun clear()
}

/**
 * Keeps the wrapped passphrase in app-private SharedPreferences.
 *
 * Only the wrapped form is stored — the raw key never touches disk, per §6.1. Writes use
 * `commit()` so a crash cannot leave a database encrypted with a key that was never saved.
 */
class SharedPreferencesWrappedKeyStore(context: Context) : WrappedKeyStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(): ByteArray? {
        val encoded = prefs.getString(KEY_WRAPPED_PASSPHRASE, null) ?: return null
        return try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            throw DatabaseKeyException.KeyCorrupted("Stored passphrase is not valid base64", e)
        }
    }

    override fun write(blob: ByteArray) {
        val committed = prefs.edit()
            .putString(KEY_WRAPPED_PASSPHRASE, Base64.encodeToString(blob, Base64.NO_WRAP))
            .commit()
        check(committed) { "Could not persist the wrapped database key" }
    }

    override fun clear() {
        prefs.edit().remove(KEY_WRAPPED_PASSPHRASE).commit()
    }

    companion object {
        const val PREFS_NAME = "private_notes_security"
        const val KEY_WRAPPED_PASSPHRASE = "db_passphrase_v1"
    }
}
