package com.example.privatenotes.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.privatenotes.data.local.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Phase-3 acceptance: tag CRUD, assignment, and the tag/keyword combination (§5.2, §7.3). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomTagRepositoryTest {

    private lateinit var database: AppDatabase
    private lateinit var notes: NoteRepository
    private lateinit var tags: TagRepository
    private var clock = 1_000L

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        notes = RoomNoteRepository(database, TimeSource { clock })
        tags = RoomTagRepository(database, TimeSource { clock })
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `creating a tag twice reuses it regardless of case`() = runBlocking {
        val first = tags.ensureTag("工作")
        clock++
        val second = tags.ensureTag("  工作  ")

        assertEquals(first, second)
        assertEquals(1, tags.observeAll().first().size)
    }

    @Test
    fun `blank tag names are refused`() = runBlocking {
        assertNull(tags.ensureTag("   "))

        assertTrue(tags.observeAll().first().isEmpty())
    }

    @Test
    fun `a tag name is normalised`() = runBlocking {
        val id = tags.ensureTag("  工作   计划 ")!!.let { tagId ->
            tags.observeAll().first().single().id
        }

        assertEquals("工作 计划", tags.observeAll().first().single { it.id == id }.name)
    }

    @Test
    fun `attaching and detaching a tag updates the note`() = runBlocking {
        val noteId = notes.createNote("t", "c")
        val tagId = tags.ensureTag("想法")!!

        tags.attachTag(noteId, tagId)
        assertEquals(listOf("想法"), tags.observeTagsForNote(noteId).first().map { it.name })

        tags.detachTag(noteId, tagId)
        assertTrue(tags.observeTagsForNote(noteId).first().isEmpty())
    }

    @Test
    fun `attaching the same tag twice keeps one link`() = runBlocking {
        val noteId = notes.createNote("t", "c")
        val tagId = tags.ensureTag("重复")!!

        tags.attachTag(noteId, tagId)
        tags.attachTag(noteId, tagId)

        assertEquals(1, tags.observeTagsForNote(noteId).first().size)
    }

    @Test
    fun `deleting a tag removes it from notes`() = runBlocking {
        val noteId = notes.createNote("t", "c")
        val tagId = tags.ensureTag("临时")!!
        tags.attachTag(noteId, tagId)

        tags.deleteTag(tagId)

        assertTrue(tags.observeTagsForNote(noteId).first().isEmpty())
        assertTrue(tags.observeAll().first().isEmpty())
    }

    @Test
    fun `renaming a tag updates it in place`() = runBlocking {
        val tagId = tags.ensureTag("旧名")!!

        assertTrue(tags.renameTag(tagId, "新名"))

        assertEquals("新名", tags.observeAll().first().single().name)
        assertEquals(tagId, tags.observeAll().first().single().id)
    }

    @Test
    fun `renaming onto an existing name merges the tags and keeps the links`() = runBlocking {
        val noteId = notes.createNote("t", "c")
        val keep = tags.ensureTag("工作")!!
        val merge = tags.ensureTag("上班")!!
        tags.attachTag(noteId, merge)

        tags.renameTag(merge, "工作")

        assertEquals("the merged-away tag disappears", 1, tags.observeAll().first().size)
        assertEquals(keep, tags.observeAll().first().single().id)
        assertEquals(
            "the note keeps a link through the surviving tag",
            listOf("工作"),
            tags.observeTagsForNote(noteId).first().map { it.name },
        )
    }

    @Test
    fun `search combines keyword and tag filter`() = runBlocking {
        val work = tags.ensureTag("工作")!!
        val personal = tags.ensureTag("生活")!!
        val workNote = notes.createNote("周报", "本周进度")
        val personalNote = notes.createNote("购物", "本周采购")
        notes.createNote("周报草稿", "本周进度（未完成）")
        tags.attachTag(workNote, work)
        tags.attachTag(personalNote, personal)

        val byTagOnly = notes.observeNotes("", work).first().map { it.note.id }
        assertEquals(listOf(workNote), byTagOnly)

        val byTagAndTerm = notes.observeNotes("进度", work).first().map { it.note.id }
        assertEquals(listOf(workNote), byTagAndTerm)

        val byTermOnly = notes.observeNotes("本周", null).first().map { it.note.id }
        assertEquals(3, byTermOnly.size)
    }

    @Test
    fun `a note with several tags appears once in a filtered list`() = runBlocking {
        val noteId = notes.createNote("多标签", "正文")
        val first = tags.ensureTag("一")!!
        val second = tags.ensureTag("二")!!
        tags.attachTag(noteId, first)
        tags.attachTag(noteId, second)

        val unfiltered = notes.observeNotes("", null).first()

        assertEquals(1, unfiltered.size)
        assertEquals(2, unfiltered.single().tags.size)
    }

    @Test
    fun `notes carry their tags into the list`() = runBlocking {
        val noteId = notes.createNote("带标签", "正文")
        val tagId = tags.ensureTag("标记")!!
        tags.attachTag(noteId, tagId)

        val entry = notes.observeNotes("", null).first().single()

        assertEquals(noteId, entry.note.id)
        assertEquals(listOf("标记"), entry.tags.map { it.name })
        assertNotNull(entry.note.updatedAt)
    }

    @Test
    fun `a trashed note drops out of filtered results`() = runBlocking {
        val noteId = notes.createNote("要删", "正文")
        val tagId = tags.ensureTag("标签")!!
        tags.attachTag(noteId, tagId)

        notes.moveToTrash(noteId)

        assertTrue(notes.observeNotes("", tagId).first().isEmpty())
    }
}
