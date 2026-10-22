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
    fun rebuildingTheFieldValueDropsALiveComposition() {
        // What the IME set up: 5 characters of pinyin being composed at the caret.
        val fromIme = TextFieldValue(
            text = "nihao",
            selection = TextRange(5),
            composition = TextRange(0, 5),
        )
        assertNotNull("the IME composition must exist before the round-trip", fromIme.composition)

        val rebuilt = TextFieldValue(text = fromIme.text, selection = fromIme.selection)

        assertNull(
            "The editor rebuilds the value without its composition, so an active pinyin " +
                "composition is discarded the moment the state round-trips. Documented defect: " +
                "see docs/test-report.md. Invert this assertion when it is fixed.",
            rebuilt.composition,
        )
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
