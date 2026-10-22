package com.example.privatenotes

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.privatenotes.data.backup.AppVersionProvider
import com.example.privatenotes.data.backup.BackupArchiveService
import com.example.privatenotes.data.backup.BackupJson
import com.example.privatenotes.data.backup.BackupSnapshotReader
import com.example.privatenotes.feature.notes.EditorTags
import com.example.privatenotes.feature.notes.NoteListTags
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Seeds the data `scripts/canary-scan.sh` looks for, through the real editor.
 *
 * The point is not the note: it is that known strings enter through the UI, land in the encrypted
 * database and in the backup password storage, and must not be findable in the app's private
 * directory afterwards. Typing through the editor rather than inserting rows means the test also
 * covers the paths that write to the WAL and to SharedPreferences.
 */
@ScriptDriven
@RunWith(AndroidJUnit4::class)
class CanarySeedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val app: PrivateNotesApp
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun seedCanariesAndExport() {
        composeRule.waitForIdle()

        // 1. A note carrying the three canary strings, typed through the editor.
        composeRule.onNodeWithTag(NoteListTags.CREATE).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(EditorTags.TITLE).performTextInput(CANARY_TITLE)
        composeRule.onNodeWithTag(EditorTags.CONTENT).performTextInput(CANARY_BODY)

        // By text, not by tag: EditorTags.ADD_TAG is declared but no longer applied to the pill
        // that replaced the AssistChip (recorded in docs/test-report.md as a defect).
        composeRule.onNode(hasText("添加标签")).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(EditorTags.TAG_INPUT).performTextInput(CANARY_TAG)
        composeRule.onNode(hasText("确定")).performClick()
        composeRule.waitForIdle()

        // Leaving the editor flushes the debounce, so the row is on disk before the pull.
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()

        val note = runBlocking {
            app.container.noteRepository.observeActiveNotes().first()
                .firstOrNull { it.title == CANARY_TITLE }
        }
        assertEquals("the canary note must exist", CANARY_TITLE, note?.title)
        assertEquals(CANARY_BODY, note?.content)
        val tags = runBlocking { app.container.tagRepository.observeAll().first() }
        assertEquals(true, tags.any { it.name == CANARY_TAG })

        // 2. The WebDAV password, stored through the same path the settings screen uses.
        app.container.backupStatusStore.saveWebDav(
            baseUrl = "https://dav.invalid/notes",
            username = "canary",
            remoteDirectory = "open-notes",
            password = CANARY_DAV_PASSWORD.toCharArray(),
        )

        // 3. One encrypted export and one plaintext export, side by side, so the scan can show the
        //    difference instead of asserting one and hoping.
        val archive = BackupArchiveService(
            snapshotReader = BackupSnapshotReader({ app.container.database }, app.container.timeSource),
            json = BackupJson(),
            appVersionProvider = AppVersionProvider(app),
        )
        val encrypted = runBlocking { archive.create(CANARY_DAV_PASSWORD.toCharArray()) }
        File(app.filesDir, "canary-encrypted.pnb").writeBytes(encrypted.bytes)
        // The plaintext export goes to external storage, the place a user-chosen backup directory
        // would be. Writing it into the private directory would make the scan fail on data the
        // test itself put there, which proves nothing.
        val plaintext = runBlocking { archive.create(null) }
        val external = app.getExternalFilesDir(null) ?: app.filesDir
        File(external, "canary-plaintext.json").writeBytes(plaintext.bytes)
    }

    private companion object {
        const val CANARY_TITLE = "CANARY-冥王星-7f3a"
        const val CANARY_BODY = "CANARY-BODY-冥王星-9c21"
        const val CANARY_TAG = "CANARY-TAG"
        const val CANARY_DAV_PASSWORD = "CANARY-DAVPW"
    }
}
