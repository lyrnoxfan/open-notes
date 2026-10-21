package com.lyrnox.opennotes

import android.content.Context
import com.lyrnox.opennotes.core.security.AndroidKeystoreSecretCipher
import com.lyrnox.opennotes.core.security.DatabaseKeyManager
import com.lyrnox.opennotes.core.security.SharedPreferencesWrappedKeyStore
import com.lyrnox.opennotes.data.NoteRepository
import com.lyrnox.opennotes.data.local.EncryptedDatabaseProvider
import com.lyrnox.opennotes.data.local.OpenNotesDatabase

/**
 * Manual dependency graph.
 *
 * Everything is lazy so the Keystore key and the database are created on first real use,
 * not during app startup before the UI has a chance to report problems.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val keyManager: DatabaseKeyManager = DatabaseKeyManager(
        store = SharedPreferencesWrappedKeyStore(appContext),
        cipher = AndroidKeystoreSecretCipher(),
    )

    val database: OpenNotesDatabase by lazy {
        EncryptedDatabaseProvider.open(appContext, keyManager)
    }

    val noteRepository: NoteRepository by lazy { NoteRepository(database.noteDao()) }
}
