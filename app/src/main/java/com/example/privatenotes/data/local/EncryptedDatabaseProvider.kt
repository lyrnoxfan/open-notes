package com.example.privatenotes.data.local

import android.content.Context
import android.database.sqlite.SQLiteTransactionListener
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.privatenotes.data.local.migrations.Migrations
import com.example.privatenotes.security.DatabaseFileGuard
import com.example.privatenotes.security.DatabaseKeyException
import com.example.privatenotes.security.DatabaseKeyManager
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

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
    fun open(
        context: Context,
        keyManager: DatabaseKeyManager,
        fileName: String = AppDatabase.NAME,
        onOpenCheck: () -> Unit = {},
    ): AppDatabase {
        val appContext = context.applicationContext
        val databaseFile = appContext.getDatabasePath(fileName)

        DatabaseFileGuard.requireNoPlaintextDatabase(databaseFile)

        if (databaseFile.isFile && !keyManager.isInitialised()) {
            throw DatabaseKeyException.KeyUnavailable(
                "The notes database exists but its key is no longer available. Nothing has been " +
                    "deleted or recreated. Restore your notes from a backup.",
            )
        }

        return openWithPassphrase(appContext, keyManager.passphrase(), fileName, onOpenCheck)
    }

    /** Opens the database with an explicit passphrase. Used by tests and by the open path above. */
    fun openWithPassphrase(
        context: Context,
        passphrase: ByteArray,
        fileName: String = AppDatabase.NAME,
        onOpenCheck: () -> Unit = {},
    ): AppDatabase {
        require(passphrase.isNotEmpty()) { "SQLCipher passphrase must not be empty" }

        val appContext = context.applicationContext
        SqlCipherNativeLibrary.ensureLoaded()

        val databaseFile = appContext.getDatabasePath(fileName)
        DatabaseFileGuard.requireNoPlaintextDatabase(databaseFile)

        // A private copy: the factory may keep or clear this array; the caller keeps its own.
        val cipherFactory = SupportOpenHelperFactory(passphrase.copyOf(), null, false)
        // Reject a retired Room handle BEFORE SQLCipher allocates/keys a connection.
        // Throwing inside preKey/onOpen can leave a partially initialized native connection
        // holding a lock; subsequent legitimate handles then fail with SQLITE_BUSY.
        val opened = AtomicReference<SupportSQLiteDatabase>()
        val transactionLifetime = ReentrantReadWriteLock(true)
        val factory = SupportSQLiteOpenHelper.Factory { configuration ->
            val helper = cipherFactory.create(configuration)
            object : SupportSQLiteOpenHelper by helper {
                private fun checkedConnection(writable: Boolean): SupportSQLiteDatabase {
                    // Room asks the helper again from setTransactionSuccessful/endTransaction.
                    // If retirement happens after beginTransaction, those calls must be able
                    // to release that transaction's native lock. Use ONLY an already-open
                    // connection whose transaction belongs to this thread: never call the
                    // delegate opener on this path. Other threads and all later accesses
                    // still pass the lease/lock check before SQLCipher can open anything.
                    val current = opened.get()
                    if (current != null && current.isOpen && current.inTransaction()) {
                        return current
                    }
                    return transactionLifetime.readLock().withLock {
                        onOpenCheck()
                        val raw = if (writable) helper.writableDatabase else helper.readableDatabase
                        val previous = opened.get()
                        if (previous != null && previous.isOpen) previous else {
                            object : SupportSQLiteDatabase by raw {
                                private fun begin(start: () -> Unit) {
                                    transactionLifetime.readLock().lock()
                                    try {
                                        if (!raw.inTransaction()) onOpenCheck()
                                        start()
                                    } catch (failure: Throwable) {
                                        transactionLifetime.readLock().unlock()
                                        throw failure
                                    }
                                }

                                override fun beginTransaction() = begin { raw.beginTransaction() }
                                override fun beginTransactionNonExclusive() = begin { raw.beginTransactionNonExclusive() }
                                override fun beginTransactionWithListener(listener: SQLiteTransactionListener) =
                                    begin { raw.beginTransactionWithListener(listener) }
                                override fun beginTransactionWithListenerNonExclusive(listener: SQLiteTransactionListener) =
                                    begin { raw.beginTransactionWithListenerNonExclusive(listener) }

                                override fun endTransaction() {
                                    try { raw.endTransaction() } finally {
                                        transactionLifetime.readLock().unlock()
                                    }
                                }
                            }.also { opened.set(it) }
                        }
                    }
                }

                override fun close() {
                    // SQLCipher cannot close its pool before an in-flight native transaction
                    // releases its connection. Retirement prevents any new transaction while
                    // the existing owner completes; the async app-lock close waits here.
                    transactionLifetime.writeLock().withLock { helper.close() }
                }

                override val writableDatabase: SupportSQLiteDatabase
                    get() = checkedConnection(writable = true)
                override val readableDatabase: SupportSQLiteDatabase
                    get() = checkedConnection(writable = false)
            }
        }

        return Room.databaseBuilder(appContext, AppDatabase::class.java, fileName)
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
            .build().also { database ->
                // Room checks inTransaction once more AFTER endTransaction. That is a state
                // query, not permission to open a connection, so it must not hit the retired
                // helper gate again. Never open anything to answer it.
                database.transactionState = {
                    transactionLifetime.readLock().withLock {
                        opened.get()?.let { it.isOpen && it.inTransaction() } ?: false
                    }
                }
            }
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
