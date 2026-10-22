package com.example.privatenotes

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.privatenotes.feature.notes.EditorTags
import com.example.privatenotes.feature.notes.NoteListTags
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Back out of the editor with predictive back enabled.
 *
 * `android:enableOnBackInvokedCallback="true"` changes which callback receives the gesture, so the
 * thing worth pinning is that the flush-on-dispose path still runs: nothing typed in the last
 * second may be lost, and backing out of an empty note must not leave a row behind.
 */
@RunWith(AndroidJUnit4::class)
class PredictiveBackEditorTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val app: PrivateNotesApp
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun backingOutImmediatelyKeepsTheLastKeystrokes() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(NoteListTags.CREATE).performClick()
        composeRule.waitForIdle()
        // Unique per run: the emulator keeps notes from earlier runs, so a fixed string would be
        // counted four times by the fifth run and look like a duplication bug.
        val marker = "预测性返回-" + System.nanoTime()
        composeRule.onNodeWithTag(EditorTags.CONTENT).performTextInput(marker)

        // Straight back, well inside the 1 s debounce.
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        composeRule.waitUntil(timeoutMillis = WAIT_TIMEOUT) {
            runBlocking {
                app.container.noteRepository.observeActiveNotes().first()
                    .any { it.content == marker }
            }
        }
        // Counted as a delta, not an absolute: the emulator carries notes from earlier runs.
        val bodies = runBlocking { app.container.noteRepository.observeActiveNotes().first() }
            .map { it.content }
        assertEquals(
            "the typed text must appear exactly once, not duplicated by the back gesture",
            1,
            bodies.count { it == marker },
        )
    }

    @Test
    fun backFromTheEditorReturnsToTheListWithoutCreatingASecondNote() {
        composeRule.waitForIdle()
        val baseline = runBlocking { app.container.noteRepository.observeActiveNotes().first().size }
        val first = "第一条-" + System.nanoTime()
        val second = "第二条-" + System.nanoTime()
        composeRule.onNodeWithTag(NoteListTags.CREATE).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(EditorTags.CONTENT).performTextInput(first)

        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitUntil(timeoutMillis = WAIT_TIMEOUT) {
            runBlocking {
                app.container.noteRepository.observeActiveNotes().first().size == baseline + 1
            }
        }
        composeRule.waitForIdle()

        // The list is on screen again, and the button opens a second, separate note.
        composeRule.onNodeWithTag(NoteListTags.CREATE).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(EditorTags.CONTENT).performTextInput(second)
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        composeRule.waitUntil(timeoutMillis = WAIT_TIMEOUT) {
            runBlocking {
                app.container.noteRepository.observeActiveNotes().first().size == baseline + 2
            }
        }
        val bodies = runBlocking { app.container.noteRepository.observeActiveNotes().first() }
            .map { it.content }
        assertEquals("one note per editor session, not a duplicate", 1, bodies.count { it == first })
        assertEquals(1, bodies.count { it == second })
    }

    private companion object {
        const val WAIT_TIMEOUT = 20_000L
    }
}
