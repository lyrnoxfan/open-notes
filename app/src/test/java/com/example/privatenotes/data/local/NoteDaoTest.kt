package com.example.privatenotes.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.privatenotes.data.repository.LikePattern
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Schema and DAO behaviour, exercised on an in-memory database.
 *
 * This covers the data model itself, not encryption — nothing here touches disk, so a plaintext
 * in-memory database is the right tool. Encryption at rest is asserted by
 * `EncryptedDatabaseTest` in the instrumented source set, where SQLCipher's native library
 * actually exists.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteDaoTest {

    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun note(
        title: String,
        content: String = "",
        createdAt: Long = 1_000L,
        id: String = UUID.randomUUID().toString(),
    ) = NoteEntity(
        id = id,
        title = title,
        content = content,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    @Test
    fun `insert keeps the supplied uuid and the note is observable`() = runBlocking {
        val id = UUID.randomUUID().toString()
        database.noteDao().insert(note("First", id = id))

        val active = database.noteDao().observeActive().first()

        assertEquals(1, active.size)
        assertEquals(id, active.single().id)
        assertEquals("First", active.single().title)
    }

    @Test
    fun `trashed notes leave the active list`() = runBlocking {
        val dao = database.noteDao()
        val id = UUID.randomUUID().toString()
        dao.insert(note("Trash me", id = id))

        dao.moveToTrash(id, timestamp = 2_000L)

        assertTrue(dao.observeActive().first().isEmpty())
        assertEquals(1, dao.countTrashed())
        assertEquals(2_000L, dao.findById(id)!!.deletedAt)
    }

    @Test
    fun `a trashed note can be restored`() = runBlocking {
        val dao = database.noteDao()
        val id = UUID.randomUUID().toString()
        dao.insert(note("Undo me", id = id))
        dao.moveToTrash(id, timestamp = 2_000L)

        dao.restoreFromTrash(id, timestamp = 3_000L)

        val restored = dao.findById(id)!!
        assertNull(restored.deletedAt)
        assertEquals(3_000L, restored.updatedAt)
        assertEquals(1, dao.countActive())
    }

    @Test
    fun `purging removes only notes older than the cutoff`() = runBlocking {
        val dao = database.noteDao()
        val old = UUID.randomUUID().toString()
        val recent = UUID.randomUUID().toString()
        dao.insert(note("Old", id = old))
        dao.insert(note("Recent", id = recent))
        dao.moveToTrash(old, timestamp = 1_000L)
        dao.moveToTrash(recent, timestamp = 9_000L)

        val purged = dao.purgeTrashOlderThan(cutoff = 5_000L)

        assertEquals(1, purged)
        assertNull(dao.findById(old))
        assertEquals(1, dao.countTrashed())
    }

    @Test
    fun `the active list is ordered by most recent edit`() = runBlocking {
        val dao = database.noteDao()
        dao.insert(note("Older", createdAt = 1_000L))
        dao.insert(note("Newer", createdAt = 5_000L))

        val active = dao.observeActive().first()

        assertEquals(listOf("Newer", "Older"), active.map { it.title })
    }

    @Test
    fun `search looks at both title and content`() = runBlocking {
        val dao = database.noteDao()
        dao.insert(note("Groceries", content = "milk and bread"))
        dao.insert(note("Ideas", content = "write a grocery app"))

        // Matches "Groceries" by title and "Ideas" by content.
        assertEquals(
            listOf("Groceries", "Ideas"),
            dao.search("Grocer").first().map { it.title }.sorted(),
        )

        assertEquals(listOf("Groceries"), dao.search("milk").first().map { it.title })
        assertTrue(dao.search("nothing-matches-this").first().isEmpty())
    }

    @Test
    fun `search treats sql metacharacters as literal text`() = runBlocking {
        val dao = database.noteDao()
        dao.insert(note("Percent", content = "progress is 100% done"))
        dao.insert(note("Plain", content = "progress is 1000 done"))

        // Escaped upstream by LikePattern, so `%` matches a literal percent sign rather than
        // acting as a wildcard that would also drag in the "1000" note.
        assertEquals(listOf("Percent"), dao.search(LikePattern.escape("100%")).first().map { it.title })

        // A quote-and-semicolon term stays a bound parameter: no match, no error, no damage.
        assertTrue(dao.search(LikePattern.escape("'; DROP TABLE notes; --")).first().isEmpty())
        assertEquals(2, dao.countActive())
    }

    @Test
    fun `deleting a note removes its tag links but keeps the tag`() = runBlocking {
        val noteId = UUID.randomUUID().toString()
        val tagId = UUID.randomUUID().toString()
        database.noteDao().insert(note("Tagged", id = noteId))
        database.tagDao().insert(TagEntity(id = tagId, name = "work", createdAt = 1_000L, updatedAt = 1_000L))
        database.tagDao().attach(NoteTagCrossRef(noteId, tagId))

        assertEquals(listOf(tagId), database.tagDao().tagIdsForNote(noteId))

        database.noteDao().moveToTrash(noteId, timestamp = 2_000L)
        database.noteDao().purgeTrashOlderThan(cutoff = 3_000L)

        assertTrue("cascade clears the join rows", database.tagDao().tagIdsForNote(noteId).isEmpty())
        assertEquals("the tag itself survives", 1, database.tagDao().observeAll().first().size)
    }

    @Test
    fun `tag names are unique case insensitively`() = runBlocking {
        val dao = database.tagDao()
        dao.insert(TagEntity(id = UUID.randomUUID().toString(), name = "Work", createdAt = 1_000L, updatedAt = 1_000L))

        val second = dao.insert(
            TagEntity(id = UUID.randomUUID().toString(), name = "work", createdAt = 2_000L, updatedAt = 2_000L),
        )

        assertEquals(-1L, second)
        assertEquals("Work", dao.findByName("Work")!!.name)
    }

    @Test
    fun `tags load with their note through the join table`() = runBlocking {
        val noteId = UUID.randomUUID().toString()
        val tagId = UUID.randomUUID().toString()
        database.noteDao().insert(note("With tags", id = noteId))
        database.tagDao().insert(TagEntity(id = tagId, name = "personal", createdAt = 1_000L, updatedAt = 1_000L))
        database.tagDao().attach(NoteTagCrossRef(noteId, tagId))

        val loaded = database.noteDao().observeWithTags(noteId).first()!!

        assertEquals(noteId, loaded.note.id)
        assertEquals(listOf("personal"), loaded.tags.map { it.name })
    }

    @Test
    fun `deletion markers outlive the note itself`() = runBlocking {
        val noteId = UUID.randomUUID().toString()
        database.noteDao().insert(note("Doomed", id = noteId, createdAt = 7_000L))
        database.deletionMarkerDao().insert(
            DeletionMarkerEntity(
                noteId = noteId,
                deletedAt = 9_000L,
                lastKnownUpdatedAt = 7_000L,
                schemaVersion = AppDatabase.SCHEMA_VERSION,
            ),
        )

        database.noteDao().moveToTrash(noteId, timestamp = 9_000L)
        database.noteDao().purgeTrashOlderThan(cutoff = 10_000L)

        assertNull("the note is gone", database.noteDao().findById(noteId))
        val marker = database.deletionMarkerDao().findByNoteId(noteId)!!
        assertEquals("the tombstone remains, so an old backup cannot resurrect it", 9_000L, marker.deletedAt)
        assertEquals(7_000L, marker.lastKnownUpdatedAt)
    }

    @Test
    fun `timestamps are stored as epoch milliseconds`() = runBlocking {
        val dao = database.noteDao()
        val id = UUID.randomUUID().toString()
        val createdAt = 1_700_000_000_123L
        dao.insert(note("Stamp", id = id, createdAt = createdAt))

        val stored = dao.findById(id)!!

        assertEquals(createdAt, stored.createdAt)
        assertEquals(createdAt, stored.updatedAt)
    }
}
