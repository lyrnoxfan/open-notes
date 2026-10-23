package com.example.privatenotes.feature.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.privatenotes.data.NoteSettings
import com.example.privatenotes.data.local.NoteWithTags
import com.example.privatenotes.data.local.TagEntity
import com.example.privatenotes.data.repository.NoteRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NoteListUiState(
    val notes: List<NoteWithTags> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    val query: String = "",
    val selectedTagId: String? = null,
    val isLoading: Boolean = true,
    /** Set when the encrypted store cannot be opened; never silently swallowed. */
    val errorMessage: String? = null,
    /** Notes picked for batch deletion; empty while no selection is going on. */
    val selection: Set<String> = emptySet(),
    val trashEnabled: Boolean = true,
) {
    val isFiltering: Boolean get() = query.isNotBlank() || selectedTagId != null

    /** True when the list is empty because of the current filter, not because nothing exists. */
    val isFilterEmpty: Boolean get() = notes.isEmpty() && isFiltering

    val isSelecting: Boolean get() = selection.isNotEmpty()
}

/**
 * State for the note list: search, tag filter and batch selection feed one screen (§4.3, §7.3).
 *
 * The repository is supplied lazily: opening the encrypted database can fail (the Keystore key
 * may have been invalidated), and that failure belongs on screen as an error state rather than
 * as a crash during ViewModel construction.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class NoteListViewModel(
    private val repositoryProvider: () -> NoteRepository,
    private val noteSettings: NoteSettings,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val selectedTagId = MutableStateFlow<String?>(null)
    private val selection = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Typing runs a `LIKE '%term%'` over title and body, which cannot use an index and touches
     * every row. Waiting for the typing to settle turns one query per keystroke into one query per
     * search; clearing the box is not typing, so it stays immediate.
     */
    private val results = combine(query, selectedTagId) { term, tagId -> term to tagId }
        .debounce { (term, _) -> if (term.isEmpty()) 0L else SEARCH_DEBOUNCE_MILLIS }
        .flatMapLatest { (term, tagId) -> repositoryProvider().observeNotes(term, tagId) }

    private data class ListData(
        val notes: List<NoteWithTags>,
        val tags: List<TagEntity>,
        val term: String,
        val tagId: String?,
    )

    // The catalog must not depend on the search/tag-filtered result. Otherwise searching
    // hides unrelated filters, and deleting the last result traps the UI in a dead tag.
    // Deleted notes keep their joins for undo/restore, but do not contribute list filters.
    private val availableTags = flow {
        emitAll(repositoryProvider().observeNotes("", null))
    }.map { notes ->
        notes.flatMap { it.tags }.distinctBy { it.id }.sortedBy { it.name }
    }.onEach { tags ->
        val selected = selectedTagId.value
        if (selected != null && tags.none { it.id == selected }) selectedTagId.value = null
    }

    private val listData = combine(results, availableTags, query, selectedTagId) { notes, tags, term, tagId ->
        ListData(notes, tags, term, tagId)
    }

    val uiState: StateFlow<NoteListUiState> =
        combine(listData, selection, noteSettings.trashEnabled) { data, picked, trashOn ->
            NoteListUiState(
                notes = data.notes,
                tags = data.tags,
                query = data.term,
                selectedTagId = data.tagId,
                isLoading = false,
                selection = picked,
                trashEnabled = trashOn,
            )
        }
            .catch { throwable ->
                emit(
                    NoteListUiState(
                        isLoading = false,
                        errorMessage = throwable.message ?: throwable::class.java.simpleName,
                    ),
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = NoteListUiState(),
            )

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onTagSelected(tagId: String?) {
        selectedTagId.value = if (selectedTagId.value == tagId) null else tagId
    }

    fun clearFilters() {
        query.value = ""
        selectedTagId.value = null
    }

    // ---------------------------------------------------------------- selection

    /** Enters selection mode with one note picked; long-press is the entry point. */
    fun startSelection(noteId: String) {
        selection.value = setOf(noteId)
    }

    fun toggleSelected(noteId: String) {
        selection.update { if (noteId in it) it - noteId else it + noteId }
    }

    /** Picks every note currently visible, so a filter doubles as a way to scope the batch. */
    fun selectAll() {
        selection.value = uiState.value.notes.map { it.note.id }.toSet()
    }

    fun clearSelection() {
        selection.value = emptySet()
    }

    /**
     * Deletes the picked notes the way the trash switch says: into the trash with an undo, or
     * straight to permanent removal when it is off.
     *
     * [onDone] receives how many were actually removed, so the caller can tell a real delete from
     * one that failed instead of offering an undo for it.
     */
    fun deleteSelected(onDone: (deleted: Int) -> Unit = {}) {
        val ids = selection.value.toList()
        if (ids.isEmpty()) return
        val trashOn = noteSettings.trashEnabled.value
        viewModelScope.launch {
            // Only ids that still exist can be removed; counting the request instead would report
            // notes that a concurrent edit already took away.
            val existing = ids.filter { runCatching { repositoryProvider().findById(it) }.getOrNull() != null }
            val removed = runCatching {
                if (trashOn) {
                    repositoryProvider().moveToTrashAll(existing)
                } else {
                    repositoryProvider().deletePermanentlyAll(existing)
                }
                existing.size
            }.getOrDefault(0)
            clearSelection()
            onDone(removed)
        }
    }

    /** Puts back a batch deleted by mistake; only possible while the trash is on. */
    fun restoreSelected(ids: List<String>) {
        viewModelScope.launch {
            ids.forEach { id -> runCatching { repositoryProvider().restoreFromTrash(id) } }
        }
    }

    fun deleteNote(noteId: String) {
        viewModelScope.launch { runCatching { repositoryProvider().moveToTrash(noteId) } }
    }

    /** Puts back a note the user swiped away by mistake, before the trash cleanup can touch it. */
    fun undoDelete(noteId: String) {
        viewModelScope.launch { runCatching { repositoryProvider().restoreFromTrash(noteId) } }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        /**
         * Long enough that a word typed at speed produces one query, short enough that the list
         * feels like it filters as you type. A pinyin IME commits a run of characters at once, so
         * a composition does not produce a query per letter.
         */
        internal const val SEARCH_DEBOUNCE_MILLIS = 250L

        fun factory(
            repositoryProvider: () -> NoteRepository,
            noteSettings: NoteSettings,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { NoteListViewModel(repositoryProvider, noteSettings) }
        }
    }
}
