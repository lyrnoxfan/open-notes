package com.example.privatenotes.feature.notes

import com.example.privatenotes.data.FakeNoteSettings
import com.example.privatenotes.data.local.NoteEntity
import com.example.privatenotes.data.local.NoteWithTags
import com.example.privatenotes.data.local.TagEntity
import com.example.privatenotes.data.repository.NoteRepository
import com.example.privatenotes.data.repository.TagRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Counting, not timing: how many queries does one search produce?
 *
 * A `LIKE '%term%'` cannot use an index, so the only thing that matters is how often it runs.
 * Before the debounce, four keystrokes meant four queries; clearing the box is not typing and
 * must not wait.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteListSearchDebounceTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun installMainDispatcher() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
    }

    @After
    fun restoreMainDispatcher() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun fourKeystrokesProduceOneQuery() = runTest(dispatcher) {
        val counter = CountingRepository()
        val viewModel = NoteListViewModel({ counter }, FakeNoteSettings())
        val job = TestScope(dispatcher).launch { viewModel.uiState.collect {} }
        advanceTimeBy(1)

        listOf("会", "会议", "会议记", "会议记录").forEach { term ->
            viewModel.onQueryChange(term)
            advanceTimeBy(50) // 50 ms between keystrokes: well inside the debounce window
        }
        advanceTimeBy(NoteListViewModel.SEARCH_DEBOUNCE_MILLIS + 50)

        // One unfiltered subscription supplies the tag catalog, one supplies initial rows,
        // and the four keystrokes must still add exactly one settled search query.
        assertEquals("catalog + initial rows + one settled search, not per-keystroke", 3, counter.calls)
        job.cancel()
    }

    @Test
    fun clearingTheBoxIsImmediate() = runTest(dispatcher) {
        val counter = CountingRepository()
        val viewModel = NoteListViewModel({ counter }, FakeNoteSettings())
        val job = TestScope(dispatcher).launch { viewModel.uiState.collect {} }
        advanceTimeBy(1)
        val beforeClear = counter.calls

        viewModel.onQueryChange("会议")
        advanceTimeBy(NoteListViewModel.SEARCH_DEBOUNCE_MILLIS + 10)
        viewModel.onQueryChange("")
        advanceTimeBy(1)

        assertTrue(
            "clearing must issue a query without waiting for the debounce (before=$beforeClear, after=${counter.calls})",
            counter.calls > beforeClear + 1,
        )
        job.cancel()
    }

    @Test
    fun theOldPipelineIssuedOneQueryPerKeystroke() = runTest(dispatcher) {
        // The pipeline before this change, reproduced here so both counts come from one run:
        // combine(...).flatMapLatest { observeNotes } with no debounce in between.
        val counter = CountingRepository()
        val query = MutableStateFlow("")
        val tagId = MutableStateFlow<String?>(null)
        val legacy = kotlinx.coroutines.flow.combine(query, tagId) { term, tag -> term to tag }
            .flatMapLatest { (term, tag) -> counter.observeNotes(term, tag) }
        val job = TestScope(dispatcher).launch { legacy.collect {} }
        advanceTimeBy(1)

        listOf("会", "会议", "会议记", "会议记录").forEach { term ->
            query.value = term
            advanceTimeBy(50)
        }
        advanceTimeBy(NoteListViewModel.SEARCH_DEBOUNCE_MILLIS + 50)

        assertEquals("one query per keystroke before the debounce", 5, counter.calls)
        job.cancel()
    }

    @Test
    fun debounceIsBetween200And300Milliseconds() {
        assertTrue(NoteListViewModel.SEARCH_DEBOUNCE_MILLIS in 200..300)
    }

    /** Counts `observeNotes` calls; every call is one round of the LIKE scan. */
    private class CountingRepository : NoteRepository {
        var calls = 0
            private set

        override fun observeNotes(term: String, tagId: String?): Flow<List<NoteWithTags>> {
            calls++
            return flowOf(emptyList())
        }

        override fun observeActiveNotes(): Flow<List<NoteEntity>> = emptyFlow()
        override fun observeTrash(): Flow<List<NoteEntity>> = emptyFlow()
        override fun search(term: String): Flow<List<NoteEntity>> = emptyFlow()
        override suspend fun findById(id: String): NoteEntity? = null
        override suspend fun createNote(title: String, content: String): String = ""
        override suspend fun updateNote(id: String, title: String, content: String) = Unit
        override suspend fun moveToTrash(id: String) = Unit
        override suspend fun moveToTrashAll(ids: List<String>) = Unit
        override suspend fun restoreFromTrash(id: String) = Unit
        override suspend fun permanentlyDelete(id: String) = Unit
        override suspend fun deletePermanentlyAll(ids: List<String>) = Unit
        override suspend fun purgeExpiredTrash(nowMillis: Long): Int = 0
        override suspend fun activeNoteCount(): Int = 0
    }

    private object NoTags : TagRepository {
        override fun observeAll(): Flow<List<TagEntity>> = MutableStateFlow(emptyList())
        override suspend fun findByName(name: String): TagEntity? = null
        override suspend fun ensureTag(name: String): String? = null
        override suspend fun renameTag(tagId: String, newName: String): Boolean = false
        override suspend fun deleteTag(tagId: String) = Unit
        override suspend fun usageCount(tagId: String): Int = 0
        override suspend fun attachTag(noteId: String, tagId: String) = Unit
        override suspend fun detachTag(noteId: String, tagId: String) = Unit
        override fun observeTagsForNote(noteId: String): Flow<List<TagEntity>> = flowOf(emptyList())
    }
}
