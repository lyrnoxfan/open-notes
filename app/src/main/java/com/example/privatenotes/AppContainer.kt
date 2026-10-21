package com.example.privatenotes

import android.content.Context
import com.example.privatenotes.data.local.AppDatabase
import com.example.privatenotes.data.local.EncryptedDatabaseProvider
import com.example.privatenotes.data.repository.NoteRepository
import com.example.privatenotes.data.repository.RoomNoteRepository
import com.example.privatenotes.data.repository.TimeSource
import com.example.privatenotes.security.AndroidKeystoreSecretCipher
import com.example.privatenotes.security.DatabaseKeyManager
import com.example.privatenotes.security.SharedPreferencesWrappedKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency graph.
 *
 * Everything is lazy so the Keystore key and the database are created on first real use,
 * rather than during startup before the UI can report a problem.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    /**
     * Outlives any single screen.
     *
     * The editor's final save has to complete even when the screen and its ViewModel are gone,
     * so it cannot run on `viewModelScope`.
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val timeSource: TimeSource = TimeSource.System

    val keyManager: DatabaseKeyManager = DatabaseKeyManager(
        store = SharedPreferencesWrappedKeyStore(appContext),
        cipher = AndroidKeystoreSecretCipher(),
    )

    val database: AppDatabase by lazy {
        EncryptedDatabaseProvider.open(appContext, keyManager)
    }

    val noteRepository: NoteRepository by lazy {
        RoomNoteRepository(database, timeSource)
    }
}
