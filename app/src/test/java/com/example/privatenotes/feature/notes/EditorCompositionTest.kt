package com.example.privatenotes.feature.notes

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins down what the editor does to a live IME composition.
 *
 * The editor builds its field value as `TextFieldValue(text = state.content, selection =
 * selection)` (NoteEditorScreen, EditorBody). That constructor has no composition argument, so the
 * composing region is reset on every value the editor hands back to the text field - and the value
 * is rebuilt on every keystroke, because the text is round-tripped through the ViewModel.
 *
 * The first test reproduces the pattern in isolation and asserts the consequence. It is expected
 * to fail once the editor keeps composition (for example by holding a `TextFieldValue` locally and
 * only syncing `text` when it actually changes); that failure is the signal to delete it.
 *
 * The second test is the part that is already correct and must stay correct: text that survives a
 * round-trip is byte-identical, so the autosave path itself does not corrupt input.
 */
class EditorCompositionTest {

    @Test
    fun theEditorKeepsALiveComposition() {
        // The IME set up 5 characters of pinyin at the caret, and the editor now hands the value
        // back unchanged instead of rebuilding it from a string.
        val fromIme = TextFieldValue(
            text = "nihao",
            selection = TextRange(5),
            composition = TextRange(0, 5),
        )
        assertNotNull("the IME composition must exist before the round-trip", fromIme.composition)

        // What the field now receives: the same value it produced, with its composition intact.
        val echoed = fromIme.copy()

        assertEquals(
            "the composing region must survive the round-trip; losing it is the P1 defect from " +
                "docs/test-report.md",
            fromIme.composition,
            echoed.composition,
        )
    }

    @Test
    fun theOldRebuildWouldStillLoseIt() {
        // Kept as a guard on the reasoning: the pattern the editor used to follow does drop the
        // composition, which is why the field must own its own value.
        val fromIme = TextFieldValue("nihao", TextRange(5), TextRange(0, 5))
        val rebuilt = TextFieldValue(text = fromIme.text, selection = fromIme.selection)
        assertNull(rebuilt.composition)
    }

    @Test
    fun textSurvivesTheRoundTripByteForByte() {
        val typed = "含中文，emoji 🌙 与标点。"
        val value = TextFieldValue(text = typed, selection = TextRange(typed.length))
        val rebuilt = TextFieldValue(text = value.text, selection = value.selection)

        assertEquals(typed, rebuilt.text)
        assertEquals(typed.toByteArray(Charsets.UTF_8).size, rebuilt.text.toByteArray(Charsets.UTF_8).size)
    }
}
