package com.example.privatenotes.feature.notes

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.drop
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.privatenotes.R
import com.example.privatenotes.ui.components.ContentMaxWidth
import com.example.privatenotes.ui.markdown.MarkdownText

/**
 * Note editor.
 *
 * There is no save button: the text is persisted by the debounce pipeline in
 * [NoteEditorViewModel], and leaving the screen or backgrounding the app forces a final write.
 * The status line reflects what the database actually did.
 *
 * Edit and preview share one string held by the ViewModel, so switching between them can neither
 * lose text nor lose uncommitted input; the caret lives here and survives the switch (§7.2).
 *
 * Both modes are laid out by the same column, with the same 24dp gutters and the same fonts, so
 * the first line of the note stays where it was when the preview is toggled. The previous version
 * had a Material `TextField` (which carries about 16dp of internal horizontal padding) next to a
 * `TextView` with none, and the text jumped sideways on every toggle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    viewModel: NoteEditorViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.isDeleted) {
        if (state.isDeleted) onBack()
    }

    EditorContent(
        state = state,
        onTitleChange = viewModel::onTitleChange,
        onContentChange = viewModel::onContentChange,
        onTogglePreview = viewModel::togglePreview,
        onDelete = viewModel::deleteNote,
        onAddTag = viewModel::addTag,
        onRemoveTag = viewModel::removeTag,
        onBack = onBack,
        onFlush = viewModel::flushInBackground,
        modifier = modifier,
    )
}

/**
 * The editor as a pure function of its state, so every combination can be previewed: empty,
 * long, preview mode, saving, failed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorContent(
    state: NoteEditorUiState,
    onTitleChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onTogglePreview: () -> Unit,
    onDelete: () -> Unit,
    onAddTag: (String) -> Unit,
    onRemoveTag: (EditorTag) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onFlush: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    var tagDialogOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // A failed save is easy to miss when the status line is not being watched: buzz once so the
    // failure is felt, not just seen.
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(state.saveStatus) {
        if (state.saveStatus is SaveStatus.Failed) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    // One editing buffer owns text, selection and IME composition, independent of Room's
    // asynchronous String snapshots. The state-based field exposes its real scroll state and
    // does not issue the legacy field's stale selection-at-focus bring-into-view request.
    val bodyState = rememberTextFieldState(state.content, TextRange.Zero)
    val currentOnContentChange by rememberUpdatedState(onContentChange)
    LaunchedEffect(bodyState) {
        snapshotFlow { bodyState.text.toString() }.drop(1).collect { currentOnContentChange(it) }
    }
    // Load once. Saved String echoes must never replace a newer editing buffer or reset its
    // selection/composition; edits flow out of TextFieldState, not back into it on each save.
    var bodyLoaded by remember { mutableStateOf(state.isLoaded) }
    LaunchedEffect(state.isLoaded) {
        if (state.isLoaded && !bodyLoaded) {
            bodyState.edit {
                replace(0, length, state.content)
                selection = TextRange.Zero
            }
            bodyLoaded = true
        }
    }
    androidx.activity.compose.BackHandler {
        onContentChange(bodyState.text.toString())
        onBack()
    }
    // Lifecycle saves must sample the actual buffer BEFORE flushing the ViewModel. Otherwise
    // an immediate background/exit can outrun snapshotFlow's final keystroke delivery.
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnFlush by rememberUpdatedState(onFlush)
    DisposableEffect(lifecycleOwner, bodyState) {
        fun flushBuffer() {
            if (bodyLoaded) currentOnContentChange(bodyState.text.toString())
            currentOnFlush()
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) flushBuffer()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            flushBuffer()
        }
    }

    if (tagDialogOpen) {
        TagDialog(
            onDismiss = { tagDialogOpen = false },
            onConfirm = { name ->
                onAddTag(name)
                tagDialogOpen = false
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.editor_delete_confirm_title)) },
            text = { Text(stringResource(R.string.editor_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onContentChange(bodyState.text.toString())
                    onDelete()
                }) {
                    Text(
                        text = stringResource(R.string.editor_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = {
                        onContentChange(bodyState.text.toString())
                        onBack()
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.editor_back),
                        )
                    }
                },
                title = {
                    Text(
                        text = state.saveStatus.label(),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.testTag(EditorTags.STATUS),
                        color = when (state.saveStatus) {
                            is SaveStatus.Failed -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    IconButton(onClick = {
                        onContentChange(bodyState.text.toString())
                        onTogglePreview()
                    }) {
                        Icon(
                            imageVector = Icons.Outlined.Visibility,
                            contentDescription = stringResource(
                                if (state.isPreview) R.string.editor_edit else R.string.editor_preview,
                            ),
                            tint = if (state.isPreview) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onBackground
                            },
                        )
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = stringResource(R.string.editor_more),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(R.string.editor_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                if (state.trashEnabled) {
                                    onContentChange(bodyState.text.toString())
                                    onDelete()
                                } else {
                                    // The trash is off: this delete is permanent and irreversible.
                                    confirmDelete = true
                                }
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
            ) {
                EditorTitle(
                    title = state.title,
                    onTitleChange = onTitleChange,
                )

                TagEditorRow(
                    tags = state.tags,
                    onAdd = { tagDialogOpen = true },
                    onRemove = onRemoveTag,
                )

                // One shared box for both modes: the geometry cannot differ between them.
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    Crossfade(
                        targetState = state.isPreview,
                        animationSpec = tween(durationMillis = MOTION_MS),
                        label = "editor-preview",
                    ) { preview ->
                        if (preview) {
                            MarkdownText(
                                markdown = state.content,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag(EditorTags.PREVIEW),
                            )
                        } else {
                            EditorBody(state = bodyState)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorTitle(title: String, onTitleChange: (String) -> Unit) {
    val titleStyle = MaterialTheme.typography.headlineSmall
    // No alpha on the placeholder: at 70% the ink drops under 4.5:1 in dark mode.
    val placeholderColor = MaterialTheme.colorScheme.onSurfaceVariant

    BasicTextField(
        value = title,
        onValueChange = onTitleChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp)
            .testTag(EditorTags.TITLE),
        textStyle = titleStyle.copy(color = MaterialTheme.colorScheme.onBackground),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        decorationBox = { inner ->
            if (title.isEmpty()) {
                Text(
                    text = stringResource(R.string.editor_title_hint),
                    style = titleStyle,
                    color = placeholderColor,
                )
            }
            inner()
        },
    )
}

@Composable
private fun EditorBody(state: TextFieldState) {
    val bodyStyle: TextStyle = MaterialTheme.typography.bodyLarge
    val placeholderColor = MaterialTheme.colorScheme.onSurfaceVariant

    val scroll = rememberScrollState()
    var focused by remember { mutableStateOf(false) }
    var viewportHeight by remember { mutableStateOf(0) }
    var layout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }

    // The native field follows edits/selection changes. A keyboard-only resize can leave the
    // logical selection unchanged; correct only that geometry event, using the SAME native
    // scroll state. Never key this on focus or drag: reading a long note must remain scrollable
    // independently of its old caret. A frame lets the tap selection and new measure settle.
    LaunchedEffect(viewportHeight) {
        if (!focused || viewportHeight <= 0) return@LaunchedEffect
        withFrameNanos { }
        val result = layout ?: return@LaunchedEffect
        val caret = result.getCursorRect(state.selection.end.coerceIn(0, state.text.length))
        val top = scroll.value.toFloat()
        val correction = when {
            caret.bottom > top + viewportHeight -> caret.bottom - (top + viewportHeight)
            caret.top < top -> caret.top - top
            else -> 0f
        }
        if (correction != 0f) scroll.scrollTo((scroll.value + correction).toInt())
    }

    BasicTextField(
        state = state,
        scrollState = scroll,
        onTextLayout = { getResult -> layout = getResult() },
        modifier = Modifier
            .fillMaxSize()
            .onFocusChanged { focused = it.isFocused }
            .onSizeChanged { viewportHeight = it.height }
            .testTag(EditorTags.CONTENT),
        textStyle = bodyStyle.copy(color = MaterialTheme.colorScheme.onBackground),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorator = TextFieldDecorator { inner ->
            if (state.text.isEmpty()) {
                Text(
                    text = stringResource(R.string.editor_content_hint),
                    style = bodyStyle,
                    color = placeholderColor,
                )
            }
            inner()
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagEditorRow(
    tags: List<EditorTag>,
    onAdd: () -> Unit,
    onRemove: (EditorTag) -> Unit,
) {
    // Wraps rather than scrolls: at fontScale 2.0 four pills do not fit on one line, and a row
    // that silently hides the last tag is worse than a second line.
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TagPill(text = stringResource(R.string.editor_add_tag), onClick = onAdd)
        tags.forEach { tag ->
            TagPill(
                text = tag.name,
                onClick = { onRemove(tag) },
                trailing = {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.editor_remove_tag, tag.name),
                        modifier = Modifier.size(14.dp),
                    )
                },
            )
        }
    }
}

/** A hairline capsule, matching the filter chips on the list. */
@Composable
private fun TagPill(
    text: String,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.background, MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        trailing?.invoke()
    }
}

@Composable
private fun TagDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_add_tag)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.editor_tag_hint)) },
                modifier = Modifier.testTag(EditorTags.TAG_INPUT),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

/** Stable handles for instrumented UI tests. */
object EditorTags {
    const val TITLE = "editor_title"
    const val CONTENT = "editor_content"
    const val PREVIEW = "editor_preview"
    const val STATUS = "editor_status"
    const val ADD_TAG = "editor_add_tag"
    const val TAG_INPUT = "editor_tag_input"
}

/** design.md §7: two motion durations, nothing else. */
internal const val MOTION_MS = 150

@Composable
private fun SaveStatus.label(): String = when (this) {
    SaveStatus.Idle -> ""
    SaveStatus.Pending -> stringResource(R.string.editor_status_pending)
    SaveStatus.Saving -> stringResource(R.string.editor_status_saving)
    is SaveStatus.Saved -> stringResource(R.string.editor_status_saved)
    is SaveStatus.Failed -> stringResource(R.string.editor_status_failed, this.message)
}
