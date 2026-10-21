package com.example.privatenotes.feature.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.privatenotes.data.local.TagEntity
import com.example.privatenotes.data.repository.NoteRepository
import com.example.privatenotes.data.repository.TagRepository
import com.example.privatenotes.data.repository.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the editor tells the user about their text (§7.1 step 5). */
sealed interface SaveStatus {
    /** Nothing typed yet, or an existing note loaded cleanly. */
    data object Idle : SaveStatus

    /** Edits are in memory and waiting for the debounce window. */
    data object Pending : SaveStatus

    data object Saving : SaveStatus

    data class Saved(val atMillis: Long) : SaveStatus

    /** The write genuinely failed. Never shown as "saved". */
    data class Failed(val message: String) : SaveStatus
}

/** A tag chip in the editor; [id] is null while the note has not been created yet. */
data class EditorTag(val id: String?, val name: String)

data class NoteEditorUiState(
    val noteId: String? = null,
    val title: String = "",
    val content: String = "",
    val isLoaded: Boolean = false,
    val saveStatus: SaveStatus = SaveStatus.Idle,
    val loadError: String? = null,
    val isDeleted: Boolean = false,
    val isPreview: Boolean = false,
    val persistedTags: List<TagEntity> = emptyList(),
    /** Tags chosen before the note existed; attached right after the first save. */
    val pendingTags: List<String> = emptyList(),
) {
    val tags: List<EditorTag>
        get() = persistedTags.map { EditorTag(it.id, it.name) } +
            pendingTags.map { EditorTag(null, it) }
}

/**
 * Editor state and the auto-save pipeline (§7.1).
 *
 * The pipeline is built around one rule: **a write is only applied if it carries the newest
 * revision**. Every keystroke bumps a revision counter and publishes a snapshot; a debounced
 * collector persists whatever snapshot is current after ~1s of quiet; a `Mutex` serialises
 * writes so they cannot interleave.
 *
 * That combination is what stops an older save from overwriting newer text: the slow first save
 * finishes, sees that the revision it holds is no longer the newest persisted one, and a later
 * save with the higher revision has already written or will write the newer content. Simple
 * debounce alone would not survive a save that is still in flight when the user types again.
 */
class NoteEditorViewModel(
    private val repository: NoteRepository,
    private val tagRepository: TagRepository,
    private val existingNoteId: String?,
    private val timeSource: TimeSource,
    /** Outlives the ViewModel so a final save can finish after the screen is gone. */
    private val externalScope: CoroutineScope,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        NoteEditorUiState(
            noteId = existingNoteId,
            // A brand-new note has nothing to load.
            isLoaded = existingNoteId == null,
        ),
    )
    val uiState: StateFlow<NoteEditorUiState> = _uiState.asStateFlow()

    private data class Snapshot(val revision: Long, val title: String, val content: String)

    private val mutex = Mutex()
    private var revision = 0L
    private var persistedRevision = 0L
    private var noteId: String? = existingNoteId
    private var deleted = false

    private val pending = MutableStateFlow<Snapshot?>(null)

    init {
        if (existingNoteId != null) {
            viewModelScope.launch {
                val note = runCatching { repository.findById(existingNoteId) }.getOrNull()
                if (note == null) {
                    _uiState.update { it.copy(isLoaded = true, loadError = LOAD_ERROR) }
                } else {
                    _uiState.update { it.copy(title = note.title, content = note.content, isLoaded = true) }
                }
            }
        }

        viewModelScope.launch {
            pending.filterNotNull().debounce(debounceMillis).collect { snapshot ->
                persist(snapshot)
            }
        }

        viewModelScope.launch {
            _uiState
                .map { it.noteId }
                .distinctUntilChanged()
                .flatMapLatest { id ->
                    if (id == null) flowOf(emptyList()) else tagRepository.observeTagsForNote(id)
                }
                .collect { tags -> _uiState.update { it.copy(persistedTags = tags) } }
        }
    }

    fun togglePreview() {
        _uiState.update { it.copy(isPreview = !it.isPreview) }
    }

    /**
     * Adds a tag by name, creating it if needed.
     *
     * A brand-new note has no id yet, so the name waits in [NoteEditorUiState.pendingTags] and is
     * attached as part of the first save. That keeps tag entry usable before the first keystroke
     * has been persisted.
     */
    fun addTag(rawName: String) {
        val name = rawName.trim()
        if (name.isEmpty()) return
        if (_uiState.value.tags.any { it.name.equals(name, ignoreCase = true) }) return

        val id = noteId
        if (id == null) {
            _uiState.update { it.copy(pendingTags = it.pendingTags + name) }
            return
        }
        viewModelScope.launch {
            val tagId = runCatching { tagRepository.ensureTag(name) }.getOrNull() ?: return@launch
            runCatching { tagRepository.attachTag(id, tagId) }
        }
    }

    fun removeTag(tag: EditorTag) {
        val id = tag.id
        if (id == null) {
            _uiState.update { state -> state.copy(pendingTags = state.pendingTags - tag.name) }
            return
        }
        val noteId = noteId ?: return
        viewModelScope.launch {
            runCatching { tagRepository.detachTag(noteId, id) }
        }
    }

    fun onTitleChange(value: String) = edit(title = value)

    fun onContentChange(value: String) = edit(content = value)

    private fun edit(title: String? = null, content: String? = null) {
        if (deleted) return
        val current = _uiState.value
        val nextTitle = title ?: current.title
        val nextContent = content ?: current.content
        if (nextTitle == current.title && nextContent == current.content) return

        revision += 1
        _uiState.update {
            it.copy(title = nextTitle, content = nextContent, saveStatus = SaveStatus.Pending)
        }
        pending.value = Snapshot(revision, nextTitle, nextContent)
    }

    /**
     * Saves whatever is currently in the editor, bypassing the debounce.
     *
     * Called when leaving the editor and when the app goes to the background (§7.1).
     */
    suspend fun flush() {
        persist(Snapshot(revision, _uiState.value.title, _uiState.value.content))
    }

    /** Same as [flush], but survives the ViewModel being cleared mid-write. */
    fun flushInBackground() {
        externalScope.launch { runCatching { flush() } }
    }

    fun deleteNote() {
        if (deleted) return
        deleted = true
        pending.value = null
        val id = noteId
        viewModelScope.launch {
            if (id != null) runCatching { repository.moveToTrash(id) }
            _uiState.update { it.copy(isDeleted = true) }
        }
    }

    private suspend fun persist(snapshot: Snapshot) {
        mutex.withLock {
            if (deleted) return
            if (snapshot.revision <= persistedRevision) return
            // Opening the editor and leaving without typing must not create an empty note.
            if (noteId == null && snapshot.title.isBlank() && snapshot.content.isBlank()) return

            _uiState.update { it.copy(saveStatus = SaveStatus.Saving) }
            try {
                val currentId = noteId
                if (currentId == null) {
                    val created = repository.createNote(snapshot.title, snapshot.content)
                    noteId = created
                    _uiState.update { it.copy(noteId = created) }
                    attachPendingTags(created)
                } else {
                    repository.updateNote(currentId, snapshot.title, snapshot.content)
                }
                persistedRevision = snapshot.revision
                _uiState.update { it.copy(saveStatus = SaveStatus.Saved(timeSource.nowMillis())) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                // The status has to say so: a silent "saved" over a failed write is the exact
                // fake affordance §13.8 rules out.
                _uiState.update {
                    it.copy(saveStatus = SaveStatus.Failed(failure.message ?: SAVE_ERROR))
                }
            }
        }
    }

    private suspend fun attachPendingTags(createdNoteId: String) {
        val waiting = _uiState.value.pendingTags
        if (waiting.isEmpty()) return
        for (name in waiting) {
            val tagId = runCatching { tagRepository.ensureTag(name) }.getOrNull() ?: continue
            runCatching { tagRepository.attachTag(createdNoteId, tagId) }
        }
        _uiState.update { it.copy(pendingTags = emptyList()) }
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 1_000L
        private const val LOAD_ERROR = "笔记不存在或已被删除"
        private const val SAVE_ERROR = "保存失败"

        fun factory(
            repository: NoteRepository,
            tagRepository: TagRepository,
            noteId: String?,
            timeSource: TimeSource,
            externalScope: CoroutineScope,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                NoteEditorViewModel(
                    repository = repository,
                    tagRepository = tagRepository,
                    existingNoteId = noteId,
                    timeSource = timeSource,
                    externalScope = externalScope,
                )
            }
        }
    }
}
