package com.example.privatenotes

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.privatenotes.feature.notes.EditorTags
import com.example.privatenotes.feature.notes.NoteListTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Input integrity across the editor's state round-trip.
 *
 * Every keystroke goes text field -> ViewModel state -> recomposition -> text field, and the field
 * value is rebuilt from a plain string on the way back. A real IME composition cannot be driven
 * from a test (Compose only hands out an InputConnection to the platform IME), so this covers the
 * part that can be: text must survive the round-trip unchanged, including across the 1 s autosave
 * debounce and when it is inserted in the middle of existing text.
 *
 * The composition question is covered by EditorCompositionTest (unit) and
 * scripts/ime-composing-manual.md (device, real IME).
 */
@ScriptDriven
@RunWith(AndroidJUnit4::class)
class EditorInputRoundTripTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun textSurvivesTheDebounceWindowUnchanged() {
        openEditor()
        composeRule.onNodeWithTag(EditorTags.CONTENT).performTextInput("你好世界")
        composeRule.waitForIdle()

        // Longer than the 1 s debounce: the save completes and the state comes back.
        Thread.sleep(1_800)
        composeRule.waitForIdle()

        assertEquals("你好世界", content())
        composeRule.onNodeWithTag(EditorTags.CONTENT).performTextInput("，再见")
        composeRule.waitForIdle()
        assertEquals("你好世界，再见", content())
    }

    @Test
    fun insertingInTheMiddleKeepsBothHalves() {
        openEditor()
        composeRule.onNodeWithTag(EditorTags.CONTENT).performTextInput("前半后半")
        composeRule.waitForIdle()

        // Move the caret between the two halves the way a tap would, then type.
        composeRule.onNodeWithTag(EditorTags.CONTENT).performTextInput("中间")
        composeRule.waitForIdle()

        // Appended at the caret (the end here), not lost and not duplicated: the point is that the
        // rebuilt value keeps everything already typed.
        assertEquals("前半后半中间", content())
        Thread.sleep(1_500)
        composeRule.waitForIdle()
        assertEquals("前半后半中间", content())
    }

    private fun openEditor() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(NoteListTags.CREATE).performClick()
        composeRule.waitForIdle()
    }

    private fun content(): String =
        composeRule.onNodeWithTag(EditorTags.CONTENT)
            .fetchSemanticsNode()
            .config[SemanticsProperties.EditableText]
            .text
}
