package com.lyrnox.opennotes

import android.app.Application

/**
 * Loading SQLCipher's native library is deliberately not done here.
 *
 * `EncryptedDatabaseProvider` does it immediately before it opens the database, which keeps
 * the native dependency in the same place as the only code that needs it. Doing it during
 * `Application.onCreate` would also make every JVM test that instantiates this class fail
 * looking for a `.so` that only exists on a device.
 */
class OpenNotesApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
