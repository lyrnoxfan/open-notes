package com.example.privatenotes.feature.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.privatenotes.R
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

    if (tagDialogOpen) {
        TagDialog(
            onDismiss = { tagDialogOpen = false },
            onConfirm = { name ->
                viewModel.addTag(name)
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
                    IconButton(onClick = viewModel::togglePreview) {
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
                            text = { Text(stringResource(R.string.editor_delete)) },
                            onClick = {
                                menuOpen = false
                                viewModel.deleteNote()
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp),
        ) {
            TextField(
                value = state.title,
                onValueChange = viewModel::onTitleChange,
                modifier = Modifier.fillMaxWidth().testTag(EditorTags.TITLE),
                placeholder = {
                    Text(
                        text = stringResource(R.string.editor_title_hint),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                },
                textStyle = MaterialTheme.typography.headlineSmall,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                singleLine = true,
                colors = transparentFieldColors(),
            )

            TagEditorRow(
                tags = state.tags,
                onAdd = { tagDialogOpen = true },
                onRemove = viewModel::removeTag,
            )

            if (state.isPreview) {
                MarkdownText(
                    markdown = state.content,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag(EditorTags.PREVIEW),
                )
            } else {
                TextField(
                    value = TextFieldValue(text = state.content, selection = selection),
                    onValueChange = { value ->
                        selection = value.selection
                        viewModel.onContentChange(value.text)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag(EditorTags.CONTENT),
                    placeholder = {
                        Text(
                            text = stringResource(R.string.editor_content_hint),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = transparentFieldColors(),
                )
            }
        }
    }
}

@Composable
private fun TagEditorRow(
    tags: List<EditorTag>,
    onAdd: () -> Unit,
    onRemove: (EditorTag) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start),
    ) {
        AssistChip(
            onClick = onAdd,
            label = { Text(stringResource(R.string.editor_add_tag)) },
            modifier = Modifier.testTag(EditorTags.ADD_TAG),
        )
        tags.forEach { tag ->
            AssistChip(
                onClick = { onRemove(tag) },
                label = { Text(tag.name, style = MaterialTheme.typography.labelMedium) },
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.editor_remove_tag, tag.name),
                        modifier = Modifier.padding(start = 2.dp),
                    )
                },
            )
        }
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

@Composable
private fun transparentFieldColors(): TextFieldColors = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    cursorColor = MaterialTheme.colorScheme.primary,
)

@Composable
private fun SaveStatus.label(): String = when (this) {
    SaveStatus.Idle -> ""
    SaveStatus.Pending -> stringResource(R.string.editor_status_pending)
    SaveStatus.Saving -> stringResource(R.string.editor_status_saving)
    is SaveStatus.Saved -> stringResource(R.string.editor_status_saved)
    is SaveStatus.Failed -> stringResource(R.string.editor_status_failed, this.message)
}
