package com.example.privatenotes.feature.backup

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import com.example.privatenotes.R
import com.example.privatenotes.data.backup.BackupErrorKind
import com.example.privatenotes.data.backup.BackupFailure
import com.example.privatenotes.data.backup.BackupFileKind
import com.example.privatenotes.data.backup.ImportOutcome
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Backup settings and status (§9.6).
 *
 * The page shows the directory, both last-success times and both last failures separately,
 * because "backup failed" is useless without knowing which destination failed and why.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsScreen(
    viewModel: BackupSettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var showLocalBackupDialog by rememberSaveable { mutableStateOf(false) }
    var showRemoteBackupDialog by rememberSaveable { mutableStateOf(false) }

    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val persisted = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }.isSuccess
            if (persisted) {
                val name = runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()
                viewModel.onLocalDirectorySelected(uri.toString(), name)
            } else {
                viewModel.onDirectoryPermissionFailed()
            }
        }
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.inspectImport(uri)
    }

    val message = state.message
    val snackbarMessage = if (message != null) noticeText(message) else null
    LaunchedEffect(message) {
        if (snackbarMessage != null) {
            snackbarHostState.showSnackbar(snackbarMessage)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.backup_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.backup_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (state.busy) {
                item {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            item {
                LocalBackupSection(
                    state = state,
                    onChooseDirectory = { directoryPicker.launch(null) },
                    onBackupNow = { showLocalBackupDialog = true },
                )
            }

            item {
                WebDavSection(
                    state = state,
                    onBaseUrlChange = viewModel::onBaseUrlChange,
                    onUsernameChange = viewModel::onUsernameChange,
                    onRemoteDirectoryChange = viewModel::onRemoteDirectoryChange,
                    onPasswordChange = viewModel::onPasswordChange,
                    onSave = viewModel::saveWebDav,
                    onTest = viewModel::testConnection,
                    onUploadNow = { showRemoteBackupDialog = true },
                )
            }

            item {
                ImportSection(
                    state = state,
                    onPickFile = { importPicker.launch(arrayOf("*/*")) },
                )
            }

            item {
                Spacer(modifier = Modifier.heightIn(min = 8.dp))
            }
        }
    }

    if (showLocalBackupDialog) {
        EncryptedBackupDialog(
            title = stringResource(R.string.backup_section_local),
            confirmLabel = stringResource(R.string.backup_do_backup),
            onDismiss = { showLocalBackupDialog = false },
            onConfirm = { password ->
                showLocalBackupDialog = false
                viewModel.backupLocal(password)
            },
        )
    }

    if (showRemoteBackupDialog) {
        EncryptedBackupDialog(
            title = stringResource(R.string.backup_section_remote),
            confirmLabel = stringResource(R.string.backup_do_upload),
            onDismiss = { showRemoteBackupDialog = false },
            onConfirm = { password ->
                showRemoteBackupDialog = false
                viewModel.backupRemote(password)
            },
        )
    }

    val pending = state.pendingImport
    if (pending != null) {
        if (pending.info.kind == BackupFileKind.ENCRYPTED) {
            PasswordPromptDialog(
                title = stringResource(R.string.backup_import_password_title),
                body = stringResource(R.string.backup_import_password_body),
                confirmLabel = stringResource(R.string.backup_import_confirm_action),
                onDismiss = viewModel::cancelImport,
                onConfirm = { password -> viewModel.confirmImport(password) },
            )
        } else {
            AlertDialog(
                onDismissRequest = viewModel::cancelImport,
                title = { Text(stringResource(R.string.backup_import_confirm_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        pending.info.displayName?.let {
                            Text(it, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Text(stringResource(R.string.backup_import_confirm_body))
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.confirmImport(null) }) {
                        Text(stringResource(R.string.backup_import_confirm_action))
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::cancelImport) {
                        Text(stringResource(R.string.backup_cancel))
                    }
                },
            )
        }
    }

    state.importReport?.let { outcome ->
        ImportReportDialog(outcome = outcome, onClose = viewModel::dismissImportReport)
    }
}

@Composable
private fun LocalBackupSection(
    state: BackupSettingsUiState,
    onChooseDirectory: () -> Unit,
    onBackupNow: () -> Unit,
) {
    BackupSection(title = stringResource(R.string.backup_section_local)) {
        when {
            !state.localConfigured -> Text(
                text = stringResource(R.string.backup_local_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> {
                Text(
                    text = state.localDirectoryLabel ?: stringResource(R.string.backup_local_none),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!state.localAuthorized) {
                    Text(
                        text = stringResource(R.string.backup_local_permission_lost),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        BackupStatusBlock(
            lastSuccessAt = state.localLastSuccessAt,
            lastFailure = state.localLastFailure,
            workState = if (state.localConfigured) state.localWorkState else null,
            neverText = stringResource(R.string.backup_never),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onChooseDirectory,
                enabled = !state.busy,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = stringResource(
                        if (state.localConfigured && !state.localAuthorized) {
                            R.string.backup_reauthorize
                        } else if (state.localConfigured) {
                            R.string.backup_change_directory
                        } else {
                            R.string.backup_choose_directory
                        },
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.localConfigured) {
                Button(
                    onClick = onBackupNow,
                    enabled = !state.busy && state.canBackupLocal,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.backup_backup_now), maxLines = 1)
                }
            }
        }

        Text(
            text = stringResource(R.string.backup_local_hint),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WebDavSection(
    state: BackupSettingsUiState,
    onBaseUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onRemoteDirectoryChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
    onUploadNow: () -> Unit,
) {
    BackupSection(title = stringResource(R.string.backup_section_remote)) {
        if (!state.remoteConfigured) {
            Text(
                text = stringResource(R.string.backup_remote_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedTextField(
            value = state.webDavBaseUrlInput,
            onValueChange = onBaseUrlChange,
            label = { Text(stringResource(R.string.backup_webdav_url)) },
            placeholder = { Text(stringResource(R.string.backup_webdav_url_hint)) },
            singleLine = true,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.webDavUsernameInput,
            onValueChange = onUsernameChange,
            label = { Text(stringResource(R.string.backup_webdav_username)) },
            singleLine = true,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.webDavPasswordInput,
            onValueChange = onPasswordChange,
            label = { Text(stringResource(R.string.backup_webdav_password)) },
            placeholder = {
                Text(
                    stringResource(
                        if (state.webDavPasswordSaved) {
                            R.string.backup_webdav_password_saved
                        } else {
                            R.string.backup_webdav_password
                        },
                    ),
                )
            },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.webDavRemoteDirectoryInput,
            onValueChange = onRemoteDirectoryChange,
            label = { Text(stringResource(R.string.backup_webdav_directory)) },
            placeholder = { Text(stringResource(R.string.backup_webdav_directory_hint)) },
            singleLine = true,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onSave,
                enabled = !state.busy,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.backup_save), maxLines = 1)
            }
            OutlinedButton(
                onClick = onTest,
                enabled = !state.busy,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.backup_test_connection), maxLines = 1)
            }
        }
        Button(
            onClick = onUploadNow,
            enabled = !state.busy && state.remoteConfigured,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.backup_upload_now))
        }

        BackupStatusBlock(
            lastSuccessAt = state.remoteLastSuccessAt,
            lastFailure = state.remoteLastFailure,
            workState = if (state.remoteConfigured) state.remoteWorkState else null,
            neverText = stringResource(R.string.backup_remote_never),
        )

        Text(
            text = stringResource(R.string.backup_webdav_hint),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ImportSection(
    state: BackupSettingsUiState,
    onPickFile: () -> Unit,
) {
    BackupSection(title = stringResource(R.string.backup_section_import)) {
        Button(
            onClick = onPickFile,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.backup_import_pick))
        }
        Text(
            text = stringResource(R.string.backup_import_hint),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BackupSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            content()
        }
    }
}

@Composable
private fun BackupStatusBlock(
    lastSuccessAt: Long?,
    lastFailure: BackupFailure?,
    workState: WorkInfo.State?,
    neverText: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = if (lastSuccessAt != null) {
                stringResource(R.string.backup_last_success, formatBackupTimestamp(lastSuccessAt))
            } else {
                neverText
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        lastFailure?.let { failure ->
            Text(
                text = stringResource(R.string.backup_last_failure, formatBackupTimestamp(failure.at)),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = backupErrorText(failure.kind, failure.detail),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        workState?.let {
            Text(
                text = stringResource(R.string.backup_current_state, workStateText(it)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EncryptedBackupDialog(
    title: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (CharArray?) -> Unit,
) {
    var usePassword by rememberSaveable { mutableStateOf(true) }
    // Passwords are intentionally not rememberSaveable: saved instance state can be written
    // to disk by the system while the app is in the background.
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }

    val passwordTooShort = usePassword && password.isNotEmpty() && password.length < MIN_PASSWORD_LENGTH
    val mismatch = usePassword && confirmation.isNotEmpty() && password != confirmation
    val canConfirm = !usePassword ||
        (password.length >= MIN_PASSWORD_LENGTH && password == confirmation)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.backup_encryption_switch),
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = usePassword,
                        onCheckedChange = { usePassword = it },
                    )
                }
                if (usePassword) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.backup_encryption_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it },
                        label = { Text(stringResource(R.string.backup_encryption_confirm)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (passwordTooShort) {
                        Text(
                            text = stringResource(R.string.backup_encryption_min),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    } else if (mismatch) {
                        Text(
                            text = stringResource(R.string.backup_encryption_mismatch),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    Text(
                        text = stringResource(R.string.backup_encryption_no_store),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.backup_encryption_warning),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm,
                onClick = { onConfirm(if (usePassword) password.toCharArray() else null) },
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.backup_cancel))
            }
        },
    )
}

@Composable
private fun PasswordPromptDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit,
) {
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(body)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.backup_encryption_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = password.isNotEmpty(),
                onClick = { onConfirm(password.toCharArray()) },
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.backup_cancel))
            }
        },
    )
}

@Composable
private fun ImportReportDialog(outcome: ImportOutcome, onClose: () -> Unit) {
    val report = outcome.report
    val countsLine = stringResource(
        R.string.backup_import_report_counts,
        report.insertedNotes,
        report.updatedNotes,
        report.keptLocalNotes,
        report.unchangedNotes,
    )
    val tombstonesLine = if (report.blockedNotes > 0 || report.deletedLocalNotes > 0) {
        stringResource(
            R.string.backup_import_report_tombstones,
            report.blockedNotes,
            report.deletedLocalNotes,
        )
    } else {
        null
    }
    val tagsLine = if (report.addedTags > 0 || report.remappedTags > 0 || report.addedNoteTags > 0) {
        stringResource(
            R.string.backup_import_report_tags,
            report.addedTags,
            report.remappedTags,
            report.addedNoteTags,
        )
    } else {
        null
    }
    val conflictsLine = if (report.conflicts.isNotEmpty()) {
        stringResource(R.string.backup_import_report_conflicts, report.conflicts.size)
    } else {
        null
    }
    val safetyLine = outcome.safetyBackupFileName?.let {
        stringResource(R.string.backup_import_report_safety, it)
    }
    val lines = listOfNotNull(countsLine, tombstonesLine, tagsLine, conflictsLine, safetyLine)

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.backup_import_report_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.backup_import_report_close))
            }
        },
    )
}

@Composable
private fun noticeText(notice: BackupNotice): String = when (notice) {
    is BackupNotice.Info -> notice.text
    is BackupNotice.Failure -> backupErrorText(notice.kind, notice.detail)
}

@Composable
private fun backupErrorText(kind: BackupErrorKind, detail: String?): String {
    val base = stringResource(
        when (kind) {
            BackupErrorKind.NOT_CONFIGURED -> R.string.backup_error_not_configured
            BackupErrorKind.PERMISSION_LOST -> R.string.backup_error_permission_lost
            BackupErrorKind.WRONG_PASSWORD_OR_CORRUPT -> R.string.backup_error_wrong_password
            BackupErrorKind.CORRUPT_FILE -> R.string.backup_error_corrupt
            BackupErrorKind.UNSUPPORTED_VERSION -> R.string.backup_error_unsupported_version
            BackupErrorKind.INVALID_FORMAT -> R.string.backup_error_invalid_format
            BackupErrorKind.INVALID_CONFIG -> R.string.backup_error_invalid_config
            BackupErrorKind.NETWORK -> R.string.backup_error_network
            BackupErrorKind.UNAUTHORIZED -> R.string.backup_error_unauthorized
            BackupErrorKind.SERVER -> R.string.backup_error_server
            BackupErrorKind.STORAGE -> R.string.backup_error_storage
            BackupErrorKind.SAFETY_BACKUP_FAILED -> R.string.backup_error_safety_backup
            BackupErrorKind.UNKNOWN -> R.string.backup_error_unknown
        },
    )
    return if (detail.isNullOrBlank() || detail == base) base else "$base\n$detail"
}

@Composable
private fun workStateText(state: WorkInfo.State): String = stringResource(
    when (state) {
        WorkInfo.State.ENQUEUED -> R.string.backup_state_enqueued
        WorkInfo.State.RUNNING -> R.string.backup_state_running
        WorkInfo.State.BLOCKED -> R.string.backup_state_blocked
        WorkInfo.State.SUCCEEDED -> R.string.backup_state_succeeded
        WorkInfo.State.FAILED -> R.string.backup_state_failed
        WorkInfo.State.CANCELLED -> R.string.backup_state_cancelled
        else -> R.string.backup_state_unknown
    },
)

private val backupTimestampFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun formatBackupTimestamp(epochMillis: Long): String =
    backupTimestampFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

private const val MIN_PASSWORD_LENGTH = 8
