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
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import android.os.SystemClock
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

    // The text field's value is owned here, not rebuilt from the ViewModel's string on every
    // frame. Rebuilding it was what dropped an IME composition: TextFieldValue(text, selection)
    // carries no composition, and the editor rebuilt on every keystroke (docs/test-report.md P1).
    // rememberSaveable, so the caret and the composing buffer also survive a configuration change;
    // EditorContent stays composed while the preview is toggled, so they survive that too.
    //
    // No caret is planted on open. Nobody has tapped yet, so there is no caret to restore, and a
    // made-up one is not harmless: TextFieldValue(text, TextRange(content.length)) put it at the
    // end of the note, and anything that read the selection before the first tap landed (the
    // scroll effect waiting out the IME animation) computed "scroll to the bottom" from it. The
    // default selection is the document start, which is also where the view opens.
    var bodyValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(state.content))
    }

    // One body scroll state for the whole screen: EditorBody leaves composition when the preview
    // is shown, and a remember inside it would silently reset the reader to the top on return.
    val bodyScrollState = rememberScrollState()

    // One-way sync from the ViewModel: only when the text actually changed underneath the field
    // (first load, an import, another screen's edit) and never while a composition is open - that
    // would throw away what the IME is in the middle of.
    LaunchedEffect(state.content) {
        if (bodyValue.composition == null && bodyValue.text != state.content) {
            bodyValue = TextFieldValue(state.content)
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
                                if (state.trashEnabled) {
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
                            EditorBody(
                                value = bodyValue,
                                onValueChange = { value ->
                                    bodyValue = value
                                    onContentChange(value.text)
                                },
                                scrollState = bodyScrollState,
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
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    scrollState: ScrollState,
) {
    val bodyStyle: TextStyle = MaterialTheme.typography.bodyLarge
    val placeholderColor = MaterialTheme.colorScheme.onSurfaceVariant

    // The scroll belongs to this screen, not to BasicTextField's internal logic. Owning it is
    // what makes both rules hold at once: the editor opens at the top of the note, and when the
    // keyboard resizes the viewport the caret is re-revealed deterministically instead of the
    // field's internal scroll position being whatever the resize left behind.
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var viewportHeight by remember { mutableStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    // When the viewport last changed size. While the keyboard animates the height moves every
    // frame; chasing each intermediate value restarts the scroll below mid-flight, which is
    // the visible flash when tapping text. We wait for it to settle instead.
    var lastViewportChange by remember { mutableLongStateOf(0L) }
    // Scroll target currently being animated toward. Restarting animateScrollTo for the same
    // target cancels it mid-flight and re-starts it - also a flash. Let it finish instead.
    var activeTarget by remember { mutableStateOf<Int?>(null) }
    // The effect below deliberately does not restart when the selection changes, so it must not
    // read the value captured when it started either: that snapshot is stale by the time the wait
    // ends, and a caret from before the tap is exactly how it used to compute "scroll to the end".
    val latestValue by rememberUpdatedState(value)

    // Deliberately *not* keyed on WindowInsets.ime: the Activity root consumes that inset with
    // imePadding(), so by the time this composable reads it, it is zero and a guard on it makes
    // the whole effect a no-op. The resize itself is the signal - with adjustResize the keyboard
    // shrinks this viewport, which re-runs the effect with the smaller height.
    //
    // Deliberately *not* keyed on value.selection either: BasicTextField already follows the
    // cursor natively on tap/type, and a second actor scrolling on every selection change fights
    // it - that fight is the "jump to the bottom, then yanked back to the tap" bug. This effect
    // only corrects the viewport after the keyboard settles; it reads the current selection when
    // it runs, so it still lands on the right caret.
    //
    // Deliberately *not* keyed on focused: on tap, focus flips before the tap's selection lands
    // in bodyValue, so an effect that read the selection at that moment would compute a target
    // from the pre-tap caret. The keyboard resize always follows a tap-focus, so keying on the
    // viewport alone still covers it - and the snapshot is re-read after the wait for the rest.
    LaunchedEffect(textLayout, viewportHeight) {
        // Unfocused means the note is merely being read: opening it must show the top, not jump
        // to wherever the caret happens to rest.
        if (!focused || viewportHeight <= 0) {
            activeTarget = null
            return@LaunchedEffect
        }
        // If the viewport moved recently the keyboard is still animating. Each new height cancels
        // this run and starts a fresh wait, so only the settled height ever scrolls: one smooth
        // motion instead of an animation fighting every frame.
        while (SystemClock.uptimeMillis() - lastViewportChange < VIEWPORT_SETTLE_MS) {
            delay(50)
        }
        // Read the value *after* the wait: the tap's selection lands in bodyValue while this
        // effect is suspended, and the effect is not keyed on it (that would fight the field's
        // own cursor-following), so the captured `value` here is the pre-tap one.
        val current = latestValue
        val layout = textLayout ?: return@LaunchedEffect
        // The layout can lag behind the text by a frame; a cursor rect from a stale layout
        // points at the wrong place (the phantom jump-to-bottom). Skip until they agree.
        if (layout.layoutInput.text.text != current.text) return@LaunchedEffect
        val cursor = current.selection.end.coerceIn(0, current.text.length)
        val cursorRect = layout.getCursorRect(cursor)
        val top = cursorRect.top.roundToInt()
        val bottom = cursorRect.bottom.roundToInt()
        // Minimal displacement: if the caret is already visible nothing scrolls, so a tap in the
        // upper half of the viewport keeps its exact position when the keyboard opens.
        val target = when {
            top < scrollState.value -> top
            bottom > scrollState.value + viewportHeight -> bottom - viewportHeight
            else -> {
                activeTarget = null
                return@LaunchedEffect
            }
        }.coerceIn(0, scrollState.maxValue)
        if (activeTarget == target) return@LaunchedEffect
        activeTarget = target
        try {
            scrollState.animateScrollTo(target)
        } finally {
            activeTarget = null
        }
    }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged {
                if (it.height != viewportHeight) lastViewportChange = SystemClock.uptimeMillis()
                viewportHeight = it.height
            }
            .verticalScroll(scrollState)
            .onFocusChanged { focused = it.isFocused }
            .testTag(EditorTags.CONTENT),
        textStyle = bodyStyle.copy(color = MaterialTheme.colorScheme.onBackground),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        onTextLayout = { textLayout = it },
        decorationBox = { inner ->
            if (value.text.isEmpty()) {
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
// How long the viewport must sit still before a scroll reacts to it.
private const val VIEWPORT_SETTLE_MS = 200L

@Composable
private fun SaveStatus.label(): String = when (this) {
    SaveStatus.Idle -> ""
    SaveStatus.Pending -> stringResource(R.string.editor_status_pending)
    SaveStatus.Saving -> stringResource(R.string.editor_status_saving)
    is SaveStatus.Saved -> stringResource(R.string.editor_status_saved)
    is SaveStatus.Failed -> stringResource(R.string.editor_status_failed, this.message)
}
