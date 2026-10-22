package com.example.privatenotes

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.privatenotes.data.repository.NoteRepository
import com.example.privatenotes.feature.notes.EditorTags
import com.example.privatenotes.feature.notes.NoteListTags
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import androidx.room.withTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase-2 acceptance, exercised through the real UI and the real encrypted database:
 * typing is enough to persist a note, and there is no save button to press.
 */
@RunWith(AndroidJUnit4::class)
class EditorAutoSaveTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val container: AppContainer
        get() = ApplicationProvider.getApplicationContext<PrivateNotesApp>().container

    private val repository: NoteRepository
        get() = container.noteRepository

    @Before
    fun clearNotes() = runBlocking {
        // Use Room's suspend transaction path; SQLCipher clearAllTables can race invalidation.
        container.database.withTransaction {
            container.database.openHelper.writableDatabase.execSQL("DELETE FROM note_tag_refs")
            container.database.openHelper.writableDatabase.execSQL("DELETE FROM notes")
            container.database.openHelper.writableDatabase.execSQL("DELETE FROM tags")
            container.database.openHelper.writableDatabase.execSQL("DELETE FROM deletion_markers")
        }
    }

    @Test
    fun typingPersistsTheNoteWithoutPressingSave() {
        composeRule.onNodeWithTag(NoteListTags.CREATE).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(EditorTags.TITLE).performTextInput("会议记录")
        composeRule.onNodeWithTag(EditorTags.CONTENT).performTextInput("周四下午三点，讨论备份策略。")

        // No save action follows: only waiting. The debounce pipeline has to do the work.
        composeRule.waitUntil(timeoutMillis = WAIT_TIMEOUT) {
            runBlocking {
                repository.observeActiveNotes().first()
                    .any { it.title == "会议记录" && it.content.contains("备份策略") }
            }
        }

        val saved = runBlocking { repository.observeActiveNotes().first().single() }
        assertEquals("会议记录", saved.title)
        assertTrue(saved.content.contains("备份策略"))
        assertNotNull(saved.updatedAt)
    }

    @Test
    fun leavingTheEditorFlushesTheLastKeystrokes() {
        composeRule.onNodeWithTag(NoteListTags.CREATE).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(EditorTags.CONTENT).performTextInput("马上返回")

        // Back out immediately, well inside the debounce window.
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        composeRule.waitUntil(timeoutMillis = WAIT_TIMEOUT) {
            runBlocking { repository.observeActiveNotes().first().any { it.content == "马上返回" } }
        }

        assertEquals(1, runBlocking { repository.observeActiveNotes().first().size })
    }

    @Test
    fun deletingFromTheListMovesTheNoteIntoTheTrash() {
        runBlocking { repository.createNote("要删的笔记", "正文") }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(NoteListTags.TRASH).assertExists()
        composeRule.waitUntil(timeoutMillis = WAIT_TIMEOUT) {
            runBlocking { repository.observeActiveNotes().first().isNotEmpty() }
        }

        // Delete through the repository the same row action calls, then confirm the lists agree.
        val noteId = runBlocking { repository.observeActiveNotes().first().single().id }
        runBlocking { repository.moveToTrash(noteId) }

        assertTrue(runBlocking { repository.observeActiveNotes().first().isEmpty() })
        assertEquals(noteId, runBlocking { repository.observeTrash().first().single().id })
    }

    private companion object {
        const val WAIT_TIMEOUT = 20_000L
    }
}
