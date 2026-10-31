package com.example.privatenotes.feature.notes

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.core.app.ApplicationProvider
import com.example.privatenotes.data.media.NoteMediaEntity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class InlineMediaEditorTest {
    @get:Rule val compose = createComposeRule()
    @Test fun pickerInsertionPointSurvivesSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        var point: androidx.compose.runtime.MutableIntState? = null
        restoration.setContent { point = rememberMediaInsertionOffset(99) }
        compose.runOnIdle { point!!.intValue = 4 }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals(4, point!!.intValue) }
    }
    @Test fun oneEditableBufferSelectsAcrossImagesAndPublishesCanonicalBody() {
        val one = InlineMediaSyntax.reference("one")
        val two = InlineMediaSyntax.reference("two")
        val state = TextFieldState("left${one}middle${two}right")
        var root: View? = null
        compose.setContent {
            root = LocalView.current.rootView
            MaterialTheme {
                InlineMediaEditor(state, listOf(NoteMediaEntity("one", "n", "p.png", "image/png", 12, "hash")),
                    { Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888) }, { error("unused") }, {}, {})
            }
        }
        compose.runOnIdle {
            fun find(v: View): RichMediaEditText? = if (v is RichMediaEditText) v else
                (v as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it)) } }
            val editor = checkNotNull(find(root!!))
            assertEquals("left\uFFFCmiddle\uFFFCright", editor.text.toString())
            assertFalse(editor.text.toString().contains("private-note-media"))
            editor.setSelection(2, 13)
            assertEquals(2, state.selection.start)
            assertEquals("left${one}middle${two}r".length, state.selection.end)
            editor.text.replace(2, 13, "NEW")
            assertEquals("leNEWight", state.text.toString())
        }
    }
    @Test fun hardwareBackspaceAtMediaBoundaryRemovesExactlyOneObjectAndMergesText() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val editor = RichMediaEditText(context)
        val body = "before" + InlineMediaSyntax.reference("picture") + "after"
        var changed = ""
        editor.onDocumentChanged = { value, _, _ -> changed = value }
        editor.setDocument(body, 0, 0)
        editor.setSelection(7)
        editor.onKeyDown(KeyEvent.KEYCODE_DEL, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        assertEquals("beforeafter", RichMediaEditText.canonical(editor.text))
        assertEquals("beforeafter", changed)
        assertEquals(6, editor.selectionStart)
    }
    @Test fun clipboardCopyHidesReferencesAndCutPreservesUnselectedBody() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val editor = RichMediaEditText(context)
        val token = InlineMediaSyntax.reference("private-id")
        editor.setDocument("left${token}right", 0, 0)
        editor.setSelection(2, 8)
        editor.onTextContextMenuItem(android.R.id.copy)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("ft[媒体]rig", clipboard.primaryClip!!.getItemAt(0).text.toString())
        editor.onTextContextMenuItem(android.R.id.cut)
        assertEquals("leht", RichMediaEditText.canonical(editor.text))
        editor.onTextContextMenuItem(android.R.id.paste)
        assertEquals("left[媒体]right", RichMediaEditText.canonical(editor.text))
        assertTrue(InlineMediaSyntax.ids(RichMediaEditText.canonical(editor.text)).isEmpty())
    }
    @Test fun externalInsertionAndReopeningRestoreHiddenObjectsAndCaret() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val editor = RichMediaEditText(context)
        val body = "left" + InlineMediaSyntax.reference("one") + "right"
        val point = "left${InlineMediaSyntax.reference("one")}ri".length
        editor.setDocument(body, point, point)
        assertEquals(7, editor.selectionStart)
        val inserted = InlineMediaSyntax.insert(body, point, "two")
        editor.setDocument(inserted, point, point)
        val reopened = RichMediaEditText(context)
        reopened.setDocument(RichMediaEditText.canonical(editor.text), 0, 0)
        assertEquals(inserted, RichMediaEditText.canonical(reopened.text))
        assertEquals(2, reopened.text.count { it == '\uFFFC' })
    }
    @Test fun saveEchoDoesNotResetSelectionOrComposition() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val editor = RichMediaEditText(context)
        val body = "left" + InlineMediaSyntax.reference("one") + "right"
        editor.setDocument(body, 0, 0)
        editor.setSelection(6, 9)
        val marker = Any()
        editor.text.setSpan(marker, 6, 8, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or android.text.Spanned.SPAN_COMPOSING)
        editor.setDocument(body, 0, 0)
        assertEquals(6, editor.selectionStart)
        assertEquals(9, editor.selectionEnd)
        assertEquals(6, editor.text.getSpanStart(marker))
    }
}
