package com.example.privatenotes.data.local

import android.content.Context
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.privatenotes.security.DatabaseFileGuard
import com.example.privatenotes.security.PlaintextDatabaseException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * The encryption contract, verified against real SQLCipher.
 *
 * These run on a device because SQLCipher's native library is what actually writes the bytes;
 * no host JVM test can say anything trustworthy about the on-disk format (§12 隐私).
 */
@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    // Never delete the live Application container's database: the suite shares one process.
    private val TEST_DATABASE = "encryption-contract-test.db"

    private val passphrase = ByteArray(32) { (it + 1).toByte() }

    private val databaseFile: File
        get() = context.getDatabasePath(TEST_DATABASE)

    @Before
    fun cleanBefore() {
        context.deleteDatabase(TEST_DATABASE)
        databaseFile.parentFile?.mkdirs()
    }

    @After
    fun cleanAfter() {
        context.deleteDatabase(TEST_DATABASE)
    }

    /** Opens the real encrypted database and always closes it, even when the body throws. */
    private fun <T> withDatabase(passphrase: ByteArray = this.passphrase, block: (AppDatabase) -> T): T {
        val database = EncryptedDatabaseProvider.openWithPassphrase(context, passphrase, TEST_DATABASE)
        return try {
            block(database)
        } finally {
            database.close()
        }
    }

    private fun insertNote(database: AppDatabase, title: String, content: String = "") = runBlocking {
        val now = 1L
        database.noteDao().insert(
            NoteEntity(
                id = UUID.randomUUID().toString(),
                title = title,
                content = content,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    @Test
    fun databaseFileIsEncryptedFromCreation() {
        withDatabase { database ->
            insertNote(database, title = "Secret", content = "top secret body")
        }

        assertTrue("database file should exist", databaseFile.isFile)
        assertTrue("database file should not be empty", databaseFile.length() > 0)
        assertFalse(
            "a freshly created database must never be plaintext SQLite",
            DatabaseFileGuard.isPlaintextSqlite(databaseFile),
        )

        val raw = databaseFile.readBytes()
        assertFalse(
            "note content must not appear in the raw file",
            raw.containsSubsequence("top secret body".toByteArray(Charsets.UTF_8)),
        )
        assertFalse(
            "note title must not appear in the raw file",
            raw.containsSubsequence("Secret".toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun reopeningWithTheSamePassphraseKeepsData() {
        withDatabase { database -> insertNote(database, title = "Persisted") }

        val count = withDatabase { database -> runBlocking { database.noteDao().countActive() } }

        assertEquals(1, count)
    }

    @Test
    fun aWrongPassphraseCannotReadTheDatabase() {
        withDatabase { database -> insertNote(database, title = "Locked") }

        val wrongPassphrase = ByteArray(32) { (it + 99).toByte() }

        assertThrows(Exception::class.java) {
            withDatabase(passphrase = wrongPassphrase) { database ->
                runBlocking { database.noteDao().countActive() }
            }
        }
    }

    @Test
    fun aWrongPassphraseLeavesTheFileByteIdentical() {
        withDatabase { database -> insertNote(database, title = "Untouched") }
        val before = databaseFile.readBytes()

        runCatching {
            withDatabase(passphrase = ByteArray(32) { (it + 99).toByte() }) { database ->
                runBlocking { database.noteDao().countActive() }
            }
        }

        assertTrue(
            "a failed open must not rewrite the database (§11: wrong key never clears data)",
            before.contentEquals(databaseFile.readBytes()),
        )
    }

    @Test
    fun aPlaintextDatabaseAtThePathIsRefusedInsteadOfOpened() {
        databaseFile.writeBytes("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) + ByteArray(256))

        assertThrows(PlaintextDatabaseException::class.java) {
            EncryptedDatabaseProvider.openWithPassphrase(context, passphrase, TEST_DATABASE)
        }
    }

    @Test
    fun existingDatabaseReopensThroughTheSameGuardedPath() {
        withDatabase { database ->
            insertNote(database, title = "First")
            insertNote(database, title = "Second")
        }

        withDatabase { database ->
            val notes = runBlocking { database.noteDao().observeActive().first() }
            assertEquals(2, notes.size)
            assertFalse(DatabaseFileGuard.isPlaintextSqlite(databaseFile))
        }
    }

    @Test
    fun retiringDuringATransactionLetsItFinishButRefusesNewAccess() = runBlocking {
        var retired = false
        val database = EncryptedDatabaseProvider.openWithPassphrase(
            context, passphrase, TEST_DATABASE,
            onOpenCheck = { check(!retired) { "test connection retired" } },
        )
        try {
            // Room's endTransaction also asks openHelper.writableDatabase. Retirement must
            // not strand that already-started native transaction (and its file write lock).
            database.withTransaction {
                database.openHelper.writableDatabase.execSQL(
                    "INSERT INTO tags (id, name, createdAt, updatedAt) VALUES ('lease', 'lease', 1, 1)",
                )
                retired = true
            }
            assertThrows(IllegalStateException::class.java) {
                database.openHelper.writableDatabase
            }
        } finally {
            database.close()
        }
        withDatabase { reopened ->
            // A distinct connection must be able to write after the retired one closes.
            insertNote(reopened, "No leaked transaction lock")
            assertEquals(1, runBlocking { reopened.noteDao().countActive() })
        }
    }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        outer@ for (start in 0..size - needle.size) {
            for (offset in needle.indices) {
                if (this[start + offset] != needle[offset]) continue@outer
            }
            return true
        }
        return false
    }
}
