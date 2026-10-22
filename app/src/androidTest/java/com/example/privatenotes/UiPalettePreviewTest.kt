package com.example.privatenotes

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import com.example.privatenotes.feature.notes.NoteListTags
import androidx.test.platform.app.InstrumentationRegistry
import com.example.privatenotes.data.local.NoteEntity
import com.example.privatenotes.data.local.NoteWithTags
import com.example.privatenotes.feature.backup.BackupSettingsUiState
import com.example.privatenotes.feature.backup.SettingsActions
import com.example.privatenotes.feature.backup.SettingsContent
import com.example.privatenotes.feature.trash.TrashContent
import androidx.compose.material3.Text
import com.example.privatenotes.feature.notes.EditorContent
import com.example.privatenotes.feature.notes.NoteEditorUiState
import com.example.privatenotes.feature.notes.NoteListContent
import com.example.privatenotes.feature.notes.NoteListUiState
import com.example.privatenotes.feature.notes.SaveStatus
import com.example.privatenotes.ui.theme.PrivateNotesTheme
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Real Compose renders of production components, using synthetic text only. The separate test
 * host has no app data or authentication. MainActivity's FLAG_SECURE remains unchanged.
 */
class UiPalettePreviewTest {
    @get:Rule val compose = createComposeRule()

    @Test fun listLight() = renderList(false)
    @Test fun listDark() = renderList(true)
    @Test fun editorLight() = renderEditor(false)
    @Test fun editorDark() = renderEditor(true)
    @Test fun settingsLight() = renderSettings(false)
    @Test fun settingsDark() = renderSettings(true)
    @Test fun trashLight() = renderTrash(false)
    @Test fun trashDark() = renderTrash(true)

    @Test fun listLargeNarrow() = renderList(false, largeNarrow = true)
    @Test fun editorLargeNarrow() = renderEditor(false, largeNarrow = true)

    @Composable private fun PreviewHost(dark: Boolean, largeNarrow: Boolean, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeNarrow) 2f else density.fontScale)) {
            PrivateNotesTheme(darkTheme = dark) {
                Box(if (largeNarrow) Modifier.width(320.dp) else Modifier) { content() }
            }
        }
    }

    private fun renderList(dark: Boolean, largeNarrow: Boolean = false) {
        val notes = listOf(
            "慢慢记录，认真生活" to "留一点时间给自己，也给那些值得记住的小事。",
            "周末的散步计划" to "沿着河边走走，带一本书，找一家安静的小店。",
            "一些新的想法" to "不急着整理，先把这一刻的灵感写下来。",
        ).mapIndexed { index, (title, content) ->
            NoteWithTags(NoteEntity("preview-$index", title, content, 1_792_684_800_000L, 1_792_684_800_000L), emptyList())
        }
        compose.setContent {
            PreviewHost(dark, largeNarrow) {
                NoteListContent(
                    state = NoteListUiState(notes = notes, isLoading = false),
                    snackbarHostState = SnackbarHostState(),
                    onCreateNote = {}, onOpenNote = {}, onOpenTrash = {}, onOpenSettings = {},
                    onQueryChange = {}, onTagSelected = {}, onClearFilters = {}, onDeleteNote = {},
                    onToggleSelected = {}, onStartSelection = {}, onSelectAll = {},
                    onClearSelection = {}, onDeleteSelected = {},
                )
            }
        }
        compose.onNodeWithTag(NoteListTags.CREATE).assertHeightIsAtLeast(48.dp)
        save("list", dark, largeNarrow)
    }

    private fun renderEditor(dark: Boolean, largeNarrow: Boolean = false) {
        compose.setContent {
            PreviewHost(dark, largeNarrow) {
                EditorContent(
                    state = NoteEditorUiState(
                        title = "慢慢记录，认真生活", isLoaded = true,
                        content = "今天终于给自己留了一点空白。\n\n窗边的光很温柔，茶还热着。那些细小的念头，也值得被认真记下来。\n\n不必写得完整，只要真实。",
                        saveStatus = SaveStatus.Saved(1_792_684_800_000L),
                    ),
                    onTitleChange = {}, onContentChange = {}, onTogglePreview = {}, onDelete = {},
                    onAddTag = {}, onRemoveTag = {}, onBack = {},
                )
            }
        }
        save("editor", dark, largeNarrow)
    }

    private fun renderSettings(dark: Boolean) {
        compose.setContent {
            PreviewHost(dark, false) {
                SettingsContent(
                    state = BackupSettingsUiState(), actions = SettingsActions(),
                    snackbarHostState = SnackbarHostState(),
                    // Preview has no credentials or real authentication state.
                    lockRow = { Text("使用设备凭据保护笔记") }, onBack = {},
                )
            }
        }
        save("settings", dark, false)
    }

    private fun renderTrash(dark: Boolean) {
        val now = System.currentTimeMillis()
        compose.setContent {
            PreviewHost(dark, false) {
                TrashContent(
                    notes = listOf(NoteEntity("preview-trash", "暂时放下的想法", "", now, now,
                        deletedAt = now)), isLoading = false, errorMessage = null,
                    onRestore = {}, onDeleteForever = {}, onBack = {},
                )
            }
        }
        save("trash", dark, false)
    }

    private fun save(screen: String, dark: Boolean, largeNarrow: Boolean) {
        compose.waitForIdle()
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "ui-previews")
        check(root.isDirectory || root.mkdirs())
        val variant = if (largeNarrow) "large-narrow" else if (dark) "dark" else "light"
        val file = File(root, "$screen-$variant.png")
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue(file.length() > 0)
    }
}
