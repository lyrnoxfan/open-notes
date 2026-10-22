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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
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
    var menuOpen by remember { mutableStateOf(false) }
    var tagDialogOpen by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(TextRange.Zero) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // §7.1: leaving the editor must not drop the last keystrokes, and neither must going to
    // the background. The write runs outside the ViewModel scope so it can finish after the
    // screen is gone.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.flushInBackground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.flushInBackground()
        }
    }

    // Place the caret at the end of freshly loaded text, and keep it inside bounds if the text
    // was replaced underneath it.
    LaunchedEffect(state.isLoaded, state.content.length) {
        if (selection.end > state.content.length) {
            selection = TextRange(state.content.length)
        }
    }

    LaunchedEffect(state.isDeleted) {
        if (state.isDeleted) onBack()
    }

    EditorContent(
        state = state,
        selection = selection,
        onSelectionChange = { selection = it },
        onTitleChange = viewModel::onTitleChange,
        onContentChange = viewModel::onContentChange,
        onTogglePreview = viewModel::togglePreview,
        onDelete = viewModel::deleteNote,
        onAddTag = viewModel::addTag,
        onRemoveTag = viewModel::removeTag,
        onBack = onBack,
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
    selection: TextRange,
    onSelectionChange: (TextRange) -> Unit,
    onTitleChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onTogglePreview: () -> Unit,
    onDelete: () -> Unit,
    onAddTag: (String) -> Unit,
    onRemoveTag: (EditorTag) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var tagDialogOpen by remember { mutableStateOf(false) }

    if (tagDialogOpen) {
        TagDialog(
            onDismiss = { tagDialogOpen = false },
            onConfirm = { name ->
                onAddTag(name)
                tagDialogOpen = false
            },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
                    IconButton(onClick = onTogglePreview) {
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
                                onDelete()
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
                            EditorBody(
                                content = state.content,
                                selection = selection,
                                onValueChange = { value ->
                                    onSelectionChange(value.selection)
                                    onContentChange(value.text)
                                },
                            )
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
private fun EditorBody(
    content: String,
    selection: TextRange,
    onValueChange: (TextFieldValue) -> Unit,
) {
    val bodyStyle: TextStyle = MaterialTheme.typography.bodyLarge
    val placeholderColor = MaterialTheme.colorScheme.onSurfaceVariant

    BasicTextField(
        value = TextFieldValue(text = content, selection = selection),
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxSize()
            .testTag(EditorTags.CONTENT),
        textStyle = bodyStyle.copy(color = MaterialTheme.colorScheme.onBackground),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { inner ->
            if (content.isEmpty()) {
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
