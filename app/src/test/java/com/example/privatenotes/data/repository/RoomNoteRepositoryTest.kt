package com.example.privatenotes.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.privatenotes.data.local.AppDatabase
import com.example.privatenotes.data.local.DeletionMarkerEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Repository behaviour for deletion, restore and trash expiry.
 *
 * In-memory database, so this exercises schema and transaction logic; encryption is covered on
 * device elsewhere.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomNoteRepositoryTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: NoteRepository
    private var clock = 1_000_000L

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomNoteRepository(database, TimeSource { clock })
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a new note is stamped with creation and update time`() = runBlocking {
        val id = repository.createNote("t", "c")

        val note = repository.findById(id)
        assertNotNull(note)
        assertEquals(clock, note!!.createdAt)
        assertEquals(clock, note.updatedAt)
        assertNull(note.deletedAt)
    }

    @Test
    fun `deleting moves the note out of the list and into the trash`() = runBlocking {
        val id = repository.createNote("t", "c")

        repository.moveToTrash(id)

        assertTrue(repository.observeActiveNotes().first().isEmpty())
        val trashed = repository.observeTrash().first()
        assertEquals(1, trashed.size)
        assertEquals(id, trashed.single().id)
        assertNotNull(trashed.single().deletedAt)
    }

    @Test
    fun `restoring brings the note back to the list`() = runBlocking {
        val id = repository.createNote("t", "c")
        repository.moveToTrash(id)

        repository.restoreFromTrash(id)

        assertEquals(listOf(id), repository.observeActiveNotes().first().map { it.id })
        assertTrue(repository.observeTrash().first().isEmpty())
        assertNull(repository.findById(id)!!.deletedAt)
    }

    @Test
    fun `a trashed note keeps its content until it is permanently deleted`() = runBlocking {
        val id = repository.createNote("title", "body")
        repository.moveToTrash(id)

        val trashed = repository.findById(id)!!
        assertEquals("title", trashed.title)
        assertEquals("body", trashed.content)
    }

    @Test(expected = IllegalStateException::class)
    fun `a note that is not in the trash cannot be permanently deleted`() = runBlocking {
        val id = repository.createNote("t", "c")

        repository.permanentlyDelete(id)
    }

    @Test
    fun `permanent deletion removes the note and leaves a marker`() = runBlocking {
        val id = repository.createNote("t", "c")
        repository.moveToTrash(id)
        val trashed = repository.findById(id)!!

        repository.permanentlyDelete(id)

        assertNull(repository.findById(id))
        val marker = database.deletionMarkerDao().findByNoteId(id)
        assertNotNull("permanent deletion must leave a tombstone (§5.3)", marker)
        assertEquals(trashed.deletedAt, marker!!.deletedAt)
        assertEquals(trashed.updatedAt, marker.lastKnownUpdatedAt)
        assertEquals(AppDatabase.SCHEMA_VERSION, marker.schemaVersion)
    }

    @Test
    fun `batch trash removes every picked note at once`() = runBlocking {
        val first = repository.createNote("one", "body")
        val second = repository.createNote("two", "body")
        val kept = repository.createNote("kept", "body")

        repository.moveToTrashAll(listOf(first, second))

        // findById still returns trashed rows; "out of the list" means the active query, as in
        // the single-note delete test.
        assertEquals(listOf(kept), repository.observeActiveNotes().first().map { it.id })
        assertEquals(
            "both picked notes are in the trash",
            setOf(first, second),
            repository.observeTrash().first().map { it.id }.toSet(),
        )
    }

    @Test
    fun `batch permanent deletion leaves one marker per note`() = runBlocking {
        val first = repository.createNote("one", "body")
        val second = repository.createNote("two", "body")

        repository.deletePermanentlyAll(listOf(first, second))

        assertNull(repository.findById(first))
        assertNull(repository.findById(second))
        assertNotNull(database.deletionMarkerDao().findByNoteId(first))
        assertNotNull(database.deletionMarkerDao().findByNoteId(second))
    }

    @Test
    fun `expired trash is purged with markers while recent trash survives`() = runBlocking {
        val oldId = repository.createNote("old", "body")
        repository.moveToTrash(oldId)
        val oldDeletedAt = repository.findById(oldId)!!.deletedAt!!

        // The second note is deleted a full day later, so only the first crosses the window.
        clock += TimeUnit.DAYS.toMillis(1)
        val freshId = repository.createNote("fresh", "body")
        repository.moveToTrash(freshId)

        // One millisecond past the first note's window; the second is still a day away.
        clock = oldDeletedAt + NoteRepository.TRASH_RETENTION_MILLIS + 1

        val purged = repository.purgeExpiredTrash(clock)

        assertTrue("the note past the window is purged", repository.findById(oldId) == null)
        assertNotNull("the purged note leaves a marker", database.deletionMarkerDao().findByNoteId(oldId))
        assertNotNull("the recent note is untouched", repository.findById(freshId))
        assertTrue(purged >= 1)
    }

    @Test
    fun `a note just inside the window is not purged`() = runBlocking {
        val id = repository.createNote("t", "c")
        repository.moveToTrash(id)

        clock += NoteRepository.TRASH_RETENTION_MILLIS - 1
        repository.purgeExpiredTrash(clock)

        assertNotNull(repository.findById(id))
    }

    @Test
    fun `updating a note bumps updatedAt strictly`() = runBlocking {
        val id = repository.createNote("t", "c")
        val created = repository.findById(id)!!

        // A frozen clock must still move the timestamp forward, otherwise §9.5 cannot tell
        // which of two edits is newer.
        repository.updateNote(id, "t2", "c2")
        val first = repository.findById(id)!!
        repository.updateNote(id, "t3", "c3")
        val second = repository.findById(id)!!

        assertTrue(first.updatedAt > created.updatedAt)
        assertTrue(second.updatedAt > first.updatedAt)
        assertEquals("t3", second.title)
    }

    @Test
    fun `updating a trashed note does nothing`() = runBlocking {
        val id = repository.createNote("t", "c")
        repository.moveToTrash(id)

        repository.updateNote(id, "resurrected", "body")

        val note = repository.findById(id)!!
        assertEquals("t", note.title)
        assertTrue(note.deletedAt != null)
    }

    @Test
    fun `updating a missing note does nothing instead of creating one`() = runBlocking {
        repository.updateNote("missing-id", "t", "c")

        assertNull(repository.findById("missing-id"))
        assertEquals(0, repository.activeNoteCount())
    }

    @Test
    fun `search finds notes by title and body but ignores the trash`() = runBlocking {
        val byTitle = repository.createNote("Groceries", "milk, eggs")
        val byBody = repository.createNote("Ideas", "write about groceries")
        val trashed = repository.createNote("Old groceries", "gone")
        repository.moveToTrash(trashed)

        val results = repository.search("groceries").first().map { it.id }.toSet()

        assertEquals("search covers title and body, case-insensitively", setOf(byTitle, byBody), results)
        assertFalse("notes in the trash must not surface in the list", results.contains(trashed))
    }

    @Test
    fun `the trash list is ordered by deletion time, newest first`() = runBlocking {
        val first = repository.createNote("a", "1")
        clock += 1_000
        val second = repository.createNote("b", "2")
        repository.moveToTrash(first)
        clock += 1_000
        repository.moveToTrash(second)

        assertEquals(listOf(second, first), repository.observeTrash().first().map { it.id })
    }
}
