package com.example.privatenotes.ui.preview

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.content.res.Configuration
import com.example.privatenotes.data.backup.BackupFailure
import com.example.privatenotes.data.backup.BackupErrorKind
import com.example.privatenotes.data.local.NoteEntity
import com.example.privatenotes.data.local.NoteWithTags
import com.example.privatenotes.data.local.TagEntity
import com.example.privatenotes.feature.backup.BackupSettingsUiState
import com.example.privatenotes.feature.backup.SettingsActions
import com.example.privatenotes.feature.backup.SettingsContent
import com.example.privatenotes.feature.notes.EditorTag
import com.example.privatenotes.feature.notes.NoteEditorUiState
import com.example.privatenotes.feature.notes.NoteListContent
import com.example.privatenotes.feature.notes.NoteListUiState
import com.example.privatenotes.feature.notes.EditorContent
import com.example.privatenotes.feature.notes.SaveStatus
import com.example.privatenotes.feature.trash.TrashContent
import com.example.privatenotes.ui.theme.PrivateNotesTheme

/**
 * Previews for every screen and every state that is hard to reach on a device.
 *
 * Screenshots of the running app are black by design (FLAG_SECURE), so this file is how the
 * layout gets looked at during development: light and dark, normal and 1.5x text, and the
 * branches that only appear when something is empty, filtered, long or broken.
 */
private const val DAY = 24L * 60 * 60 * 1000
private const val NOW = 1_713_000_000_000L

private val sampleTags = listOf(
    TagEntity(id = "t1", name = "工作", createdAt = NOW - 40 * DAY, updatedAt = NOW - 40 * DAY),
    TagEntity(id = "t2", name = "读书", createdAt = NOW - 30 * DAY, updatedAt = NOW - 30 * DAY),
    TagEntity(id = "t3", name = "灵感", createdAt = NOW - 20 * DAY, updatedAt = NOW - 20 * DAY),
)

private fun note(
    id: String,
    title: String,
    content: String,
    updatedAt: Long = NOW - 2 * 60 * 60 * 1000,
    tags: List<TagEntity> = emptyList(),
) = NoteWithTags(
    note = NoteEntity(
        id = id,
        title = title,
        content = content,
        createdAt = updatedAt - DAY,
        updatedAt = updatedAt,
    ),
    tags = tags,
)

private val sampleNotes = listOf(
    note(
        id = "n1",
        title = "周会记录",
        content = "**结论**：这周把备份的合并策略改成按更新时间取新，冲突时保留本地。\n\n" +
            "- 远端失败要能看见原因\n- 本地目录的权限要重新确认",
        tags = listOf(sampleTags[0]),
    ),
    note(
        id = "n2",
        title = "读《夜航西飞》",
        content = "她写飞行，其实写的是一个人如何独自面对空白。第二章值得再读一遍。",
        updatedAt = NOW - 3 * DAY,
        tags = listOf(sampleTags[1]),
    ),
    note(
        id = "n3",
        title = "没有标签的一小段",
        content = "把「随手记」做成一个真的会想打开的应用。",
        updatedAt = NOW - 400 * DAY,
    ),
)

private val longNote = buildString {
    appendLine("# 长文预览")
    appendLine()
    repeat(6) { index ->
        appendLine("第 ${index + 1} 段。正文用 17sp 的霞鹜文楷，行距 1.76 倍，段落之间留一个空行，")
        appendLine("这样一屏大约能放下二十行中文，读起来不像在填表。")
        appendLine()
    }
    appendLine("> 引用用左侧的一条细线，不加底色。")
    appendLine()
    appendLine("```kotlin")
    appendLine("val note = repository.observeNotes(query = \"\", tagId = null)")
    appendLine("```")
}

@Composable
private fun PreviewFrame(content: @Composable () -> Unit) {
    PrivateNotesTheme { content() }
}

// --- Note list ---------------------------------------------------------------------------

@Composable
private fun ListPreview(state: NoteListUiState) {
    PreviewFrame {
        NoteListContent(
            state = state,
            snackbarHostState = SnackbarHostState(),
            onCreateNote = {},
            onOpenNote = {},
            onOpenTrash = {},
            onOpenSettings = {},
            onQueryChange = {},
            onTagSelected = {},
            onClearFilters = {},
            onDeleteNote = {},
        )
    }
}

@Preview(name = "列表 · 正常", group = "list", widthDp = 360, heightDp = 720, showBackground = true)
@Composable
private fun NoteListNormal() {
    ListPreview(NoteListUiState(notes = sampleNotes, tags = sampleTags, isLoading = false))
}

@Preview(name = "列表 · 深色", group = "list", widthDp = 360, heightDp = 720, showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NoteListDark() {
    ListPreview(NoteListUiState(notes = sampleNotes, tags = sampleTags, isLoading = false))
}

@Preview(name = "列表 · 1.5x 字号", group = "list", widthDp = 360, heightDp = 720, showBackground = true,
    fontScale = 1.5f)
@Composable
private fun NoteListLargeFont() {
    ListPreview(NoteListUiState(notes = sampleNotes, tags = sampleTags, isLoading = false))
}

@Preview(name = "列表 · 空", group = "list", widthDp = 360, heightDp = 720, showBackground = true)
@Composable
private fun NoteListEmpty() {
    ListPreview(NoteListUiState(notes = emptyList(), tags = sampleTags, isLoading = false))
}

@Preview(name = "列表 · 搜索无结果", group = "list", widthDp = 360, heightDp = 720, showBackground = true)
@Composable
private fun NoteListNoMatches() {
    ListPreview(
        NoteListUiState(
            notes = emptyList(),
            tags = sampleTags,
            query = "并不存在的词",
            isLoading = false,
        ),
    )
}

@Preview(name = "列表 · 错误", group = "list", widthDp = 360, heightDp = 720, showBackground = true)
@Composable
private fun NoteListError() {
    ListPreview(
        NoteListUiState(
            isLoading = false,
            errorMessage = "数据库无法打开：Keystore 中的密钥已失效",
        ),
    )
}

@Preview(name = "列表 · 加载中", group = "list", widthDp = 360, heightDp = 720, showBackground = true)
@Composable
private fun NoteListLoading() {
    ListPreview(NoteListUiState())
}

// --- Editor ------------------------------------------------------------------------------

@Composable
private fun EditorPreview(state: NoteEditorUiState) {
    PreviewFrame {
        EditorContent(
            state = state,
            selection = androidx.compose.ui.text.TextRange.Zero,
            onSelectionChange = {},
            onTitleChange = {},
            onContentChange = {},
            onTogglePreview = {},
            onDelete = {},
            onAddTag = {},
            onRemoveTag = {},
            onBack = {},
        )
    }
}

private val editorState = NoteEditorUiState(
    noteId = "n1",
    title = "周会记录",
    content = "**结论**：这周把备份的合并策略改成按更新时间取新。\n\n- 远端失败要能看见原因\n- 本地目录的权限要重新确认",
    isLoaded = true,
    saveStatus = SaveStatus.Saved(NOW),
    persistedTags = listOf(sampleTags[0], sampleTags[2]),
)

@Preview(name = "编辑 · 正常", group = "editor", widthDp = 360, heightDp = 720, showBackground = true)
@Composable
private fun EditorNormal() {
    EditorPreview(editorState)
}

@Preview(name = "编辑 · 深色", group = "editor", widthDp = 360, heightDp = 720, showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun EditorDark() {
    EditorPreview(editorState)
}

@Preview(name = "编辑 · 1.5x 字号", group = "editor", widthDp = 360, heightDp = 720, showBackground = true,
    fontScale = 1.5f)
@Composable
private fun EditorLargeFont() {
    EditorPreview(editorState)
}

@Preview(name = "编辑 · 空笔记", group = "editor", widthDp = 360, heightDp = 720, showBackground = true)
@Composable
private fun EditorEmpty() {
    EditorPreview(NoteEditorUiState(isLoaded = true))
}

@Preview(name = "编辑 · 长文预览", group = "editor", widthDp = 360, heightDp = 720, showBackground = true)
@Composable
private fun EditorPreviewLong() {
    EditorPreview(
        editorState.copy(
            title = "长文预览",
            content = longNote,
            isPreview = true,
            pendingTags = listOf("长文", "排版", "阅读"),
        ),
    )
}

@Preview(name = "编辑 · 保存失败", group = "editor", widthDp = 360, heightDp = 720, showBackground = true)
@Composable
private fun EditorFailed() {
    EditorPreview(editorState.copy(saveStatus = SaveStatus.Failed("磁盘已满")))
}

// --- Trash -------------------------------------------------------------------------------

@Composable
private fun TrashPreview(notes: List<NoteEntity>) {
    PreviewFrame {
        TrashContent(
            notes = notes,
            isLoading = false,
            errorMessage = null,
            onRestore = {},
            onDeleteForever = {},
            onBack = {},
        )
    }
}

private val trashed = sampleNotes.mapIndexed { index, entry ->
    entry.note.copy(deletedAt = NOW - (index + 1) * 3 * DAY)
}

@Preview(name = "回收站 · 正常", group = "trash", widthDp = 360, heightDp = 720, showBackground = true)
@Composable
private fun TrashNormal() {
    TrashPreview(trashed)
}

@Preview(name = "回收站 · 深色", group = "trash", widthDp = 360, heightDp = 720, showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TrashDark() {
    TrashPreview(trashed)
}

@Preview(name = "回收站 · 空", group = "trash", widthDp = 360, heightDp = 720, showBackground = true)
@Composable
private fun TrashEmpty() {
    TrashPreview(emptyList())
}

@Preview(name = "回收站 · 1.5x 字号", group = "trash", widthDp = 360, heightDp = 720, showBackground = true,
    fontScale = 1.5f)
@Composable
private fun TrashLargeFont() {
    TrashPreview(trashed)
}

// --- Settings ----------------------------------------------------------------------------

private val settingsState = BackupSettingsUiState(
    localDirectoryLabel = "Documents/OpenNotes",
    localConfigured = true,
    localAuthorized = true,
    localLastSuccessAt = NOW - 5 * 60 * 60 * 1000,
    remoteConfigured = true,
    remoteLastSuccessAt = NOW - 26 * 60 * 60 * 1000,
    remoteLastFailure = BackupFailure(
        kind = BackupErrorKind.UNAUTHORIZED,
        at = NOW - 2 * 60 * 60 * 1000,
        detail = "401 Unauthorized",
    ),
    webDavBaseUrlInput = "https://dav.example.com/notes",
    webDavUsernameInput = "yg",
    webDavPasswordSaved = true,
)

@Composable
private fun SettingsPreview(state: BackupSettingsUiState) {
    PreviewFrame {
        SettingsContent(
            state = state,
            actions = SettingsActions(),
            snackbarHostState = SnackbarHostState(),
            lockRow = {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text(
                        text = "开启应用锁",
                        style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                    )
                    androidx.compose.material3.Text(
                        text = "离开应用超过 30 秒后，重新进入需要指纹或设备密码。",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            onBack = {},
        )
    }
}

@Preview(name = "设置 · 正常", group = "settings", widthDp = 360, heightDp = 900, showBackground = true)
@Composable
private fun SettingsNormal() {
    SettingsPreview(settingsState)
}

@Preview(name = "设置 · 深色", group = "settings", widthDp = 360, heightDp = 900, showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsDark() {
    SettingsPreview(settingsState)
}

@Preview(name = "设置 · 1.5x 字号", group = "settings", widthDp = 360, heightDp = 900, showBackground = true,
    fontScale = 1.5f)
@Composable
private fun SettingsLargeFont() {
    SettingsPreview(settingsState)
}

@Preview(name = "设置 · 全部未配置", group = "settings", widthDp = 360, heightDp = 900, showBackground = true)
@Composable
private fun SettingsUnconfigured() {
    SettingsPreview(BackupSettingsUiState())
}

@Preview(name = "设置 · 备份进行中", group = "settings", widthDp = 360, heightDp = 900, showBackground = true)
@Composable
private fun SettingsBusy() {
    SettingsPreview(settingsState.copy(operation = com.example.privatenotes.feature.backup.BackupOperation.LOCAL_BACKUP))
}

// --- Type specimen -----------------------------------------------------------------------

@Preview(name = "字号样例 · 浅色", group = "type", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun TypeSpecimenLight() {
    TypeSpecimen()
}

@Preview(name = "字号样例 · 深色", group = "type", widthDp = 360, heightDp = 640, showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TypeSpecimenDark() {
    TypeSpecimen()
}

@Composable
private fun TypeSpecimen() {
    PreviewFrame {
        androidx.compose.foundation.layout.Column(
            modifier = androidx.compose.ui.Modifier.padding(20.dp),
        ) {
            val typography = androidx.compose.material3.MaterialTheme.typography
            androidx.compose.material3.Text("标题 22sp 衬线", style = typography.headlineSmall)
            androidx.compose.material3.Text("小节 17sp 衬线", style = typography.titleMedium)
            androidx.compose.material3.Text(
                "正文 17sp / 行距 30sp。中文正文需要这样松的行距，读起来才不挤。" +
                    "霞鹜文楷的撇捺在 17sp 上还认得出来，再小一号就开始糊。",
                style = typography.bodyLarge,
            )
            androidx.compose.material3.Text("次要文字 15sp", style = typography.bodyMedium)
            androidx.compose.material3.Text("时间戳 12sp 无衬线", style = typography.labelSmall)
        }
    }
}
