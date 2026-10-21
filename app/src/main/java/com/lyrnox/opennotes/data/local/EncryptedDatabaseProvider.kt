package com.lyrnox.opennotes.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lyrnox.opennotes.core.security.DatabaseFileGuard
import com.lyrnox.opennotes.core.security.PassphraseProvider
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

/**
 * The single gate through which [OpenNotesDatabase] may be built.
 *
 * Why this exists instead of letting callers use `Room.databaseBuilder` directly:
 * Room happily creates a plaintext database if no open-helper factory is attached, and the
 * resulting file is readable by anything with file access. Attaching SQLCipher here — and
 * only here — means the database file is encrypted from the first page SQLite writes,
 * never plaintext-then-converted.
 *
 * Two guards back that up:
 *  - before opening, refuse a plaintext file at the database path,
 *  - after opening, assert the file on disk is not plaintext SQLite.
 */
object EncryptedDatabaseProvider {

    /**
     * Opens (or creates) the encrypted database.
     *
     * The passphrase is fetched only now: if key creation fails, no database gets created
     * with a key that could not be stored.
     */
    fun open(context: Context, passphrases: PassphraseProvider): OpenNotesDatabase =
        openWithPassphrase(context, passphrases.passphrase())

    fun openWithPassphrase(context: Context, passphrase: ByteArray): OpenNotesDatabase {
        require(passphrase.isNotEmpty()) { "SQLCipher passphrase must not be empty" }

        val appContext = context.applicationContext

        // SQLCipher's native layer has to be resident before the first database call.
        SQLiteDatabase.loadLibs(appContext)

        val databaseFile = appContext.getDatabasePath(OpenNotesDatabase.NAME)
        DatabaseFileGuard.requireNoPlaintextDatabase(databaseFile)

        // A private copy: SQLCipher may keep or clear this array, callers keep theirs intact.
        val factory = SupportFactory(passphrase.copyOf(), null, false)

        return Room.databaseBuilder(appContext, OpenNotesDatabase::class.java, OpenNotesDatabase.NAME)
            .openHelperFactory(factory)
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
}
