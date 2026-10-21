package com.example.privatenotes.feature.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.privatenotes.data.local.NoteEntity
import com.example.privatenotes.data.repository.NoteRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class NoteListUiState(
    val notes: List<NoteEntity> = emptyList(),
    val isLoading: Boolean = true,
    /** Set when the encrypted store cannot be opened; never silently swallowed. */
    val errorMessage: String? = null,
)

/**
 * State for the note list.
 *
 * The repository is supplied lazily: opening the encrypted database can fail (the Keystore key
 * may have been invalidated), and that failure belongs on screen as an error state rather than
 * as a crash during ViewModel construction.
 */
class NoteListViewModel(
    private val repositoryProvider: () -> NoteRepository,
) : ViewModel() {

    val uiState: StateFlow<NoteListUiState> = flow {
        emitAll(repositoryProvider().observeActiveNotes())
    }
        .map { NoteListUiState(notes = it, isLoading = false) }
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

    fun deleteNote(noteId: String) {
        viewModelScope.launch { runCatching { repositoryProvider().moveToTrash(noteId) } }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(repositoryProvider: () -> NoteRepository): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { NoteListViewModel(repositoryProvider) }
            }
    }
}
