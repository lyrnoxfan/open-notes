package com.example.privatenotes.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.privatenotes.data.local.AppDatabase
import com.example.privatenotes.data.local.DeletionMarkerEntity
import com.example.privatenotes.data.local.NoteEntity
import com.example.privatenotes.data.local.NoteTagCrossRef
import com.example.privatenotes.data.local.TagEntity
import com.example.privatenotes.data.repository.TimeSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Full archive path: database → document → bytes → document → database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRoundTripTest {

    private lateinit var source: AppDatabase
    private lateinit var target: AppDatabase
    private lateinit var archive: BackupArchiveService
    private lateinit var json: BackupJson

    private val fixedTime = TimeSource { 1_700_000_000_000L }

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        source = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        target = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        json = BackupJson()
        archive = BackupArchiveService(
            snapshotReader = BackupSnapshotReader(source, fixedTime),
            json = json,
            appVersionProvider = AppVersionProvider(context),
        )
    }

    @After
    fun tearDown() {
        source.close()
        target.close()
    }

    @Test
    fun `a plaintext backup round trips every table`() = runBlocking {
        seedSource()

        val archived = archive.create(password = null)
        assertEquals(BackupFileKind.PLAINTEXT_JSON, archive.kindOf(archived.bytes))
        assertTrue(archived.fileName.endsWith(".json"))

        val document = archive.read(archived.bytes, password = null)
        assertEquals(1, document.notes.size)
        assertEquals(1, document.tags.size)
        assertEquals(1, document.noteTags.size)
        assertEquals(1, document.deletionMarkers.size)

        val report = BackupMerger(target, json).merge(document)

        assertEquals(1, report.insertedNotes)
        assertEquals("note body", target.noteDao().findById("note-1")?.content)
        assertEquals(listOf("tag-1"), target.tagDao().tagIdsForNote("note-1"))
        assertNotNull(target.deletionMarkerDao().findByNoteId("deleted-note"))
    }

    @Test
    fun `an encrypted backup round trips only with the right password`() = runBlocking {
        seedSource()
        val password = "a sufficiently long password".toCharArray()

        val archived = archive.create(password)
        assertTrue(archived.encrypted)
        assertEquals(BackupFileKind.ENCRYPTED, archive.kindOf(archived.bytes))
        assertTrue(archived.fileName.endsWith(".pnb"))
        assertFalse(
            "the encrypted file must not contain a note body",
            String(archived.bytes, Charsets.ISO_8859_1).contains("note body"),
        )

        val wrong = assertThrows(BackupException::class.java) {
            archive.read(archived.bytes, "wrong password".toCharArray())
        }
        assertEquals(BackupErrorKind.WRONG_PASSWORD_OR_CORRUPT, wrong.kind)

        val document = archive.read(archived.bytes, password)
        BackupMerger(target, json).merge(document)
        assertEquals("note body", target.noteDao().findById("note-1")?.content)
    }

    @Test
    fun `an empty database exports a valid empty backup`() = runBlocking {
        val archived = archive.create(null)

        val document = archive.read(archived.bytes, null)

        assertTrue(document.notes.isEmpty())
        assertTrue(document.tags.isEmpty())
        assertTrue(document.noteTags.isEmpty())
        assertTrue(document.deletionMarkers.isEmpty())
    }

    @Test
    fun `a backup preserves trashed notes and timestamps exactly`() = runBlocking {
        source.noteDao().insert(
            NoteEntity(
                id = "trashed",
                title = "title",
                content = "body",
                createdAt = 10L,
                updatedAt = 20L,
                deletedAt = 30L,
            ),
        )

        val archived = archive.create(null)
        val decoded = archive.read(archived.bytes, null)
        val mergedInto = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            BackupMerger(mergedInto, json).merge(decoded)
            val restored = mergedInto.noteDao().findById("trashed")
            assertEquals(10L, restored?.createdAt ?: -1L)
            assertEquals(20L, restored?.updatedAt ?: -1L)
            assertEquals(30L, restored?.deletedAt ?: -1L)
        } finally {
            mergedInto.close()
        }
    }

    @Test
    fun `encoded json is not empty and names the schema`() = runBlocking {
        val archived = archive.create(null)

        val text = String(archived.bytes, Charsets.UTF_8)

        assertTrue(text.contains("\"format\": \"private-notes-backup\""))
        assertTrue(text.contains("\"schemaVersion\": 2"))
    }

    private suspend fun seedSource() {
        source.noteDao().insert(
            NoteEntity(
                id = "note-1",
                title = "note title",
                content = "note body",
                createdAt = 100L,
                updatedAt = 200L,
            ),
        )
        source.tagDao().insert(TagEntity(id = "tag-1", name = "Tag", createdAt = 100L, updatedAt = 100L))
        source.tagDao().attach(NoteTagCrossRef("note-1", "tag-1"))
        source.deletionMarkerDao().insert(
            DeletionMarkerEntity("deleted-note", deletedAt = 300L, lastKnownUpdatedAt = 250L, schemaVersion = 1),
        )
    }
}
