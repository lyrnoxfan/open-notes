package com.example.privatenotes

import android.content.Context
import com.example.privatenotes.data.local.AppDatabase
import com.example.privatenotes.data.local.EncryptedDatabaseProvider
import com.example.privatenotes.data.repository.NoteRepository
import com.example.privatenotes.data.repository.RoomNoteRepository
import com.example.privatenotes.data.repository.RoomTagRepository
import com.example.privatenotes.data.repository.TagRepository
import com.example.privatenotes.data.repository.TimeSource
import com.example.privatenotes.security.AndroidAppLockAuthenticator
import com.example.privatenotes.security.AndroidKeystoreSecretCipher
import com.example.privatenotes.security.AppLock
import com.example.privatenotes.security.AppLockAuthenticator
import com.example.privatenotes.security.AppLockManager
import com.example.privatenotes.security.AppLockSettings
import com.example.privatenotes.security.DatabaseKeyManager
import com.example.privatenotes.security.SharedPreferencesAppLockSettings
import com.example.privatenotes.security.SharedPreferencesWrappedKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

    private val databaseLock = Any()

    @Volatile
    private var databaseInstance: AppDatabase? = null

    /**
     * The open database, or a freshly opened one.
     *
     * Not `by lazy`: §6.2 wants the connection closed while the app is locked, which means it has
     * to be reopenable later with the same key.
     */
    val database: AppDatabase
        get() = databaseInstance ?: synchronized(databaseLock) {
            databaseInstance ?: EncryptedDatabaseProvider.open(appContext, keyManager)
                .also { databaseInstance = it }
        }

    val noteRepository: NoteRepository
        get() = RoomNoteRepository(database, timeSource)

    val appLockSettings: AppLockSettings = SharedPreferencesAppLockSettings(appContext)

    val appLockAuthenticator: AppLockAuthenticator = AndroidAppLockAuthenticator(appContext)

    /** Lock state machine; locking also drops the database connection. */
    val appLock: AppLock = AppLockManager(
        settings = appLockSettings,
        timeSource = timeSource,
        onLocked = { closeDatabase() },
    )

    /** Whether a database connection is currently held; used by the lock teardown test. */
    val isDatabaseOpen: Boolean
        get() = databaseInstance?.isOpen == true

    /**
     * Closes the database, if one is open.
     *
     * The instance is detached from the container *before* it is closed and the close itself runs
     * off the main thread. A getter racing with this call therefore opens a new instance instead
     * of touching the closing one.
     */
    fun closeDatabase() {
        val closing = synchronized(databaseLock) {
            databaseInstance.also { databaseInstance = null }
        } ?: return

        applicationScope.launch {
            runCatching { closing.close() }
        }
    }

    /**
     * Rebuilt per access for the same reason as [noteRepository]: the database can be closed by
     * the lock and reopened later, and a cached repository would keep pointing at the old handle.
     */
    val tagRepository: TagRepository
        get() = RoomTagRepository(database, timeSource)
}
