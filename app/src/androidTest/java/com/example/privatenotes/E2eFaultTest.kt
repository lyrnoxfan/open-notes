package com.example.privatenotes

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.privatenotes.data.backup.BackupErrorKind
import com.example.privatenotes.data.backup.BackupException
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fault injection for the import path, run after a successful import so there is something to
 * lose.
 *
 * Each case asserts two things: the operation fails loudly, and the database is bit-for-bit the
 * same afterwards. "Did not report success" alone would pass on a wipe.
 */
@RunWith(AndroidJUnit4::class)
class E2eFaultTest {

    private val app: PrivateNotesApp
        get() = ApplicationProvider.getApplicationContext()

    private val files: File
        get() = app.filesDir

    @Test
    fun truncatedBackupIsRejectedAndDataIsUnchanged() = runBlocking {
        val before = databaseFingerprint()

        val exception = runCatching {
            app.container.backupImporter.import(fileUri("e2e-truncated.pnb"), E2E_PASSWORD.toCharArray())
        }.exceptionOrNull()

        assertTrue("a truncated envelope must fail", exception is BackupException)
        val kind = (exception as BackupException).kind
        assertTrue(
            "expected a wrong-password-or-corrupt failure, got $kind",
            kind == BackupErrorKind.WRONG_PASSWORD_OR_CORRUPT || kind == BackupErrorKind.INVALID_FORMAT,
        )
        assertEquals("database must be untouched by a rejected file", before, databaseFingerprint())
    }

    @Test
    fun missingBackupFileIsReportedAndDataIsUnchanged() = runBlocking {
        val before = databaseFingerprint()

        val exception = runCatching {
            app.container.backupImporter.import(fileUri("does-not-exist.pnb"), null)
        }.exceptionOrNull()

        assertTrue("a missing file must fail", exception != null)
        assertEquals("database must be untouched by a missing file", before, databaseFingerprint())
    }

    /** The directory a backup lives in disappears between choosing it and importing from it. */
    @Test
    fun deletedDirectoryIsReportedAndDataIsUnchanged() = runBlocking {
        val dir = File(files, "gone-directory").apply { mkdirs() }
        val file = File(dir, "e2e-plain.json").apply { writeBytes(File(files, "e2e-plain.json").readBytes()) }
        val before = databaseFingerprint()

        // Delete the parent after the URI was formed, which is what a revoked SAF grant looks like.
        assertTrue(dir.deleteRecursively())

        val exception = runCatching {
            app.container.backupImporter.import(android.net.Uri.fromFile(file), null)
        }.exceptionOrNull()

        assertTrue("a vanished directory must fail", exception != null)
        assertEquals("database must be untouched", before, databaseFingerprint())
    }

    /** WebDAV that never answers: no data loss, no "uploaded" status. */
    @Test
    fun unreachableWebDavIsANetworkFailureAndDataIsUnchanged() = runBlocking {
        val before = databaseFingerprint()
        app.container.backupStatusStore.saveWebDav(
            baseUrl = "https://127.0.0.1:9/notes",
            username = "e2e",
            remoteDirectory = "open-notes",
            password = "E2E-DAV".toCharArray(),
        )
        val statusBefore = app.container.backupStatusStore.status.value

        val exception = runCatching {
            app.container.webDavBackupManager.backup(E2E_PASSWORD.toCharArray())
        }.exceptionOrNull()

        assertTrue("an unreachable server must fail", exception != null)
        assertEquals("database must be untouched", before, databaseFingerprint())
        val statusAfter = app.container.backupStatusStore.status.value
        assertEquals(
            "a failed upload must not move the last-success timestamp",
            statusBefore.remoteLastSuccessAt,
            statusAfter.remoteLastSuccessAt,
        )
    }

    /**
     * Fingerprint of what a user would notice: every note's id, title, content and timestamps, in
     * a stable order. Sorted by id so two reads compare equal regardless of query order.
     */
    suspend fun databaseFingerprint(): String {
        val notes = app.container.noteRepository
        val active = notes.observeNotes().first().map { it.note }
        val trash = notes.observeTrash().first()
        return (active + trash)
            .sortedBy { it.id }
            .joinToString("\n") { note ->
                "${note.id}|${note.title}|${note.content.hashCode()}|${note.createdAt}|${note.updatedAt}|${note.deletedAt}"
            }
    }

    private fun fileUri(name: String): android.net.Uri = android.net.Uri.fromFile(File(files, name))

    private companion object {
        const val E2E_PASSWORD = "E2E-PASS"
    }
}
