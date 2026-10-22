package com.example.privatenotes.feature.backup

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
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
import com.example.privatenotes.data.backup.MergeConflictKind
import com.example.privatenotes.feature.lock.AppLockSection
import com.example.privatenotes.security.AppLock
import com.example.privatenotes.security.AppLockAuthenticator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Everything the settings page can ask the app to do.
 *
 * Passed as one value, so the screen can be previewed with the defaults and rendered in a test
 * without wiring fifteen lambdas by hand.
 */
internal class SettingsActions(
    val onOpenTags: () -> Unit = {},
    val onChooseDirectory: () -> Unit = {},
    val onConfirmLocalBackup: (CharArray?) -> Unit = {},
    val onConfirmRemoteBackup: (CharArray?) -> Unit = {},
    val onBaseUrlChange: (String) -> Unit = {},
    val onUsernameChange: (String) -> Unit = {},
    val onRemoteDirectoryChange: (String) -> Unit = {},
    val onPasswordChange: (String) -> Unit = {},
    val onSaveWebDav: () -> Unit = {},
    val onTestConnection: () -> Unit = {},
    val onPickImportFile: () -> Unit = {},
    val onCancelImport: () -> Unit = {},
    val onConfirmImport: (CharArray?) -> Unit = {},
    val onDismissImportReport: () -> Unit = {},
    val onAutoSyncChange: (Boolean) -> Unit = {},
    val onLocalRetentionChange: (Int) -> Unit = {},
    val onListRemote: () -> Unit = {},
    val onDismissRemote: () -> Unit = {},
    val onDownloadRemote: (String, CharArray?) -> Unit = { _, _ -> },
    val onBack: () -> Unit = {},
)

/**
 * Settings (§4.3, §9.6).
 *
 * Six sections in a fixed order - app lock, local backup, WebDAV, import, backup status, about.
 * Sections are separated by whitespace and a 1dp rule rather than stacked cards: a card per
 * setting turns a quiet page into a pile of boxes.
 *
 * Backup status lives in its own section instead of being repeated under each destination,
 * because "backup failed" is useless without knowing which destination failed and why - and
 * because the two are read together, not separately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsScreen(
    viewModel: BackupSettingsViewModel,
    appLock: AppLock,
    authenticator: AppLockAuthenticator,
    onOpenTags: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        appLock.endSystemUi()
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
        appLock.endSystemUi()
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

    val actions = SettingsActions(
        onChooseDirectory = {
            // A picker holds the screen for as long as the folder hunt takes; without this
            // bracket a slow one trips the 30-second grace window and locks the user out on
            // return (§6.2: system flows do not count as leaving).
            appLock.beginSystemUi()
            directoryPicker.launch(null)
        },
        onConfirmLocalBackup = viewModel::backupLocal,
        onConfirmRemoteBackup = viewModel::backupRemote,
        onBaseUrlChange = viewModel::onBaseUrlChange,
        onUsernameChange = viewModel::onUsernameChange,
        onRemoteDirectoryChange = viewModel::onRemoteDirectoryChange,
        onPasswordChange = viewModel::onPasswordChange,
        onSaveWebDav = viewModel::saveWebDav,
        onTestConnection = viewModel::testConnection,
        onPickImportFile = {
            appLock.beginSystemUi()
            importPicker.launch(arrayOf("*/*"))
        },
        onCancelImport = viewModel::cancelImport,
        onConfirmImport = { password -> viewModel.confirmImport(password) },
        onDismissImportReport = viewModel::dismissImportReport,
        onOpenTags = onOpenTags,
        onAutoSyncChange = viewModel::setAutoSyncEnabled,
        onLocalRetentionChange = viewModel::setLocalRetention,
        onListRemote = viewModel::listRemoteBackups,
        onDismissRemote = viewModel::dismissRemoteFiles,
        onDownloadRemote = viewModel::downloadAndImport,
        onBack = onBack,
    )

    SettingsContent(
        state = state,
        actions = actions,
        snackbarHostState = snackbarHostState,
        lockRow = { AppLockSection(lock = appLock, authenticator = authenticator) },
        onBack = onBack,
        modifier = modifier,
    )
}

/** Settings as a pure function of its state and the actions it is allowed to take. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsContent(
    state: BackupSettingsUiState,
    actions: SettingsActions,
    snackbarHostState: SnackbarHostState,
    lockRow: @Composable () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showLocalBackupDialog by rememberSaveable { mutableStateOf(false) }
    var showRemoteBackupDialog by rememberSaveable { mutableStateOf(false) }

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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
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
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        ) {
            if (state.busy) {
                item {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
            }

            item {
                SettingsSection(title = stringResource(R.string.settings_section_lock)) {
                    lockRow()
                }
            }

            item {
                LocalBackupSection(
                    state = state,
                    onChooseDirectory = actions.onChooseDirectory,
                    onBackupNow = { showLocalBackupDialog = true },
                    onRetentionChange = actions.onLocalRetentionChange,
                )
            }

            item {
                WebDavSection(
                    state = state,
                    onBaseUrlChange = actions.onBaseUrlChange,
                    onUsernameChange = actions.onUsernameChange,
                    onRemoteDirectoryChange = actions.onRemoteDirectoryChange,
                    onPasswordChange = actions.onPasswordChange,
                    onSave = actions.onSaveWebDav,
                    onTest = actions.onTestConnection,
                    onUploadNow = { showRemoteBackupDialog = true },
                    onAutoSyncChange = actions.onAutoSyncChange,
                    onListRemote = actions.onListRemote,
                )
            }

            item {
                ImportSection(
                    state = state,
                    onPickFile = actions.onPickImportFile,
                )
            }

            item {
                SettingsSection(title = stringResource(R.string.settings_section_tags)) {
                    TextButton(
                        onClick = actions.onOpenTags,
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.testTag(SettingsTags.TAGS),
                    ) {
                        Text(stringResource(R.string.settings_section_tags))
                    }
                    SectionHint(stringResource(R.string.settings_tags_hint))
                }
            }

            item {
                StatusSection(state = state)
            }

            item {
                AboutSection()
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
                actions.onConfirmLocalBackup(password)
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
                actions.onConfirmRemoteBackup(password)
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
                onDismiss = actions.onCancelImport,
                onConfirm = { password -> actions.onConfirmImport(password) },
            )
        } else {
            AlertDialog(
                onDismissRequest = actions.onCancelImport,
                title = { Text(stringResource(R.string.backup_import_confirm_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        pending.info.displayName?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(stringResource(R.string.backup_import_confirm_body))
                    }
                },
                confirmButton = {
                    TextButton(onClick = { actions.onConfirmImport(null) }) {
                        Text(stringResource(R.string.backup_import_confirm_action))
                    }
                },
                dismissButton = {
                    TextButton(onClick = actions.onCancelImport) {
                        Text(stringResource(R.string.backup_cancel))
                    }
                },
            )
        }
    }

    state.importReport?.let { outcome ->
        ImportReportDialog(outcome = outcome, onClose = actions.onDismissImportReport)
    }

    state.remoteFiles?.let { files ->
        if (files.isEmpty()) {
            AlertDialog(
                onDismissRequest = actions.onDismissRemote,
                title = { Text(stringResource(R.string.backup_restore_title)) },
                text = { Text(stringResource(R.string.backup_restore_empty)) },
                confirmButton = {
                    TextButton(onClick = actions.onDismissRemote) {
                        Text(stringResource(R.string.dialog_confirm))
                    }
                },
            )
        } else {
            RemoteFilesDialog(
                files = files,
                onDismiss = actions.onDismissRemote,
                onPick = { row, password -> actions.onDownloadRemote(row.fileName, password) },
            )
        }
    }
}

/**
 * A settings section: a serif title, a hairline, then its content.
 *
 * The rule is the only separator. There is no card, no elevation and no background fill, so the
 * page keeps one plane and the eye follows the type.
 */
@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        HorizontalDivider(
            modifier = Modifier.padding(top = 8.dp),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Column(
            modifier = Modifier.padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun RetentionChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                Color.Transparent
            },
        ),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/** A short line of explanation under a control; never a paragraph. */
@Composable
private fun SectionHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LocalBackupSection(
    state: BackupSettingsUiState,
    onChooseDirectory: () -> Unit,
    onBackupNow: () -> Unit,
    onRetentionChange: (Int) -> Unit,
) {
    SettingsSection(title = stringResource(R.string.backup_section_local)) {
        when {
            !state.localConfigured -> SectionHint(stringResource(R.string.backup_local_none))

            else -> {
                Text(
                    text = state.localDirectoryLabel ?: stringResource(R.string.backup_local_none),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!state.localAuthorized) {
                    Text(
                        text = stringResource(R.string.backup_local_permission_lost),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

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
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.localConfigured) {
                Button(
                    onClick = onBackupNow,
                    enabled = !state.busy && state.canBackupLocal,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.backup_backup_now), maxLines = 2)
                }
            }
        }

        SectionHint(stringResource(R.string.backup_local_hint))

        Text(
            text = stringResource(R.string.backup_retention_title),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RetentionChoice(
                label = stringResource(R.string.backup_retention_30),
                selected = state.localRetention == 30,
                onClick = { onRetentionChange(30) },
            )
            RetentionChoice(
                label = stringResource(R.string.backup_retention_90),
                selected = state.localRetention == 90,
                onClick = { onRetentionChange(90) },
            )
            RetentionChoice(
                label = stringResource(R.string.backup_retention_all),
                selected = state.localRetention == 0,
                onClick = { onRetentionChange(0) },
            )
        }
        SectionHint(stringResource(R.string.backup_retention_hint))
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
    onAutoSyncChange: (Boolean) -> Unit,
    onListRemote: () -> Unit,
) {
    SettingsSection(title = stringResource(R.string.backup_section_remote)) {
        if (!state.remoteConfigured) {
            SectionHint(stringResource(R.string.backup_remote_none))
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
                Text(stringResource(R.string.backup_save), maxLines = 2)
            }
            OutlinedButton(
                onClick = onTest,
                enabled = !state.busy,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.backup_test_connection), maxLines = 2)
            }
        }
        Button(
            onClick = onUploadNow,
            enabled = !state.busy && state.remoteConfigured,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.backup_upload_now))
        }

        SectionHint(stringResource(R.string.backup_webdav_hint))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.backup_auto_sync_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = state.autoSyncEnabled,
                onCheckedChange = onAutoSyncChange,
                enabled = !state.busy && state.remoteConfigured,
            )
        }
        SectionHint(stringResource(R.string.backup_auto_sync_hint))

        OutlinedButton(
            onClick = onListRemote,
            enabled = !state.busy && state.remoteConfigured,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.backup_restore_pick))
        }
        SectionHint(stringResource(R.string.backup_restore_hint))
    }
}

@Composable
private fun ImportSection(
    state: BackupSettingsUiState,
    onPickFile: () -> Unit,
) {
    SettingsSection(title = stringResource(R.string.backup_section_import)) {
        OutlinedButton(
            onClick = onPickFile,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onBackground,
            ),
        ) {
            Text(stringResource(R.string.backup_import_pick))
        }
        SectionHint(stringResource(R.string.backup_import_hint))
    }
}

/** Both destinations, side by side in reading order: success first, then the last failure. */
@Composable
private fun StatusSection(state: BackupSettingsUiState) {
    SettingsSection(title = stringResource(R.string.settings_section_status)) {
        StatusLine(
            label = stringResource(R.string.backup_section_local),
            lastSuccessAt = state.localLastSuccessAt,
            lastFailure = state.localLastFailure,
            workState = if (state.localConfigured) state.localWorkState else null,
            neverText = stringResource(R.string.backup_never),
        )
        StatusLine(
            label = stringResource(R.string.backup_section_remote),
            lastSuccessAt = state.remoteLastSuccessAt,
            lastFailure = state.remoteLastFailure,
            workState = if (state.remoteConfigured) state.remoteWorkState else null,
            neverText = stringResource(R.string.backup_remote_never),
        )
    }
}

@Composable
private fun StatusLine(
    label: String,
    lastSuccessAt: Long?,
    lastFailure: BackupFailure?,
    workState: WorkInfo.State?,
    neverText: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = if (lastSuccessAt != null) {
                stringResource(R.string.backup_last_success, formatBackupTimestamp(lastSuccessAt))
            } else {
                neverText
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        lastFailure?.let { failure ->
            Text(
                text = stringResource(R.string.backup_last_failure, formatBackupTimestamp(failure.at)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = backupErrorText(failure.kind, failure.detail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        workState?.let {
            Text(
                text = stringResource(R.string.backup_current_state, workStateText(it)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Version, the font the app reads in, and what protects the notes.
 *
 * The licence text is read from assets when it is opened rather than bundled as a string: the
 * OFL is 90 lines and has no business being a translatable resource.
 */
@Composable
private fun AboutSection() {
    val context = LocalContext.current
    var licenceOpen by remember { mutableStateOf(false) }
    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "—"
    }
    val licence = remember(context) {
        runCatching {
            context.assets.open(LICENCE_ASSET).bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }

    SettingsSection(title = stringResource(R.string.settings_section_about)) {
        AboutRow(
            label = stringResource(R.string.settings_about_version),
            value = versionName,
        )
        AboutRow(
            label = stringResource(R.string.settings_about_font),
            value = stringResource(R.string.settings_about_font_value),
        )
        TextButton(
            onClick = { licenceOpen = true },
            contentPadding = PaddingValues(0.dp),
        ) {
            Text(stringResource(R.string.settings_about_font_licence))
        }
        SectionHint(stringResource(R.string.settings_about_encryption))
    }

    if (licenceOpen) {
        AlertDialog(
            onDismissRequest = { licenceOpen = false },
            title = { Text(stringResource(R.string.settings_about_font_licence_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = licence.ifEmpty { stringResource(R.string.settings_about_font_licence_missing) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { licenceOpen = false }) {
                    Text(stringResource(R.string.dialog_confirm))
                }
            },
        )
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The remote backups, newest first. An encrypted one asks for its password before downloading,
 * because the merge cannot start without it.
 */
@Composable
private fun RemoteFilesDialog(
    files: List<RemoteFileRow>,
    onDismiss: () -> Unit,
    onPick: (RemoteFileRow, CharArray?) -> Unit,
) {
    var passwordFor by remember { mutableStateOf<RemoteFileRow?>(null) }
    val formatter = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_restore_title)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                files.forEach { row ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (row.encrypted) passwordFor = row else onPick(row, null)
                            }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(
                            text = row.fileName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = buildString {
                                append(formatBytes(row.sizeBytes))
                                row.modifiedAt?.let { append(" · " + formatter.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))) }
                                if (row.encrypted) append(" · " + stringResource(R.string.backup_restore_encrypted))
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )

    passwordFor?.let { row ->
        PasswordPromptDialog(
            title = stringResource(R.string.backup_restore_password_title),
            body = stringResource(R.string.backup_restore_password_body),
            confirmLabel = stringResource(R.string.backup_restore_confirm),
            onDismiss = { passwordFor = null },
            onConfirm = { password ->
                passwordFor = null
                onPick(row, password)
            },
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024 -> "%.0f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
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
                        style = MaterialTheme.typography.bodyMedium,
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
                Text(body, style = MaterialTheme.typography.bodyMedium)
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
    val conflictLines = report.conflicts.map { conflict ->
        // One plain-language line per conflict instead of a bare count: the reader learns what
        // happened to each item, not just how many there were.
        stringResource(
            when (conflict.kind) {
                MergeConflictKind.NOTE_TIMESTAMP_TIE -> R.string.backup_import_report_conflict_note_tie
                MergeConflictKind.TAG_NAME_COLLISION -> R.string.backup_import_report_conflict_tag
                MergeConflictKind.NOTE_EDITED_AFTER_TOMBSTONE -> R.string.backup_import_report_conflict_tombstone
            }
        )
    }
    val safetyLine = outcome.safetyBackupFileName?.let {
        stringResource(R.string.backup_import_report_safety, it)
    }
    val lines = listOfNotNull(countsLine, tombstonesLine, tagsLine) + conflictLines + listOfNotNull(safetyLine)

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

private const val LICENCE_ASSET = "licenses/lxgw-wenkai-OFL.txt"

/** Stable handles for instrumented UI tests. */
object SettingsTags {
    const val TAGS = "settings_tags"
}
