package com.example.privatenotes.feature.notes

import com.example.privatenotes.data.FakeNoteSettings
import com.example.privatenotes.data.repository.FakeNoteRepository
import com.example.privatenotes.data.repository.FakeTagRepository
import com.example.privatenotes.data.repository.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Auto-save behaviour (§7.1) and the phase-2 acceptance criteria:
 * no manual saving, and a slow older save must never overwrite newer text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteEditorViewModelTest {

    private val fixedTime = TimeSource { 42_000L }

    private val scheduler = TestCoroutineScheduler()
    private val mainDispatcher = StandardTestDispatcher(scheduler)

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(
        repository: FakeNoteRepository,
        tagRepository: FakeTagRepository = FakeTagRepository(),
        noteSettings: FakeNoteSettings = FakeNoteSettings(),
        noteId: String? = null,
        debounceMillis: Long = NoteEditorViewModel.DEFAULT_DEBOUNCE_MILLIS,
    ): NoteEditorViewModel {
        return NoteEditorViewModel(
            repository = repository,
            tagRepository = tagRepository,
            noteSettings = noteSettings,
            existingNoteId = noteId,
            timeSource = fixedTime,
            externalScope = backgroundScope,
            debounceMillis = debounceMillis,
        )
    }

    @Test
    fun `typing is saved once after the debounce window`() = runTest(mainDispatcher) {
        val repository = FakeNoteRepository()
        val viewModel = viewModel(repository)
            viewModel.onContentChange("a")
            viewModel.onContentChange("ab")
            viewModel.onContentChange("abc")

            advanceTimeBy(900)
            assertTrue("nothing may be written before the debounce elapses", repository.writeLog.isEmpty())

            advanceTimeBy(200)
            runCurrent()

            assertEquals(listOf("abc"), repository.writeLog)
            assertTrue(viewModel.uiState.value.saveStatus is SaveStatus.Saved)
    }

    @Test
    fun `continuous typing resets the debounce instead of saving every keystroke`() = runTest(mainDispatcher) {
        val repository = FakeNoteRepository()
        val viewModel = viewModel(repository)
            repeat(10) {
                viewModel.onContentChange("draft-$it")
                advanceTimeBy(500)
            }
            assertEquals("no write while the user is still typing", 0, repository.writeLog.size)

            advanceTimeBy(600)
            runCurrent()

            assertEquals(listOf("draft-9"), repository.writeLog)
    }

    @Test
    fun `a slow older save never overwrites newer text`() = runTest(mainDispatcher) {
        val repository = FakeNoteRepository()
        val viewModel = viewModel(repository)
            // First write is deliberately slow, so a second edit lands while it is in flight.
            repository.slowUpdateMillis = 5_000
            viewModel.onContentChange("first")
            advanceTimeBy(1_100)
            runCurrent()

            viewModel.onContentChange("second")
            advanceTimeBy(1_100)
            runCurrent()

            advanceTimeBy(10_000)
            runCurrent()

            assertEquals(
                "the newest text must be the last thing written",
                "second",
                repository.writeLog.last(),
            )
            assertTrue(
                "no staleness left behind",
                repository.notes.value.single().content == "second",
            )
    }

    @Test
    fun `edits during an in-flight save are persisted afterwards`() = runTest(mainDispatcher) {
        val repository = FakeNoteRepository()
        val viewModel = viewModel(repository, noteId = null)
            viewModel.onContentChange("one")
            advanceTimeBy(1_100)
            runCurrent()

            repository.slowUpdateMillis = 3_000
            viewModel.onContentChange("two")
            advanceTimeBy(1_100)
            runCurrent()

            viewModel.onContentChange("three")
            advanceTimeBy(1_100)
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()

            assertEquals("three", repository.notes.value.single().content)
    }

    @Test
    fun `flush writes immediately without waiting for the debounce`() = runTest(mainDispatcher) {
        val repository = FakeNoteRepository()
        val viewModel = viewModel(repository)
            viewModel.onContentChange("leaving now")
            viewModel.flush()

            assertEquals(listOf("leaving now"), repository.writeLog)
            assertTrue(viewModel.uiState.value.saveStatus is SaveStatus.Saved)
    }

    @Test
    fun `a failed write is reported as failed and never as saved`() = runTest(mainDispatcher) {
        val repository = FakeNoteRepository()
        val viewModel = viewModel(repository)
            repository.failWrites = true

            viewModel.onContentChange("doomed")
            advanceTimeBy(1_100)
            runCurrent()

            val status = viewModel.uiState.value.saveStatus
            assertTrue("status must be Failed, was $status", status is SaveStatus.Failed)
            assertTrue((status as SaveStatus.Failed).message.isNotBlank())
    }

    @Test
    fun `leaving an untouched new editor creates no note`() = runTest(mainDispatcher) {
        val repository = FakeNoteRepository()
        val viewModel = viewModel(repository)
            viewModel.flush()
            advanceTimeBy(2_000)
            runCurrent()

            assertEquals(0, repository.createCount)
            assertEquals(0, repository.writeLog.size)
    }

    @Test
    fun `the first edit creates the note and later edits update the same one`() = runTest(mainDispatcher) {
        val repository = FakeNoteRepository()
        val viewModel = viewModel(repository)
            viewModel.onContentChange("hello")
            advanceTimeBy(1_100)
            runCurrent()

            viewModel.onContentChange("hello world")
            advanceTimeBy(1_100)
            runCurrent()

            assertEquals("exactly one note may be created", 1, repository.createCount)
            assertEquals(1, repository.notes.value.size)
            assertEquals("hello world", repository.notes.value.single().content)
            assertEquals(repository.notes.value.single().id, viewModel.uiState.value.noteId)
    }

    @Test
    fun `an existing note loads its content into the editor`() = runTest(mainDispatcher) {
        val repository = FakeNoteRepository()
        runCurrent()
        repository.createNote("Title", "Body")
        val existingId = repository.notes.value.single().id

        val viewModel = viewModel(repository, noteId = existingId)
            advanceTimeBy(10)
            runCurrent()

            assertEquals("Title", viewModel.uiState.value.title)
            assertEquals("Body", viewModel.uiState.value.content)
            assertTrue(viewModel.uiState.value.isLoaded)
            assertNull(viewModel.uiState.value.loadError)
    }

    @Test
    fun `a tag added before the note exists is attached after the first save`() = runTest(mainDispatcher) {
        val repository = FakeNoteRepository()
        val tagRepository = FakeTagRepository()
        val viewModel = viewModel(repository, tagRepository)

        viewModel.addTag("工作")
        assertEquals("nothing to attach yet", 0, tagRepository.attachments.value.size)
        assertEquals(listOf("工作"), viewModel.uiState.value.tags.map { it.name })

        viewModel.onContentChange("正文")
        advanceTimeBy(1_100)
        runCurrent()

        val noteId = repository.notes.value.single().id
        assertEquals(1, tagRepository.attachments.value.size)
        assertEquals(noteId, tagRepository.attachments.value.single().noteId)
        assertTrue(viewModel.uiState.value.pendingTags.isEmpty())
    }

    @Test
    fun `duplicate tag names are not added twice`() = runTest(mainDispatcher) {
        val viewModel = viewModel(FakeNoteRepository(), FakeTagRepository())

        viewModel.addTag("工作")
        viewModel.addTag("  工作  ")
        viewModel.addTag("work")
        viewModel.addTag("WORK")

        // "工作" and "work" survive; the padded duplicate and the case variant do not.
        assertEquals(listOf("工作", "work"), viewModel.uiState.value.tags.map { it.name })
    }

    @Test
    fun `removing a pending tag drops it from the editor`() = runTest(mainDispatcher) {
        val viewModel = viewModel(FakeNoteRepository(), FakeTagRepository())
        viewModel.addTag("临时")

        viewModel.removeTag(viewModel.uiState.value.tags.single())

        assertTrue(viewModel.uiState.value.tags.isEmpty())
    }

    @Test
    fun `toggling preview keeps the text and the note id`() = runTest(mainDispatcher) {
        val repository = FakeNoteRepository()
        val viewModel = viewModel(repository)

        viewModel.onContentChange("预览内容")
        advanceTimeBy(1_100)
        runCurrent()
        val noteId = viewModel.uiState.value.noteId

        viewModel.togglePreview()
        assertTrue(viewModel.uiState.value.isPreview)
        assertEquals("预览内容", viewModel.uiState.value.content)
        assertEquals(noteId, viewModel.uiState.value.noteId)

        viewModel.togglePreview()
        assertEquals("预览内容", viewModel.uiState.value.content)
        assertTrue(!viewModel.uiState.value.isPreview)
    }

    @Test
    fun `deleting stops further writes`() = runTest(mainDispatcher) {
        val repository = FakeNoteRepository()
        val viewModel = viewModel(repository)

        viewModel.onContentChange("draft")
        advanceTimeBy(1_100)
        runCurrent()
        val writesBefore = repository.writeLog.size

        viewModel.deleteNote()
        advanceTimeBy(100)
        runCurrent()

        viewModel.onContentChange("after delete")
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals("nothing may be written after deletion", writesBefore, repository.writeLog.size)
        assertTrue(viewModel.uiState.value.isDeleted)
    }

    @Test
    fun `deleting with the trash off removes the note outright`() = runTest(mainDispatcher) {
        val repository = FakeNoteRepository()
        val settings = FakeNoteSettings(trashEnabled = false)
        val viewModel = viewModel(repository, noteSettings = settings)

        viewModel.onContentChange("gone either way")
        advanceTimeBy(1_100)
        runCurrent()

        viewModel.deleteNote()
        advanceTimeBy(100)
        runCurrent()

        assertTrue(viewModel.uiState.value.isDeleted)
        assertEquals("no note is left for the trash to hold", 0, repository.notes.value.size)
    }

    @Test
    fun `a failed delete keeps the editor open and says so`() = runTest(mainDispatcher) {
        val repository = FakeNoteRepository()
        val viewModel = viewModel(repository)

        viewModel.onContentChange("must not vanish")
        advanceTimeBy(1_100)
        runCurrent()
        val saved = repository.notes.value.single()

        repository.failDeletes = true
        viewModel.deleteNote()
        runCurrent()

        // isDeleted drives the automatic popBackStack: setting it after a failed write would look
        // exactly like a successful delete while the note is still there.
        assertTrue("the screen must not close", !viewModel.uiState.value.isDeleted)
        assertTrue(
            "the failure must be shown",
            viewModel.uiState.value.saveStatus is SaveStatus.Failed,
        )
        assertEquals("the note is untouched", saved, repository.notes.value.single())
    }

    @Test
    fun `deleting a note that was never saved just leaves`() = runTest(mainDispatcher) {
        val repository = FakeNoteRepository()
        val viewModel = viewModel(repository)

        viewModel.deleteNote()
        runCurrent()

        assertTrue(viewModel.uiState.value.isDeleted)
        assertTrue("nothing was ever written", repository.notes.value.isEmpty())
    }
}
