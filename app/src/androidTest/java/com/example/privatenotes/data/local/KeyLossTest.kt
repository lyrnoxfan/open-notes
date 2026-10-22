package com.example.privatenotes.data.local

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.privatenotes.security.AndroidKeystoreSecretCipher
import com.example.privatenotes.security.DatabaseKeyException
import com.example.privatenotes.security.DatabaseKeyManager
import com.example.privatenotes.security.SharedPreferencesWrappedKeyStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * §6.1: when the key is unavailable the app must not rebuild an empty database, must not delete
 * the original, and must tell the user to restore from a backup.
 *
 * These assertions are the whole point of the guard, so they are checked against the actual file
 * bytes rather than by inspecting code paths.
 */
@RunWith(AndroidJUnit4::class)
class KeyLossTest {

    private val context: Context
        get() = isolatedContext

    // Real Keystore and real persisted wrapped keys, but never the live app's prefs/database.
    private val isolatedContext: Context by lazy {
        object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("key-loss-test-$name", mode)
        }
    }

    private val TEST_DATABASE = "key-loss-contract-test.db"

    private val passphrase = ByteArray(32) { (it + 7).toByte() }

    private val databaseFile: File
        get() = context.getDatabasePath(TEST_DATABASE)

    private fun realKeyManager() = DatabaseKeyManager(
        store = SharedPreferencesWrappedKeyStore(context),
        cipher = AndroidKeystoreSecretCipher(),
    )

    @Before
    fun cleanBefore() {
        context.deleteDatabase(TEST_DATABASE)
        databaseFile.parentFile?.mkdirs()
        SharedPreferencesWrappedKeyStore(context).clear()
    }

    @After
    fun cleanAfter() {
        context.deleteDatabase(TEST_DATABASE)
        SharedPreferencesWrappedKeyStore(context).clear()
    }

    private fun seedDatabase() {
        val database = EncryptedDatabaseProvider.openWithPassphrase(context, passphrase, TEST_DATABASE)
        try {
            runBlocking {
                database.noteDao().insert(
                    NoteEntity(
                        id = UUID.randomUUID().toString(),
                        title = "Keep me",
                        content = "still here",
                        createdAt = 1L,
                        updatedAt = 1L,
                    ),
                )
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun missingKeyRefusesToOpenAndLeavesTheFileUntouched() {
        seedDatabase()
        val before = databaseFile.readBytes()

        // Simulate the Keystore entry being gone while the database file survives.
        SharedPreferencesWrappedKeyStore(context).clear()

        val error = assertThrows(DatabaseKeyException.KeyUnavailable::class.java) {
            EncryptedDatabaseProvider.open(context, realKeyManager(), TEST_DATABASE)
        }

        assertTrue(
            "the message must point the user at backup restore",
            error.message!!.contains("backup"),
        )
        assertTrue("the database file must still exist", databaseFile.isFile)
        assertTrue(
            "no byte may change: nothing was wiped or recreated",
            before.contentEquals(databaseFile.readBytes()),
        )
    }

    @Test
    fun aFreshInstallWithoutAKeyCreatesOneEncryptedDatabase() {
        val database = EncryptedDatabaseProvider.open(context, realKeyManager(), TEST_DATABASE)
        val count = try {
            runBlocking { database.noteDao().countActive() }
        } finally {
            database.close()
        }

        assertEquals(0, count)
        assertTrue("database file should now exist", databaseFile.isFile)
        assertTrue("the key should have been created", realKeyManager().isInitialised())
    }
}
