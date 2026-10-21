package com.lyrnox.opennotes.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lyrnox.opennotes.data.NoteRepository
import com.lyrnox.opennotes.data.local.entity.NoteEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class NotesUiState(
    val notes: List<NoteEntity> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
)

/**
 * Screen state for the note list.
 *
 * The repository is supplied lazily: opening the encrypted database can fail (for example
 * when the Keystore key was invalidated by a lock-screen change), and that failure belongs
 * in the UI as an error state rather than as a crash during ViewModel construction.
 */
class NotesViewModel(
    private val repositoryProvider: () -> NoteRepository,
) : ViewModel() {

    val uiState: StateFlow<NotesUiState> = flow {
        val repository = repositoryProvider()
        emitAll(repository.observeActiveNotes())
    }
        .map { NotesUiState(notes = it, isLoading = false) }
        .catch { throwable ->
            emit(
                NotesUiState(
                    isLoading = false,
                    errorMessage = throwable.message ?: throwable::class.java.simpleName,
                ),
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = NotesUiState(),
        )

    fun addNote(now: Long) {
        viewModelScope.launch {
            runCatching {
                repositoryProvider().createNote(
                    title = "Untitled note",
                    content = "",
                    now = now,
                )
            }
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(repositoryProvider: () -> NoteRepository): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { NotesViewModel(repositoryProvider) }
            }
    }
}
