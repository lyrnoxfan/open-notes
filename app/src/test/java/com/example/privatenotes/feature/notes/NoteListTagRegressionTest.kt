package com.example.privatenotes.feature.notes

import com.example.privatenotes.data.FakeNoteSettings
import com.example.privatenotes.data.local.NoteEntity
import com.example.privatenotes.data.local.NoteWithTags
import com.example.privatenotes.data.local.TagEntity
import com.example.privatenotes.data.repository.FakeNoteRepository
import com.example.privatenotes.data.repository.NoteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class NoteListTagRegressionTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() = Dispatchers.resetMain()
    private val a = TagEntity("a", "旅行", 1, 1)
    private val b = TagEntity("b", "工作", 1, 1)
    private val raw = FakeNoteRepository()
    private val tags = mapOf("one" to listOf(a), "two" to listOf(b), "shared" to listOf(a))
    private val repository = object : NoteRepository by raw {
        override fun observeNotes(term: String, tagId: String?) = raw.notes.map { rows ->
            rows.filter { it.deletedAt == null && (it.title.contains(term) || it.content.contains(term)) &&
                (tagId == null || tags[it.id].orEmpty().any { t -> t.id == tagId }) }
                .map { NoteWithTags(it, tags[it.id].orEmpty()) }
        }
    }
    private fun seed() {
        raw.notes.value = listOf(NoteEntity("one", "甲", "杭州", 1, 1), NoteEntity("two", "乙", "会议", 1, 1))
    }
    @Test fun searchAndTagSelectionDoNotEraseOtherAvailableFilters() = runTest(dispatcher) {
        seed()
        val vm = NoteListViewModel({ repository }, FakeNoteSettings())
        val collect = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceTimeBy(1)
        vm.onQueryChange("杭州")
        advanceTimeBy(300)
        assertEquals(listOf("one"), vm.uiState.value.notes.map { it.note.id })
        assertEquals(setOf("a", "b"), vm.uiState.value.tags.map { it.id }.toSet())
        vm.onQueryChange("")
        vm.onTagSelected("a")
        advanceTimeBy(1)
        assertEquals(setOf("a", "b"), vm.uiState.value.tags.map { it.id }.toSet())
        collect.cancel()
    }
    @Test fun deletingLastSelectedTagClearsDeadFilterAndUndoRestoresTags() = runTest(dispatcher) {
        seed()
        val vm = NoteListViewModel({ repository }, FakeNoteSettings())
        val collect = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceTimeBy(1)
        vm.onTagSelected("a")
        advanceTimeBy(1)
        vm.deleteNote("one")
        advanceTimeBy(1)
        assertNull(vm.uiState.value.selectedTagId)
        assertEquals(listOf("two"), vm.uiState.value.notes.map { it.note.id })
        assertEquals(listOf("b"), vm.uiState.value.tags.map { it.id })
        vm.undoDelete("one")
        advanceTimeBy(1)
        assertEquals(setOf("a", "b"), vm.uiState.value.tags.map { it.id }.toSet())
        assertEquals(listOf(a), tags["one"])
        collect.cancel()
    }
    @Test fun deletingOneNoteKeepsSharedTagAvailable() = runTest(dispatcher) {
        seed()
        raw.notes.value += NoteEntity("shared", "丙", "别的文章", 1, 1)
        val vm = NoteListViewModel({ repository }, FakeNoteSettings())
        val collect = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceTimeBy(1)
        vm.deleteNote("one")
        advanceTimeBy(1)
        assertEquals(setOf("a", "b"), vm.uiState.value.tags.map { it.id }.toSet())
        collect.cancel()
    }
}
