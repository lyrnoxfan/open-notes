package com.lyrnox.opennotes.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lyrnox.opennotes.data.local.entity.FolderEntity
import com.lyrnox.opennotes.data.local.entity.NoteEntity
import com.lyrnox.opennotes.data.local.entity.NoteTagCrossRef
import com.lyrnox.opennotes.data.local.entity.TagEntity
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

/**
 * Schema and DAO behaviour, exercised on an in-memory database.
 *
 * This covers the data model itself, not encryption — a plaintext in-memory database is
 * the right tool here because nothing touches disk. Encryption is asserted by
 * `EncryptedDatabaseTest` in the instrumented source set, where SQLCipher's native
 * library actually exists.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteDaoTest {

    private lateinit var database: OpenNotesDatabase

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, OpenNotesDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun note(title: String, content: String = "", createdAt: Long = 1_000L) = NoteEntity(
        title = title,
        content = content,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    @Test
    fun `insert assigns an id and the note is observable`() = runBlocking {
        val id = database.noteDao().insert(note("First"))

        val active = database.noteDao().observeActive().first()

        assertEquals(1, active.size)
        assertEquals(id, active.single().id)
        assertEquals("First", active.single().title)
    }

    @Test
    fun `trashed notes leave the active list`() = runBlocking {
        val dao = database.noteDao()
        val id = dao.insert(note("Trash me"))

        dao.moveToTrash(id, timestamp = 2_000L)

        assertTrue(dao.observeActive().first().isEmpty())
        assertEquals(1, dao.countTrashed())
        assertEquals(2_000L, dao.findById(id)!!.deletedAt)
    }

    @Test
    fun `a trashed note can be restored`() = runBlocking {
        val dao = database.noteDao()
        val id = dao.insert(note("Undo me"))
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
        val old = dao.insert(note("Old"))
        val recent = dao.insert(note("Recent"))
        dao.moveToTrash(old, timestamp = 1_000L)
        dao.moveToTrash(recent, timestamp = 9_000L)

        val purged = dao.purgeTrashOlderThan(cutoff = 5_000L)

        assertEquals(1, purged)
        assertNull(dao.findById(old))
        assertEquals(1, dao.countTrashed())
    }

    @Test
    fun `pinned notes sort ahead of newer unpinned ones`() = runBlocking {
        val dao = database.noteDao()
        dao.insert(note("Unpinned newer", createdAt = 5_000L))
        val pinned = dao.insert(note("Pinned older", createdAt = 1_000L))
        dao.update(dao.findById(pinned)!!.copy(pinned = true))

        val active = dao.observeActive().first()

        assertEquals("Pinned older", active.first().title)
        assertEquals(2, active.size)
    }

    @Test
    fun `search looks at both title and content`() = runBlocking {
        val dao = database.noteDao()
        dao.insert(note("Groceries", content = "milk and bread"))
        dao.insert(note("Ideas", content = "write a grocery app"))

        // Matches "Groceries" by title and "Ideas" by content.
        val broad = dao.search("Grocer").first().map { it.title }.sorted()

        assertEquals(listOf("Groceries", "Ideas"), broad)

        // Only the content of one note contains this.
        assertEquals(listOf("Groceries"), dao.search("milk").first().map { it.title })

        assertTrue(dao.search("nothing-matches-this").first().isEmpty())
    }

    @Test
    fun `deleting a folder keeps its notes and clears their folder reference`() = runBlocking {
        val folderId = database.folderDao().insert(
            FolderEntity(name = "Work", createdAt = 1_000L),
        )
        val noteId = database.noteDao().insert(note("In a folder").copy(folderId = folderId))

        database.folderDao().delete(folderId)

        val stored = database.noteDao().findById(noteId)!!
        assertNull("FK is SET NULL, so the note survives", stored.folderId)
        assertEquals(0, database.folderDao().count())
    }

    @Test
    fun `deleting a note removes its tag links`() = runBlocking {
        val noteId = database.noteDao().insert(note("Tagged"))
        val tagId = database.tagDao().insert(TagEntity(name = "work", createdAt = 1_000L))
        database.tagDao().attach(NoteTagCrossRef(noteId, tagId))

        assertEquals(listOf(tagId), database.tagDao().tagIdsForNote(noteId))

        database.noteDao().moveToTrash(noteId, timestamp = 2_000L)
        database.noteDao().purgeTrashOlderThan(cutoff = 3_000L)

        assertTrue("cascade should clear the join rows", database.tagDao().tagIdsForNote(noteId).isEmpty())
        assertEquals("the tag itself survives", 1, database.tagDao().observeAll().first().size)
    }

    @Test
    fun `tag names are unique case insensitively`() = runBlocking {
        val dao = database.tagDao()
        dao.insert(TagEntity(name = "Work", createdAt = 1_000L))

        val second = dao.insert(TagEntity(name = "work", createdAt = 2_000L))

        assertEquals(-1L, second)
        assertEquals("Work", dao.findByName("Work")!!.name)
    }

    @Test
    fun `notes can be listed per folder`() = runBlocking {
        val dao = database.noteDao()
        val folderId = database.folderDao().insert(FolderEntity(name = "Inbox", createdAt = 1_000L))
        dao.insert(note("Inside").copy(folderId = folderId))
        dao.insert(note("Outside"))

        val inside = dao.observeActiveInFolder(folderId).first()
        val loose = dao.observeActiveInFolder(null).first()

        assertEquals(listOf("Inside"), inside.map { it.title })
        assertEquals(listOf("Outside"), loose.map { it.title })
    }
}
