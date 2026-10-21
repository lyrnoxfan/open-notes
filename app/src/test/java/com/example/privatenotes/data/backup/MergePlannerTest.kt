package com.example.privatenotes.data.backup

import com.example.privatenotes.data.local.DeletionMarkerEntity
import com.example.privatenotes.data.local.NoteEntity
import com.example.privatenotes.data.local.NoteTagCrossRef
import com.example.privatenotes.data.local.TagEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MergePlannerTest {

    @Test
    fun `a newer backup note updates the local copy`() {
        val plan = plan(
            localNotes = listOf(localNote("n", title = "local", updatedAt = 100L)),
            backup = backupDocument(notes = listOf(backupNote("n", title = "backup", createdAt = 1L, updatedAt = 200L))),
        )

        assertEquals(1, plan.report.updatedNotes)
        assertEquals("backup", plan.notesToUpsert.single().title)
    }

    @Test
    fun `a newer local note is not rolled back by an old backup`() {
        val plan = plan(
            localNotes = listOf(localNote("n", title = "local", updatedAt = 300L)),
            backup = backupDocument(notes = listOf(backupNote("n", title = "backup", createdAt = 1L, updatedAt = 200L))),
        )

        assertEquals(1, plan.report.keptLocalNotes)
        assertTrue(plan.notesToUpsert.isEmpty())
    }

    @Test
    fun `equal timestamps with different content keep local and record a conflict`() {
        val plan = plan(
            localNotes = listOf(localNote("n", title = "local", updatedAt = 200L)),
            backup = backupDocument(notes = listOf(backupNote("n", title = "backup", createdAt = 1L, updatedAt = 200L))),
        )

        assertEquals(1, plan.report.keptLocalNotes)
        assertEquals(MergeConflictKind.NOTE_TIMESTAMP_TIE, plan.report.conflicts.single().kind)
        assertTrue(plan.notesToUpsert.isEmpty())
    }

    @Test
    fun `equal timestamps with identical content count as unchanged`() {
        val plan = plan(
            localNotes = listOf(localNote("n", title = "same", updatedAt = 200L)),
            backup = backupDocument(notes = listOf(backupNote("n", title = "same", createdAt = 100L, updatedAt = 200L))),
        )

        assertEquals(1, plan.report.unchangedNotes)
        assertTrue(plan.report.conflicts.isEmpty())
    }

    @Test
    fun `an unknown note is inserted`() {
        val plan = plan(backup = backupDocument(notes = listOf(backupNote("new"))))

        assertEquals(1, plan.report.insertedNotes)
        assertEquals("new", plan.notesToUpsert.single().id)
    }

    @Test
    fun `a backup tombstone blocks an older note and removes the local copy`() {
        val plan = plan(
            localNotes = listOf(localNote("n", title = "old", updatedAt = 100L)),
            backup = backupDocument(
                notes = listOf(backupNote("n", title = "restored", createdAt = 1L, updatedAt = 120L)),
                markers = listOf(BackupDeletionMarker("n", deletedAt = 150L, lastKnownUpdatedAt = 130L)),
            ),
        )

        assertEquals(1, plan.report.blockedNotes)
        assertEquals(1, plan.report.deletedLocalNotes)
        assertTrue(plan.notesToDelete.contains("n"))
        assertTrue(plan.notesToUpsert.none { it.id == "n" })
    }

    @Test
    fun `a local note edited after the tombstone is kept and reported`() {
        val plan = plan(
            localNotes = listOf(localNote("n", title = "newer edit", updatedAt = 200L)),
            backup = backupDocument(
                notes = listOf(backupNote("n", title = "backup", createdAt = 1L, updatedAt = 160L)),
                markers = listOf(BackupDeletionMarker("n", deletedAt = 150L, lastKnownUpdatedAt = 140L)),
            ),
        )

        assertEquals(MergeConflictKind.NOTE_EDITED_AFTER_TOMBSTONE, plan.report.conflicts.single().kind)
        assertFalse(plan.notesToDelete.contains("n"))
        assertTrue(plan.notesToUpsert.isEmpty())
    }

    @Test
    fun `a tag with a new id but an existing name is remapped instead of duplicated`() {
        val plan = plan(
            localTags = listOf(TagEntity(id = "local", name = "Work", createdAt = 1L, updatedAt = 1L)),
            backup = backupDocument(
                notes = listOf(backupNote("n")),
                tags = listOf(backupTag("remote", name = "work", createdAt = 2L, updatedAt = 2L)),
                noteTags = listOf(BackupNoteTag("n", "remote")),
            ),
        )

        assertEquals(1, plan.report.remappedTags)
        assertEquals(0, plan.report.addedTags)
        val refs = plan.refsToReplace.getValue("n")
        assertEquals("local", refs.single().tagId)
    }

    @Test
    fun `a tag rename that collides with another local tag is refused`() {
        val localTags = listOf(
            TagEntity(id = "a", name = "X", createdAt = 1L, updatedAt = 1L),
            TagEntity(id = "b", name = "Y", createdAt = 1L, updatedAt = 1L),
        )
        val plan = plan(
            localTags = localTags,
            backup = backupDocument(
                tags = listOf(backupTag("a", name = "Y", createdAt = 1L, updatedAt = 50L)),
            ),
        )

        assertEquals(MergeConflictKind.TAG_NAME_COLLISION, plan.report.conflicts.single().kind)
        assertTrue(plan.tagsToUpsert.isEmpty())
    }

    @Test
    fun `a replaced note adopts the backup tag set even when it is empty`() {
        val plan = plan(
            localNotes = listOf(localNote("n", title = "old", updatedAt = 100L)),
            localTags = listOf(TagEntity(id = "t", name = "Tag", createdAt = 1L, updatedAt = 1L)),
            localRefs = listOf(NoteTagCrossRef("n", "t")),
            backup = backupDocument(
                notes = listOf(backupNote("n", title = "new", createdAt = 1L, updatedAt = 200L)),
            ),
        )

        assertTrue(plan.refsToReplace.containsKey("n"))
        assertTrue(plan.refsToReplace.getValue("n").isEmpty())
    }

    @Test
    fun `a kept local note also receives additive backup tags`() {
        val plan = plan(
            localNotes = listOf(localNote("n", updatedAt = 300L)),
            localTags = listOf(TagEntity(id = "t", name = "Tag", createdAt = 1L, updatedAt = 1L)),
            backup = backupDocument(
                notes = listOf(backupNote("n", createdAt = 1L, updatedAt = 200L)),
                tags = listOf(backupTag("t", name = "Tag", createdAt = 1L, updatedAt = 1L)),
                noteTags = listOf(BackupNoteTag("n", "t")),
            ),
        )

        assertEquals(1, plan.report.addedNoteTags)
        assertEquals("t", plan.additiveRefs.single().tagId)
    }

    @Test
    fun `a local deletion marker blocks a note in the backup`() {
        val plan = plan(
            localMarkers = listOf(
                DeletionMarkerEntity("n", deletedAt = 300L, lastKnownUpdatedAt = 250L, schemaVersion = 1),
            ),
            backup = backupDocument(notes = listOf(backupNote("n", createdAt = 1L, updatedAt = 200L))),
        )

        assertEquals(1, plan.report.blockedNotes)
        assertTrue(plan.notesToUpsert.isEmpty())
    }

    private fun localNote(
        id: String,
        title: String = "title-$id",
        updatedAt: Long = 100L,
    ): NoteEntity = NoteEntity(
        id = id,
        title = title,
        content = "body-$id",
        createdAt = 1L,
        updatedAt = updatedAt,
    )

    private fun plan(
        localNotes: List<NoteEntity> = emptyList(),
        localTags: List<TagEntity> = emptyList(),
        localRefs: List<NoteTagCrossRef> = emptyList(),
        localMarkers: List<DeletionMarkerEntity> = emptyList(),
        backup: BackupDocument,
    ): MergePlan = MergePlanner.plan(
        localNotes = localNotes,
        localTags = localTags,
        localRefs = localRefs,
        localMarkers = localMarkers,
        backup = backup,
    )
}
