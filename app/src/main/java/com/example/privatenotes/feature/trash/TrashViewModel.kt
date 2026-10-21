package com.example.privatenotes.feature.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.privatenotes.data.local.NoteEntity
import com.example.privatenotes.data.repository.NoteRepository
import com.example.privatenotes.data.repository.TimeSource
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TrashUiState(
    val notes: List<NoteEntity> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
)

/** Trash list, restore, and permanent deletion (§8). */
class TrashViewModel(
    private val repository: NoteRepository,
    private val timeSource: TimeSource,
) : ViewModel() {

    val uiState: StateFlow<TrashUiState> = repository.observeTrash()
        .map { TrashUiState(notes = it, isLoading = false) }
        .catch { throwable ->
            emit(
                TrashUiState(
                    isLoading = false,
                    errorMessage = throwable.message ?: throwable::class.java.simpleName,
                ),
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = TrashUiState(),
        )

    fun restore(noteId: String) {
        viewModelScope.launch { runCatching { repository.restoreFromTrash(noteId) } }
    }

    fun permanentlyDelete(noteId: String) {
        viewModelScope.launch { runCatching { repository.permanentlyDelete(noteId) } }
    }

    /** Clears anything past the 30-day window (§8). */
    fun purgeExpired() {
        viewModelScope.launch {
            runCatching { repository.purgeExpiredTrash(timeSource.nowMillis()) }
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(repository: NoteRepository, timeSource: TimeSource): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { TrashViewModel(repository, timeSource) }
            }
    }
}
