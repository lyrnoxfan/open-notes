package com.example.privatenotes.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.privatenotes.data.local.AppDatabase
import com.example.privatenotes.data.local.DeletionMarkerEntity
import com.example.privatenotes.data.local.NoteEntity
import com.example.privatenotes.data.local.NoteTagCrossRef
import com.example.privatenotes.data.local.TagEntity
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MergeIntegrationTest {

    private lateinit var database: AppDatabase
    private lateinit var merger: BackupMerger

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        merger = BackupMerger(database, BackupJson())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `updating a note does not cascade away its tag links`() = runBlocking {
        database.noteDao().insert(
            NoteEntity(id = "n", title = "old", content = "old", createdAt = 1L, updatedAt = 100L),
        )
        database.tagDao().insert(TagEntity(id = "t", name = "Tag", createdAt = 1L, updatedAt = 1L))
        database.tagDao().attach(NoteTagCrossRef("n", "t"))

        merger.merge(
            backupDocument(
                notes = listOf(backupNote("n", title = "new", content = "new", createdAt = 1L, updatedAt = 200L)),
                tags = listOf(backupTag("t", name = "Tag", createdAt = 1L, updatedAt = 1L)),
                noteTags = listOf(BackupNoteTag("n", "t")),
            ),
        )

        assertEquals("new", database.noteDao().findById("n")?.title)
        assertEquals(listOf("t"), database.tagDao().tagIdsForNote("n"))
    }

    @Test
    fun `a replaced note drops tag links the backup no longer has`() = runBlocking {
        database.noteDao().insert(
            NoteEntity(id = "n", title = "old", content = "old", createdAt = 1L, updatedAt = 100L),
        )
        database.tagDao().insert(TagEntity(id = "t", name = "Tag", createdAt = 1L, updatedAt = 1L))
        database.tagDao().attach(NoteTagCrossRef("n", "t"))

        merger.merge(
            backupDocument(
                notes = listOf(backupNote("n", title = "new", createdAt = 1L, updatedAt = 200L)),
                tags = listOf(backupTag("t", name = "Tag", createdAt = 1L, updatedAt = 1L)),
            ),
        )

        assertTrue(database.tagDao().tagIdsForNote("n").isEmpty())
    }

    @Test
    fun `an older backup cannot roll back a newer local note`() = runBlocking {
        database.noteDao().insert(
            NoteEntity(id = "n", title = "local-new", content = "c", createdAt = 1L, updatedAt = 300L),
        )

        val report = merger.merge(
            backupDocument(notes = listOf(backupNote("n", title = "backup-old", createdAt = 1L, updatedAt = 200L))),
        )

        assertEquals(1, report.keptLocalNotes)
        assertEquals("local-new", database.noteDao().findById("n")?.title)
    }

    @Test
    fun `an older backup note cannot resurrect a permanently deleted note`() = runBlocking {
        database.deletionMarkerDao().insert(
            DeletionMarkerEntity("gone", deletedAt = 300L, lastKnownUpdatedAt = 250L, schemaVersion = 1),
        )

        val report = merger.merge(
            backupDocument(notes = listOf(backupNote("gone", createdAt = 1L, updatedAt = 200L))),
        )

        assertEquals(1, report.blockedNotes)
        assertNull(database.noteDao().findById("gone"))
        assertNotNull(database.deletionMarkerDao().findByNoteId("gone"))
    }

    @Test
    fun `a backup tombstone deletes an older local copy`() = runBlocking {
        database.noteDao().insert(
            NoteEntity(id = "n", title = "old", content = "c", createdAt = 1L, updatedAt = 100L),
        )

        val report = merger.merge(
            backupDocument(
                markers = listOf(BackupDeletionMarker("n", deletedAt = 200L, lastKnownUpdatedAt = 150L)),
            ),
        )

        assertEquals(1, report.deletedLocalNotes)
        assertNull(database.noteDao().findById("n"))
        assertNotNull(database.deletionMarkerDao().findByNoteId("n"))
    }

    @Test
    fun `a newer local edit survives a backup tombstone`() = runBlocking {
        database.noteDao().insert(
            NoteEntity(id = "n", title = "newer", content = "c", createdAt = 1L, updatedAt = 300L),
        )

        val report = merger.merge(
            backupDocument(
                markers = listOf(BackupDeletionMarker("n", deletedAt = 200L, lastKnownUpdatedAt = 150L)),
            ),
        )

        assertEquals(MergeConflictKind.NOTE_EDITED_AFTER_TOMBSTONE, report.conflicts.single().kind)
        assertEquals("newer", database.noteDao().findById("n")?.title)
    }

    @Test
    fun `tags with the same name but different ids merge into one link`() = runBlocking {
        database.tagDao().insert(TagEntity(id = "local", name = "Work", createdAt = 1L, updatedAt = 1L))
        database.noteDao().insert(
            NoteEntity(id = "n", title = "t", content = "c", createdAt = 1L, updatedAt = 100L),
        )

        merger.merge(
            backupDocument(
                notes = listOf(backupNote("n", title = "t2", createdAt = 1L, updatedAt = 200L)),
                tags = listOf(backupTag("remote", name = "work", createdAt = 1L, updatedAt = 1L)),
                noteTags = listOf(BackupNoteTag("n", "remote")),
            ),
        )

        assertEquals(listOf("local"), database.tagDao().tagIdsForNote("n"))
        assertEquals(1, database.tagDao().observeAll().first().size)
    }
}
