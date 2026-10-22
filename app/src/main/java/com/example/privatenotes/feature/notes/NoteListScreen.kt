package com.example.privatenotes.feature.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.privatenotes.R
import com.example.privatenotes.data.local.NoteWithTags
import com.example.privatenotes.ui.components.TagFilterRow
import com.example.privatenotes.ui.components.TagLabelRow
import com.example.privatenotes.ui.components.readingWidth
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Note list (design.md, plan §4.3): page title, search field, tag filter, entries with title,
 * excerpt, tags and update time, plus a create entry and a way into the trash.
 *
 * Deleting from here is a swipe, not a permanent button in every row: the row keeps its full
 * width for the text, a stray tap cannot destroy anything, and the move is undone from the
 * snackbar. The same action is exposed as an accessibility action for TalkBack and switch users.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteListScreen(
    viewModel: NoteListViewModel,
    onCreateNote: () -> Unit,
    onOpenNote: (String) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val undoLabel = stringResource(R.string.note_list_undo)
    val deletedLabel = stringResource(R.string.note_list_moved_to_trash)

    NoteListContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onCreateNote = onCreateNote,
        onOpenNote = onOpenNote,
        onOpenTrash = onOpenTrash,
        onOpenSettings = onOpenSettings,
        onQueryChange = viewModel::onQueryChange,
        onTagSelected = { name ->
            viewModel.onTagSelected(state.tags.firstOrNull { it.name == name }?.id)
        },
        onClearFilters = viewModel::clearFilters,
        onDeleteNote = { noteId ->
            viewModel.deleteNote(noteId)
            scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = deletedLabel,
                    actionLabel = undoLabel,
                    duration = SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete(noteId)
            }
        },
    )
}

/**
 * The list as a pure function of its state.
 *
 * Split from the screen so every branch - loading, error, filtered-empty, empty, long - can be
 * rendered in a `@Preview` without a database, a ViewModel or a device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteListContent(
    state: NoteListUiState,
    snackbarHostState: SnackbarHostState,
    onCreateNote: () -> Unit,
    onOpenNote: (String) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenSettings: () -> Unit,
    onQueryChange: (String) -> Unit,
    onTagSelected: (String?) -> Unit,
    onClearFilters: () -> Unit,
    onDeleteNote: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.testTag(NoteListTags.SETTINGS),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.note_list_settings),
                        )
                    }
                    IconButton(onClick = onOpenTrash, modifier = Modifier.testTag(NoteListTags.TRASH)) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteSweep,
                            contentDescription = stringResource(R.string.note_list_trash),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreateNote,
                modifier = Modifier.testTag(NoteListTags.CREATE),
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                elevation = androidx.compose.material3.FloatingActionButtonDefaults.elevation(
                    defaultElevation = 0.dp,
                    pressedElevation = 0.dp,
                    focusedElevation = 0.dp,
                    hoveredElevation = 0.dp,
                ),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = stringResource(R.string.note_list_new),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(modifier = Modifier.readingWidth()) {
                    SearchField(query = state.query, onQueryChange = onQueryChange)

                    TagFilterRow(
                        tags = state.tags.map { it.name },
                        selectedTag = state.tags.firstOrNull { it.id == state.selectedTagId }?.name,
                        onSelect = { name ->
                            onTagSelected(state.tags.firstOrNull { it.name == name }?.id)
                        },
                    )
                }
            }

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    state.isLoading -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)

                    state.errorMessage != null -> ErrorState(state.errorMessage!!)

                    state.isFilterEmpty -> NoMatches(
                        query = state.query,
                        onClear = onClearFilters,
                    )

                    state.notes.isEmpty() -> EmptyState()

                    else -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxHeight().readingWidth(),
                            contentPadding = PaddingValues(bottom = 88.dp),
                        ) {
                            items(state.notes, key = { it.note.id }) { entry ->
                                SwipeableNoteRow(
                                    modifier = Modifier.animateItem(
                                        fadeInSpec = tween(MOTION_LIST_MS),
                                        placementSpec = tween(MOTION_LIST_MS),
                                        fadeOutSpec = tween(MOTION_LIST_MS),
                                    ),
                                    entry = entry,
                                    onClick = { onOpenNote(entry.note.id) },
                                    onDelete = { onDeleteNote(entry.note.id) },
                                    deleteActionLabel = stringResource(R.string.note_list_moved_to_trash),
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 20.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    thickness = 1.dp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val borderColor = if (query.isEmpty()) {
        MaterialTheme.colorScheme.outlineVariant
    } else {
        MaterialTheme.colorScheme.primary
    }

    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp)
            .border(1.dp, borderColor, MaterialTheme.shapes.small)
            .testTag(NoteListTags.SEARCH),
        placeholder = {
            Text(
                text = stringResource(R.string.note_list_search_hint),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.note_list_clear_search),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        shape = MaterialTheme.shapes.small,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

/**
 * A row that can be swiped left to move the note to the trash.
 *
 * `enableDismissFromStartToEnd = false` keeps the gesture one-directional, so scrolling back and
 * forth cannot delete anything by accident.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableNoteRow(
    entry: NoteWithTags,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    deleteActionLabel: String,
    modifier: Modifier = Modifier,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        },
    )

    SwipeToDismissBox(
        modifier = modifier,
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        NoteRow(
            entry = entry,
            onClick = onClick,
            deleteActionLabel = deleteActionLabel,
            onDelete = onDelete,
        )
    }
}

@Composable
private fun NoteRow(
    entry: NoteWithTags,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    deleteActionLabel: String,
) {
    val note = entry.note
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClick = onClick)
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(deleteActionLabel) {
                        onDelete()
                        true
                    },
                )
            }
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 16.dp),
    ) {
        Text(
            text = note.title.ifBlank { stringResource(R.string.note_untitled) },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (note.content.isNotBlank()) {
            Text(
                text = plainExcerpt(note.content),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Row(
            modifier = Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatListTimestamp(note.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TagLabelRow(tags = entry.tags.map { it.name })
        }
    }
}

/** One sentence, no illustration (design.md §9): the empty screen is paper, not a poster. */
@Composable
private fun EmptyState() {
    Text(
        text = stringResource(R.string.note_list_empty_title),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 40.dp),
    )
}

@Composable
private fun NoMatches(query: String, onClear: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 40.dp),
    ) {
        Text(
            text = stringResource(R.string.note_list_no_match),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        TextButton(onClick = onClear, modifier = Modifier.padding(top = 4.dp)) {
            Text(stringResource(R.string.note_list_clear_filter))
        }
    }
}

@Composable
private fun ErrorState(message: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 32.dp),
    ) {
        Text(
            text = stringResource(R.string.note_list_error_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = stringResource(R.string.note_list_error_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** design.md §7: list items move in 180ms, everything else in 150ms. */
internal const val MOTION_LIST_MS = 180

/** Stable handles for instrumented UI tests. */
object NoteListTags {
    const val CREATE = "note_list_create"
    const val TRASH = "note_list_trash"
    const val SEARCH = "note_list_search"
    const val MORE = "note_list_more"
    const val SETTINGS = "note_list_settings"
}

/** Excerpt without Markdown punctuation, so the list reads as text rather than source (§4.3). */
internal fun plainExcerpt(markdown: String): String =
    markdown
        .replace(Regex("```[\\s\\S]*?```"), " ")
        .replace(Regex("[#>*_`~\\[\\]()]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val thisYearFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日")
private val olderFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日")

/**
 * Timestamps read the way a person thinks about them: the time of day for today, the day for
 * this year, the full date before that. A shared `yyyy-MM-dd HH:mm` on every row is precise and
 * unreadable (§4.3 asks for a quiet list).
 */
internal fun formatListTimestamp(
    epochMillis: Long,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val instant = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val date: LocalDate = instant.toLocalDate()
    return when {
        date == today -> timeFormatter.format(instant)
        date.year == today.year -> thisYearFormatter.format(instant)
        else -> olderFormatter.format(instant)
    }
}
