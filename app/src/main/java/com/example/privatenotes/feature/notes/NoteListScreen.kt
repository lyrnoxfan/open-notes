package com.example.privatenotes.feature.notes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import com.example.privatenotes.ui.components.NoteButton as Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.privatenotes.ui.theme.LocalActionColors
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
 * Deleting a single note is a swipe, undone from the snackbar. Picking many notes starts with a
 * long press; while a selection is open, taps toggle instead of open and the top bar carries the
 * batch actions. With the trash switched off the swipe is gone - a gesture that cannot be undone
 * must not be able to destroy a note - and batch deletion asks first.
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
    val deletedPermanentLabel = stringResource(R.string.note_list_deleted_permanent)

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
        onToggleSelected = viewModel::toggleSelected,
        onStartSelection = viewModel::startSelection,
        onSelectAll = viewModel::selectAll,
        onClearSelection = viewModel::clearSelection,
        onDeleteSelected = {
            // Nothing to delete means nothing to report, and no snackbar to offer an undo for.
            if (state.selection.isEmpty()) return@NoteListContent
            val ids = state.selection.toList()
            val trashOn = state.trashEnabled
            viewModel.deleteSelected { deleted ->
                // The snackbar must describe what happened, not what was attempted: an undo
                // action over a failed write restores nothing.
                scope.launch {
                    if (deleted == 0) return@launch
                    if (trashOn) {
                        val result = snackbarHostState.showSnackbar(
                            message = deletedLabel,
                            actionLabel = undoLabel,
                            duration = SnackbarDuration.Short,
                        )
                        if (result == SnackbarResult.ActionPerformed) viewModel.restoreSelected(ids)
                    } else {
                        snackbarHostState.showSnackbar(message = deletedPermanentLabel)
                    }
                }
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
    onToggleSelected: (String) -> Unit,
    onStartSelection: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmPermanentDelete by rememberSaveable { mutableStateOf(false) }

    if (confirmPermanentDelete) {
        AlertDialog(
            onDismissRequest = { confirmPermanentDelete = false },
            title = { Text(stringResource(R.string.note_list_batch_confirm_title, state.selection.size)) },
            text = { Text(stringResource(R.string.note_list_batch_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmPermanentDelete = false
                    onDeleteSelected()
                }) {
                    Text(
                        text = stringResource(R.string.note_list_delete_selected),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmPermanentDelete = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            if (state.isSelecting) {
                SelectionTopBar(
                    count = state.selection.size,
                    onSelectAll = onSelectAll,
                    onDeleteSelected = {
                        if (state.trashEnabled) onDeleteSelected() else confirmPermanentDelete = true
                    },
                    onClearSelection = onClearSelection,
                )
            } else {
                ListTopBar(
                    onOpenTrash = onOpenTrash,
                    onOpenSettings = onOpenSettings,
                    trashEnabled = state.trashEnabled,
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreateNote,
                modifier = Modifier.testTag(NoteListTags.CREATE),
                shape = MaterialTheme.shapes.large,
                containerColor = LocalActionColors.current.first,
                contentColor = LocalActionColors.current.second,
                elevation = androidx.compose.material3.FloatingActionButtonDefaults.elevation(
                    defaultElevation = 2.dp,
                    pressedElevation = 3.dp,
                    focusedElevation = 2.dp,
                    hoveredElevation = 3.dp,
                ),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = stringResource(R.string.note_list_new),
                    modifier = Modifier.size(24.dp),
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

                    state.notes.isEmpty() -> EmptyState(onCreateNote = onCreateNote)

                    else -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxHeight().readingWidth(),
                            contentPadding = PaddingValues(top = 4.dp, bottom = 88.dp),
                        ) {
                            items(state.notes, key = { it.note.id }) { entry ->
                                val deleteActionLabel = if (state.trashEnabled) {
                                    stringResource(R.string.note_list_moved_to_trash)
                                } else {
                                    null
                                }
                                val row: @Composable () -> Unit = {
                                    NoteRow(
                                        entry = entry,
                                        selecting = state.isSelecting,
                                        picked = entry.note.id in state.selection,
                                        onClick = {
                                            if (state.isSelecting) {
                                                onToggleSelected(entry.note.id)
                                            } else {
                                                onOpenNote(entry.note.id)
                                            }
                                        },
                                        onLongClick = {
                                            if (state.isSelecting) {
                                                onToggleSelected(entry.note.id)
                                            } else {
                                                onStartSelection(entry.note.id)
                                            }
                                        },
                                        onDelete = { onDeleteNote(entry.note.id) },
                                        deleteActionLabel = deleteActionLabel,
                                    )
                                }
                                val itemModifier = Modifier.animateItem(
                                    fadeInSpec = tween(MOTION_LIST_MS),
                                    placementSpec = tween(MOTION_LIST_MS),
                                    fadeOutSpec = tween(MOTION_LIST_MS),
                                )
                                if (state.trashEnabled && !state.isSelecting) {
                                    SwipeableNoteRow(
                                        modifier = itemModifier,
                                        onDelete = { onDeleteNote(entry.note.id) },
                                        content = row,
                                    )
                                } else {
                                    // Without a drag recognizer, combinedClickable can accept a
                                    // horizontal move that stays inside the row as a tap.
                                    // Consume drags even when deletion is unavailable, without
                                    // intercepting taps, long presses or vertical list scrolling.
                                    Box(modifier = itemModifier.pointerInput(Unit) {
                                        detectHorizontalDragGestures { change, _ -> change.consume() }
                                    }) {
                                        row()
                                    }
                                }
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
private fun ListTopBar(
    onOpenTrash: () -> Unit,
    onOpenSettings: () -> Unit,
    trashEnabled: Boolean,
) {
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
            if (trashEnabled) {
                IconButton(onClick = onOpenTrash, modifier = Modifier.testTag(NoteListTags.TRASH)) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteSweep,
                        contentDescription = stringResource(R.string.note_list_trash),
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            actionIconContentColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    onSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit,
    onClearSelection: () -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClearSelection) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.note_list_exit_selection),
                )
            }
        },
        title = {
            Text(
                text = stringResource(R.string.note_list_selection_title, count),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        actions = {
            IconButton(onClick = onSelectAll) {
                Icon(
                    imageVector = Icons.Outlined.SelectAll,
                    contentDescription = stringResource(R.string.note_list_select_all),
                )
            }
            IconButton(onClick = onDeleteSelected) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.note_list_delete_selected),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
            actionIconContentColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val borderColor = if (query.isEmpty()) {
        Color.Transparent
    } else {
        MaterialTheme.colorScheme.primary
    }

    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp)
            .border(1.dp, borderColor, MaterialTheme.shapes.medium)
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
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
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
 * forth cannot delete anything by accident. The row is passed in as content: the very same
 * composable renders without deletion when the trash is off or a selection is open.
 * That plain wrapper still consumes horizontal drags so they cannot become row clicks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableNoteRow(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
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
        enableDismissFromEndToStart = true,
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.tertiaryContainer)
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        content()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteRow(
    entry: NoteWithTags,
    selecting: Boolean,
    picked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
    deleteActionLabel: String?,
) {
    val note = entry.note
    Column(
        modifier = Modifier
            .testTag("note_row_${note.id}")
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(MaterialTheme.shapes.large)
            .background(if (picked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .border(
                width = if (picked) 1.dp else 0.5.dp,
                color = if (picked) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = MaterialTheme.shapes.large,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics {
                if (selecting) selected = picked
                deleteActionLabel?.let { label ->
                    customActions = listOf(
                        CustomAccessibilityAction(label) {
                            onDelete()
                            true
                        },
                    )
                }
            }
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selecting) {
                Icon(
                    imageVector = if (picked) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = if (picked) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = note.title.ifBlank { stringResource(R.string.note_untitled) },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Truncated before the regexes run and cached per note version: the list shows two lines,
                // so stripping Markdown out of a 500 KB body on every recomposition was pure waste.
                val excerpt = remember(note.id, note.updatedAt) { plainExcerpt(note.content) }
                if (excerpt.isNotBlank()) {
                    Text(
                        text = excerpt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
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

/** One sentence plus a direct way into the editor (design.md §9): the empty screen is paper, not a poster. */
@Composable
private fun EmptyState(onCreateNote: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 40.dp),
    ) {
        Text(
            text = stringResource(R.string.note_list_empty_title),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onCreateNote,
            modifier = Modifier
                .padding(top = 20.dp)
                .heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.note_list_empty_action))
        }
    }
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
    const val SETTINGS = "note_list_settings"
}

/**
 * Excerpt without Markdown punctuation, so the list reads as text rather than source (§4.3).
 *
 * The fence stripping runs first (it needs the original text to find a fence), then the string is
 * cut to [EXCERPT_CHARS] before the punctuation regexes and the whitespace collapse. A 500 KB note
 * used to be scanned in full, three times, to produce two visible lines.
 */
internal fun plainExcerpt(markdown: String): String =
    markdown
        .replace(CODE_FENCE, " ")
        .take(EXCERPT_CHARS)
        .replace(MARKDOWN_PUNCTUATION, "")
        .replace(WHITESPACE_RUN, " ")
        .trim()

/** Two lines at 15sp are about 120 characters; 300 covers a wide screen. */
internal const val EXCERPT_CHARS = 300

private val CODE_FENCE = Regex("```[\\s\\S]*?```")
private val MARKDOWN_PUNCTUATION = Regex("[#>*_`~\\[\\]()]")
private val WHITESPACE_RUN = Regex("\\s+")

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
