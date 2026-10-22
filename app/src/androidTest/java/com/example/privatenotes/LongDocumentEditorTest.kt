@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.example.privatenotes

import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.node.LayoutNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.privatenotes.feature.notes.EditorTags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 100 actual lines, physical scroll gestures and coordinate taps in the real app, not a static
 * preview or a storage-only stress test. No selection action is used to move to a target line.
 *
 * Compose's value-based BasicTextField has no public scrollState. The read-only layout-tree
 * helper below finds the rendered text's coordinates to independently translate document ->
 * viewport; using the field's own tap/selection calculation as the oracle would hide the bug.
 * This introspection is test-only and pinned to our Compose version. It changes no UI state,
 * authentication, FLAG_SECURE, input handling, or database protections.
 */
@ScriptDriven
@RunWith(AndroidJUnit4::class)
class LongDocumentEditorTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val container get() = ApplicationProvider.getApplicationContext<PrivateNotesApp>().container
    private fun field() = compose.onNodeWithTag(EditorTags.CONTENT)
    private fun text() = field().fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun selection() = field().fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
    private val document = (1..100).joinToString("\n") { "第%03d行 记录 abc 中文".format(it) }

    @Test fun scrollThenTapAndEditBeyondFirstViewport() {
        openDocument()
        for (line in listOf(5, 31, 60, 100)) {
            val offset = scrollToAndTapLine(line)
            assertEquals("tap must address line $line, not a first-screen coordinate", offset, selection().start)
            assertCursorVisible("after tapping line $line and opening the real IME")
            field().performTextInput("测")
            assertEquals(document.replaceRange(offset, offset, "测"), text())
            field().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Backspace) }
            assertEquals(document, text())
            field().performTextInput("\n")
            assertEquals(document.replaceRange(offset, offset, "\n"), text())
            field().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Backspace) }
            assertEquals(document, text())
        }
    }

    @Test fun cursorRemainsVisibleWhenKeyboardReopensAtLine100() {
        openDocument()
        scrollToAndTapLine(100)
        assertCursorVisible("after the line-100 tap opens the IME")
        hideKeyboard()
        assertCursorVisible("after hiding the IME")
        // Open via a coordinate tap at the selected character. The caret must remain in view
        // during the viewport resize even when its logical offset and text are unchanged.
        val caret = cursorInField()
        field().performTouchInput { click(Offset(caret.left + 1f, caret.center.y)) }
        waitForKeyboard(true)
        assertCursorVisible("after reopening the IME without changing the selection")
    }

    private fun openDocument() {
        val title = "长文滚动验收-${java.util.UUID.randomUUID()}"
        val id = runBlocking { container.noteRepository.createNote(title, document) }
        compose.waitForIdle()
        compose.onNodeWithText(title).performClick()
        compose.waitUntil(15_000) { runCatching { text() == document }.getOrDefault(false) }
        Log.i("LongDocument", "opened id=$id, 100 distinguishable lines")
    }

    private fun layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        field().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    /** Actual rendered glyph origin, independent of the text field's hit-testing code. */
    private fun textOriginInRoot(): Offset {
        val result = layout()
        val node = field().fetchSemanticsNode()
        return compose.runOnIdle {
            fun leaves(n: LayoutNode): List<LayoutNode> =
                if (n.children.isEmpty()) listOf(n) else n.children.flatMap { leaves(it) }
            val candidates = leaves(node.layoutInfo as LayoutNode).filter {
                it.coordinates.size.width == result.size.width && it.coordinates.size.height == result.size.height
            }
            check(candidates.size == 1) { "Expected one rendered text layout, got ${candidates.map { it.coordinates.size }}" }
            candidates.single().coordinates.localToRoot(Offset.Zero)
        }
    }

    private fun cursorInField(): Rect {
        val r = layout().getCursorRect(selection().start)
        return r.translate(textOriginInRoot() - field().fetchSemanticsNode().boundsInRoot.topLeft)
    }

    private fun assertCursorVisible(stage: String) {
        compose.waitForIdle()
        val r = cursorInField()
        val height = field().fetchSemanticsNode().boundsInRoot.height
        Log.i("LongDocument", "$stage: selection=${selection()}, caret=$r viewport=$height")
        assertTrue("$stage: caret $r must be inside viewport height=$height", r.top >= -1f && r.bottom <= height + 1f)
    }

    private fun scrollToAndTapLine(line: Int): Int {
        hideKeyboard()
        // User drags, not a semantic SetSelection that teleports the caret into view.
        val result = layout()
        val start = document.indexOf("第%03d行".format(line))
        check(start >= 0)
        val target = result.getCursorRect(start)
        repeat(60) {
            val bounds = field().fetchSemanticsNode().boundsInRoot
            val localY = textOriginInRoot().y + target.center.y - bounds.top
            if (localY in 8f..(bounds.height - 8f)) {
                field().performTouchInput { click(Offset(target.left + 1f, localY)) }
                waitForKeyboard(true)
                Log.i("LongDocument", "tap line=$line expected=$start actual=${selection()} localY=$localY")
                return start
            }
            field().performTouchInput {
                if (localY >= bounds.height - 8f) {
                    swipeWithVelocity(Offset(width * .8f, height * .8f), Offset(width * .8f, height * .25f), 0f, 500)
                } else {
                    swipeWithVelocity(Offset(width * .8f, height * .25f), Offset(width * .8f, height * .8f), 0f, 500)
                }
            }
            compose.waitForIdle()
        }
        error("Could not reach line $line by scrolling")
    }

    private fun hideKeyboard() {
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        waitForKeyboard(false)
    }

    private fun waitForKeyboard(visible: Boolean) {
        compose.waitUntil(10_000) {
            var isVisible = false
            compose.activityRule.scenario.onActivity {
                isVisible = androidx.core.view.ViewCompat.getRootWindowInsets(it.window.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            }
            isVisible == visible
        }
        // Insets animation frames are real time; wait past the platform's keyboard animation.
        Thread.sleep(500)
        compose.waitForIdle()
    }
}
