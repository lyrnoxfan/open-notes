package com.lyrnox.opennotes.data.local

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lyrnox.opennotes.core.security.DatabaseFileGuard
import com.lyrnox.opennotes.core.security.PlaintextDatabaseException
import com.lyrnox.opennotes.data.local.entity.NoteEntity
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

/**
 * The encryption contract, verified against real SQLCipher.
 *
 * These run on a device because SQLCipher's native library is what actually writes the
 * bytes; no host JVM test can say anything trustworthy about the on-disk format.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val passphrase = ByteArray(32) { (it + 1).toByte() }

    private val databaseFile: File
        get() = context.getDatabasePath(OpenNotesDatabase.NAME)

    @Before
    fun cleanBefore() {
        context.deleteDatabase(OpenNotesDatabase.NAME)
        databaseFile.parentFile?.mkdirs()
    }

    @After
    fun cleanAfter() {
        context.deleteDatabase(OpenNotesDatabase.NAME)
    }

    /** Opens the real encrypted database and always closes it, even when the body throws. */
    private fun <T> withDatabase(passphrase: ByteArray = this.passphrase, block: (OpenNotesDatabase) -> T): T {
        val database = EncryptedDatabaseProvider.openWithPassphrase(context, passphrase)
        return try {
            block(database)
        } finally {
            database.close()
        }
    }

    private fun insertNote(database: OpenNotesDatabase, title: String, content: String = "") = runBlocking {
        database.noteDao().insert(
            NoteEntity(title = title, content = content, createdAt = 1L, updatedAt = 1L),
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
    fun aPlaintextDatabaseAtThePathIsRefusedInsteadOfOpened() {
        databaseFile.writeBytes("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) + ByteArray(256))

        assertThrows(PlaintextDatabaseException::class.java) {
            EncryptedDatabaseProvider.openWithPassphrase(context, passphrase)
        }
    }

    @Test
    fun existingDatabaseReopensThroughTheSameGuardedPath() {
        withDatabase { database ->
            insertNote(database, title = "First")
            insertNote(database, title = "Second")
        }

        withDatabase { database ->
            val titles = runBlocking { database.noteDao().observeActive().first() }
            assertEquals(2, titles.size)
            assertFalse(DatabaseFileGuard.isPlaintextSqlite(databaseFile))
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
