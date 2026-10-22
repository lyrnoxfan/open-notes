package com.example.privatenotes

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The import that `scripts/e2e-reinstall.sh` kills in the middle.
 *
 * It starts importing the 1 MB export and then stays alive long enough for the host to run
 * `am force-stop`. Nothing here asserts success - the assertion lives in [E2eAtomicityTest],
 * which runs in a fresh process afterwards.
 */
@ScriptDriven
@RunWith(AndroidJUnit4::class)
class E2eSlowImportTest {

    private val app: PrivateNotesApp
        get() = ApplicationProvider.getApplicationContext()

    private companion object {
        /** Chosen to land inside the merge: reading and validating the file takes a bit longer. */
        const val KILL_AFTER_MILLIS = 120L
    }

    @Test
    fun startImportAndWaitToBeKilled() = runBlocking {
        val file = File(app.filesDir, "e2e-plain.json")
        assertTrue("the seed export must be on the device", file.isFile)

        // The process kills itself a fixed moment after the merge begins. A host-driven
        // force-stop has to survive an adb round trip per poll and lands after the commit; a
        // watchdog inside the process lands where the interesting failure is - inside the
        // transaction, before it commits.
        Thread {
            runCatching {
                runBlocking {
                    app.container.backupImporter.import(android.net.Uri.fromFile(file), null)
                }
            }
        }.start()

        Thread.sleep(KILL_AFTER_MILLIS)
        android.os.Process.killProcess(android.os.Process.myPid())
        Thread.sleep(5_000)
    }
}

/**
 * Runs after the kill: the database must be either untouched or completely imported.
 *
 * A half-merged database is the failure this checks for - the import runs in one transaction, so
 * anything in between means the transaction boundary is not where it claims to be.
 */
@ScriptDriven
@RunWith(AndroidJUnit4::class)
class E2eAtomicityTest {

    private val app: PrivateNotesApp
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun afterAKillTheDatabaseIsEitherUntouchedOrComplete() = runBlocking {
        val manifest = JSONObject(File(app.filesDir, "e2e-manifest.json").readText())
        val expected = manifest.getJSONArray("notes")
        val expectedIds = (0 until expected.length()).map { expected.getJSONObject(it).getString("id") }.toSet()

        val notes = app.container.noteRepository
        val active = notes.observeActiveNotes().first().map { it.id }.toSet()
        val trash = notes.observeTrash().first().map { it.id }.toSet()
        val present = active + trash

        val summary = "atomicity: ${present.size} of ${expectedIds.size} notes present after the kill " +
            if (present.isEmpty()) "(clean rollback)" else "(fully committed)"
        println(summary)
        File(app.filesDir, "atomicity.txt").writeText(summary + "\n")
        if (present.isEmpty()) {
            // Nothing committed: a clean rollback.
            assertEquals("no partial rows may exist", 0, present.size)
        } else {
            assertEquals(
                "a kill may either commit everything or nothing, never a subset",
                expectedIds,
                present,
            )
            assertEquals(
                "active note count after a completed import",
                manifest.getInt("expectedActive"),
                notes.activeNoteCount(),
            )
            assertEquals("trash count after a completed import", manifest.getInt("expectedTrash"), trash.size)
        }
    }
}
