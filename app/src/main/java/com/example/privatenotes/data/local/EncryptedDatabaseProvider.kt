package com.example.privatenotes.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.privatenotes.data.local.migrations.Migrations
import com.example.privatenotes.security.DatabaseFileGuard
import com.example.privatenotes.security.DatabaseKeyException
import com.example.privatenotes.security.DatabaseKeyManager
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * The single gate through which [AppDatabase] may be built.
 *
 * Room will happily create a plaintext database when no open-helper factory is attached, and
 * such a file is readable by anything with file access. Attaching SQLCipher here — and only
 * here — means the file is encrypted from the first page SQLite writes, never
 * plaintext-then-converted (§6.1).
 */
object EncryptedDatabaseProvider {

    /**
     * Opens (or creates) the encrypted database using the app's persistent key.
     *
     * Refuses to run when the database exists but the key does not: minting a new key would
     * leave the user with an unopenable file and no explanation. The existing file is never
     * deleted or replaced (§6.1).
     */
    fun open(context: Context, keyManager: DatabaseKeyManager): AppDatabase {
        val appContext = context.applicationContext
        val databaseFile = appContext.getDatabasePath(AppDatabase.NAME)

        DatabaseFileGuard.requireNoPlaintextDatabase(databaseFile)

        if (databaseFile.isFile && !keyManager.isInitialised()) {
            throw DatabaseKeyException.KeyUnavailable(
                "The notes database exists but its key is no longer available. Nothing has been " +
                    "deleted or recreated. Restore your notes from a backup.",
            )
        }

        return openWithPassphrase(appContext, keyManager.passphrase())
    }

    /** Opens the database with an explicit passphrase. Used by tests and by the open path above. */
    fun openWithPassphrase(context: Context, passphrase: ByteArray): AppDatabase {
        require(passphrase.isNotEmpty()) { "SQLCipher passphrase must not be empty" }

        val appContext = context.applicationContext
        SqlCipherNativeLibrary.ensureLoaded()

        val databaseFile = appContext.getDatabasePath(AppDatabase.NAME)
        DatabaseFileGuard.requireNoPlaintextDatabase(databaseFile)

        // A private copy: the factory may keep or clear this array; the caller keeps its own.
        val factory = SupportOpenHelperFactory(passphrase.copyOf(), null, false)

        return Room.databaseBuilder(appContext, AppDatabase::class.java, AppDatabase.NAME)
            .openHelperFactory(factory)
            // Migrations are declared explicitly; there is deliberately no destructive fallback.
            .addMigrations(*Migrations.ALL)
            .addCallback(
                object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        DatabaseFileGuard.requireEncryptedDatabase(databaseFile)
                    }

                    override fun onOpen(db: SupportSQLiteDatabase) {
                        DatabaseFileGuard.requireEncryptedDatabase(databaseFile)
                    }
                },
            )
            .build()
    }

    /**
     * Loads SQLCipher's native library exactly once, immediately before the first database
     * call. Keeping it here — rather than in `Application.onCreate` — leaves the native
     * dependency next to the only code that needs it.
     */
    private object SqlCipherNativeLibrary {
        @Volatile
        private var loaded = false

        @Synchronized
        fun ensureLoaded() {
            if (loaded) return
            System.loadLibrary("sqlcipher")
            loaded = true
        }
    }
}
