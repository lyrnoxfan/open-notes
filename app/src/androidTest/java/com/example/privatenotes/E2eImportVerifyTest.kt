package com.example.privatenotes

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.privatenotes.data.backup.ImportOutcome
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verification half of `scripts/e2e-reinstall.sh`.
 *
 * Runs in a process that was installed after `adb uninstall`, with a new database and a new
 * Keystore key, so passing means the export really carried the data across a reinstall rather
 * than a database that was already there.
 */
@ScriptDriven
@RunWith(AndroidJUnit4::class)
class E2eImportVerifyTest {

    private val app: PrivateNotesApp
        get() = ApplicationProvider.getApplicationContext()

    private val files: File
        get() = app.filesDir

    @Test
    fun plaintextExportRestoresEveryField() = runBlocking {
        val manifest = JSONObject(File(files, "e2e-manifest.json").readText())

        val outcome = app.container.backupImporter.import(fileUri("e2e-plain.json"), null)
        assertImported(outcome, manifest)

        // A second import of the same file must not duplicate anything.
        val countAfter = app.container.noteRepository.activeNoteCount()
        val again = app.container.backupImporter.import(fileUri("e2e-plain.json"), null)
        assertEquals("second import must not insert", 0, again.report.insertedNotes)
        assertEquals("second import must not delete", 0, again.report.deletedLocalNotes)
        assertEquals("note count after re-import", countAfter, app.container.noteRepository.activeNoteCount())
        assertImported(again, manifest, checkCounts = false)
    }

    @Test
    fun encryptedExportRestoresEveryField() = runBlocking {
        val manifest = JSONObject(File(files, "e2e-manifest.json").readText())
        // The database is empty on a fresh install; this test runs on its own too.
        val outcome = app.container.backupImporter.import(
            fileUri("e2e-encrypted.pnb"),
            E2E_PASSWORD.toCharArray(),
        )
        assertImported(outcome, manifest)
    }

    private suspend fun assertImported(
        outcome: ImportOutcome,
        manifest: JSONObject,
        checkCounts: Boolean = true,
    ) {
        val expected = manifest.getJSONArray("notes")
        val notes = app.container.noteRepository
        val active = notes.observeNotes().first()
        val trash = notes.observeTrash().first()

        if (checkCounts) {
            assertEquals(
                "active notes after import",
                manifest.getInt("expectedActive"),
                notes.activeNoteCount(),
            )
            assertEquals("trashed notes after import", manifest.getInt("expectedTrash"), trash.size)
            assertNull(
                "the permanently deleted note must not come back",
                notes.findById(manifest.getString("permanentlyDeletedId")),
            )
        }

        val byId = (active.map { it.note } + trash).associateBy { it.id }
        assertEquals("every manifest note must exist", expected.length(), byId.size)

        for (index in 0 until expected.length()) {
            val wanted = expected.getJSONObject(index)
            val id = wanted.getString("id")
            val actual = byId[id] ?: error("missing note $id")
            assertEquals("title of $id", wanted.getString("title"), actual.title)
            assertEquals(
                "content bytes of $id",
                wanted.getInt("contentBytes"),
                actual.content.toByteArray(Charsets.UTF_8).size,
            )
            assertEquals(
                "content hash of $id",
                wanted.getString("contentSha256"),
                sha256(actual.content),
            )
            assertEquals("createdAt of $id", wanted.getLong("createdAt"), actual.createdAt)
            assertEquals("updatedAt of $id", wanted.getLong("updatedAt"), actual.updatedAt)
            if (wanted.isNull("deletedAt")) {
                assertNull("deletedAt of $id", actual.deletedAt)
            } else {
                assertEquals("deletedAt of $id", wanted.getLong("deletedAt"), actual.deletedAt)
            }

            val wantedTags = wanted.getJSONArray("tags").let { array ->
                (0 until array.length()).map(array::getString).sorted()
            }
            val actualTags = app.container.tagRepository.observeTagsForNote(id)
                .first().map { it.name }.sorted()
            assertEquals("tags of $id", wantedTags, actualTags)
        }
        assertTrue("the import reports at least one note", expected.length() > 0)
    }

    private fun fileUri(name: String): android.net.Uri =
        android.net.Uri.fromFile(File(files, name))

    private companion object {
        const val E2E_PASSWORD = "E2E-PASS"

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
