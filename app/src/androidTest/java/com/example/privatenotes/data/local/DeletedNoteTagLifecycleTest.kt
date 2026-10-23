package com.example.privatenotes.data.local

import androidx.test.platform.app.InstrumentationRegistry
import com.example.privatenotes.data.repository.RoomNoteRepository
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

/** The real foreign-key cascade must remove only the permanently deleted note's joins. */
class DeletedNoteTagLifecycleTest {
    @Test fun softDeleteRestoreAndPermanentDeleteKeepOtherNotesTags() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "deleted-tag-lifecycle-test.db"
        context.deleteDatabase(name)
        val db = EncryptedDatabaseProvider.openWithPassphrase(context, ByteArray(32) { 17 }, name)
        try {
            val repo = RoomNoteRepository(db)
            val one = repo.createNote("删除测试", "正文中文")
            val two = repo.createNote("共用保留", "别的正文")
            val tag = TagEntity("shared", "共用", 1, 1)
            val unique = TagEntity("unique", "独有", 1, 1)
            db.tagDao().insert(tag)
            db.tagDao().insert(unique)
            db.tagDao().attach(NoteTagCrossRef(one, tag.id))
            db.tagDao().attach(NoteTagCrossRef(one, unique.id))
            db.tagDao().attach(NoteTagCrossRef(two, tag.id))
            repo.moveToTrash(one)
            assertEquals(setOf(tag.id, unique.id), db.tagDao().tagIdsForNote(one).toSet())
            repo.restoreFromTrash(one)
            assertNull(repo.findById(one)!!.deletedAt)
            assertEquals(2, db.tagDao().tagIdsForNote(one).size)
            repo.moveToTrash(one)
            repo.permanentlyDelete(one)
            assertTrue(db.tagDao().tagIdsForNote(one).isEmpty())
            assertEquals(listOf(tag.id), db.tagDao().tagIdsForNote(two))
            assertNotNull(db.tagDao().findById(tag.id))
            // Unused global labels are not destroyed by an unrelated article delete.
            assertNotNull(db.tagDao().findById(unique.id))
            repo.deletePermanentlyAll(listOf(two))
            assertTrue(db.tagDao().tagIdsForNote(two).isEmpty())
            assertNotNull(db.tagDao().findById(tag.id))
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
