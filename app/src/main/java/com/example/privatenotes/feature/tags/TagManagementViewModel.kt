package com.example.privatenotes.feature.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.privatenotes.data.local.TagEntity
import com.example.privatenotes.data.repository.TagRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One row of the tag manager: the tag and how many notes currently carry it. */
data class TagUsage(
    val id: String,
    val name: String,
    val noteCount: Int,
)

data class TagManagementUiState(
    val tags: List<TagUsage> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    /** Set after a rename merged into an existing tag, so the page can say so. */
    val mergedInto: String? = null,
    /** Set after a validation failure, e.g. a name that is too long. */
    val problem: String? = null,
)

/**
 * Tag management (§5.2, and the gap listed in the README's known limitations).
 *
 * Renaming and deleting already work in `TagRepository`; this is the missing screen. The
 * repository's behaviour is untouched: a rename onto an existing name still merges the two tags,
 * and deleting a tag still removes only the links, never the notes.
 */
class TagManagementViewModel(
    private val tagRepositoryProvider: () -> TagRepository,
) : ViewModel() {

    private val mergedInto = MutableStateFlow<String?>(null)
    private val problem = MutableStateFlow<String?>(null)

    private val tags = flow { emitAll(tagRepositoryProvider().observeAll()) }

    private val usages = tags.map { list ->
        list.map { tag ->
            val count = runCatching { tagRepositoryProvider().usageCount(tag.id) }.getOrDefault(0)
            TagUsage(id = tag.id, name = tag.name, noteCount = count)
        }
    }

    val uiState: StateFlow<TagManagementUiState> =
        combine(usages, mergedInto, problem) { rows, merged, issue ->
            TagManagementUiState(
                tags = rows,
                isLoading = false,
                mergedInto = merged,
                problem = issue,
            )
        }
            .catch { throwable ->
                emit(
                    TagManagementUiState(
                        isLoading = false,
                        errorMessage = throwable.message ?: throwable::class.java.simpleName,
                    ),
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = TagManagementUiState(),
            )

    /**
     * Renames a tag. Returns nothing: the outcome is published as state, because a merge needs a
     * sentence on screen rather than a silent success.
     */
    fun rename(tagId: String, newName: String, existingNames: Set<String>) {
        val clean = newName.trim()
        if (clean.isEmpty()) {
            problem.value = "标签名不能为空"
            return
        }
        if (clean.length > TagRepository.MAX_NAME_LENGTH) {
            problem.value = "标签名最多 ${TagRepository.MAX_NAME_LENGTH} 个字符"
            return
        }
        viewModelScope.launch {
            val current = tagsOf(tagId)?.name
            val willMerge = current != null &&
                !current.equals(clean, ignoreCase = true) &&
                existingNames.any { it.equals(clean, ignoreCase = true) }
            val ok = runCatching { tagRepositoryProvider().renameTag(tagId, clean) }.getOrDefault(false)
            when {
                !ok -> problem.value = "改名失败"
                // The repository merges instead of failing, so say so rather than reporting success.
                willMerge -> mergedInto.value = clean
                else -> Unit
            }
        }
    }

    fun delete(tagId: String) {
        viewModelScope.launch {
            runCatching { tagRepositoryProvider().deleteTag(tagId) }
                .onFailure { problem.value = "删除失败：${it.message ?: "未知原因"}" }
        }
    }

    fun consumeMessage() {
        mergedInto.value = null
        problem.value = null
    }

    private suspend fun tagsOf(tagId: String): TagEntity? =
        runCatching { tagRepositoryProvider().observeAll().first().firstOrNull { it.id == tagId } }
            .getOrNull()

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(tagRepositoryProvider: () -> TagRepository): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { TagManagementViewModel(tagRepositoryProvider) }
            }
    }
}
