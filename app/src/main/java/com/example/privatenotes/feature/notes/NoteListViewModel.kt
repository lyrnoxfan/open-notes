package com.example.privatenotes.feature.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.privatenotes.data.local.NoteWithTags
import com.example.privatenotes.data.local.TagEntity
import com.example.privatenotes.data.repository.NoteRepository
import com.example.privatenotes.data.repository.TagRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class NoteListUiState(
    val notes: List<NoteWithTags> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    val query: String = "",
    val selectedTagId: String? = null,
    val isLoading: Boolean = true,
    /** Set when the encrypted store cannot be opened; never silently swallowed. */
    val errorMessage: String? = null,
) {
    val isFiltering: Boolean get() = query.isNotBlank() || selectedTagId != null

    /** True when the list is empty because of the current filter, not because nothing exists. */
    val isFilterEmpty: Boolean get() = notes.isEmpty() && isFiltering
}

/**
 * State for the note list: search term and tag filter feed one query (§7.3).
 *
 * The repository is supplied lazily: opening the encrypted database can fail (the Keystore key
 * may have been invalidated), and that failure belongs on screen as an error state rather than
 * as a crash during ViewModel construction.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteListViewModel(
    private val repositoryProvider: () -> NoteRepository,
    private val tagRepositoryProvider: () -> TagRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val selectedTagId = MutableStateFlow<String?>(null)

    private val results = combine(query, selectedTagId) { term, tagId -> term to tagId }
        .flatMapLatest { (term, tagId) -> repositoryProvider().observeNotes(term, tagId) }

    // Deferred to collection time so a database that cannot be opened surfaces as an error
    // state instead of a crash during construction.
    private val tags = flow { emitAll(tagRepositoryProvider().observeAll()) }

    val uiState: StateFlow<NoteListUiState> =
        combine(results, tags, query, selectedTagId) { notes, allTags, term, tagId ->
            NoteListUiState(
                notes = notes,
                tags = allTags,
                query = term,
                selectedTagId = tagId,
                isLoading = false,
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

    fun deleteNote(noteId: String) {
        viewModelScope.launch { runCatching { repositoryProvider().moveToTrash(noteId) } }
    }

    /** Puts back a note the user swiped away by mistake, before the trash cleanup can touch it. */
    fun undoDelete(noteId: String) {
        viewModelScope.launch { runCatching { repositoryProvider().restoreFromTrash(noteId) } }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(
            repositoryProvider: () -> NoteRepository,
            tagRepositoryProvider: () -> TagRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { NoteListViewModel(repositoryProvider, tagRepositoryProvider) }
        }
    }
}
