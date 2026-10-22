package com.example.privatenotes.feature.trash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.privatenotes.R
import com.example.privatenotes.data.local.NoteEntity
import com.example.privatenotes.data.repository.NoteRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

/**
 * Trash (§8): shows when each note was deleted and how long is left, with restore and
 * permanent deletion behind a confirmation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    viewModel: TrashViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pendingDeletion by remember { mutableStateOf<NoteEntity?>(null) }

    LaunchedEffect(Unit) { viewModel.purgeExpired() }

    TrashContent(
        notes = state.notes,
        isLoading = state.isLoading,
        errorMessage = state.errorMessage,
        onRestore = viewModel::restore,
        onDeleteForever = viewModel::permanentlyDelete,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * The trash as a pure function of its state, so the three branches can be previewed without a
 * database.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrashContent(
    notes: List<NoteEntity>,
    isLoading: Boolean,
    errorMessage: String?,
    onRestore: (String) -> Unit,
    onDeleteForever: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDeletion by remember { mutableStateOf<NoteEntity?>(null) }

    pendingDeletion?.let { note ->
        AlertDialog(
            onDismissRequest = { pendingDeletion = null },
            title = { Text(stringResource(R.string.trash_confirm_title)) },
            text = { Text(stringResource(R.string.trash_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteForever(note.id)
                        pendingDeletion = null
                    },
                ) {
                    Text(
                        text = stringResource(R.string.trash_confirm_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletion = null }) {
                    Text(stringResource(R.string.trash_cancel))
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
                            contentDescription = stringResource(R.string.trash_back),
                        )
                    }
                },
                title = {
                    Text(
                        text = stringResource(R.string.trash_title),
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            when {
                isLoading -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                errorMessage != null -> Text(
                    text = errorMessage!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(32.dp),
                )
                // One sentence, like the empty note list (design.md §9).
                notes.isEmpty() -> Text(
                    text = stringResource(R.string.trash_empty_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 40.dp),
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(notes, key = { it.id }) { note ->
                        TrashRow(
                            note = note,
                            onRestore = { onRestore(note.id) },
                            onDeleteForever = { pendingDeletion = note },
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrashRow(
    note: NoteEntity,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
) {
    val deletedAt = note.deletedAt ?: return
    val now = System.currentTimeMillis()
    val remainingDays = remainingDays(deletedAt, now)

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text(
            text = note.title.ifBlank { stringResource(R.string.note_untitled) },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(R.string.trash_deleted_at, formatTimestamp(deletedAt)) + "　" +
                if (remainingDays <= 0) {
                    stringResource(R.string.trash_cleanup_today)
                } else {
                    stringResource(R.string.trash_remaining_days, remainingDays)
                },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        FlowRow(
            modifier = Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TextButton(
                onClick = onRestore,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 6.dp),
            ) {
                Text(
                    text = stringResource(R.string.trash_restore),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            TextButton(
                onClick = onDeleteForever,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 6.dp),
            ) {
                Text(
                    text = stringResource(R.string.trash_delete_forever),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** Whole days left before automatic cleanup; never negative. */
internal fun remainingDays(deletedAtMillis: Long, nowMillis: Long): Int {
    val expiresAt = deletedAtMillis + NoteRepository.TRASH_RETENTION_MILLIS
    val remaining = expiresAt - nowMillis
    if (remaining <= 0) return 0
    return ceil(remaining / (24.0 * 60 * 60 * 1000)).toInt()
}

private val trashTimestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun formatTimestamp(epochMillis: Long): String =
    trashTimestampFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
