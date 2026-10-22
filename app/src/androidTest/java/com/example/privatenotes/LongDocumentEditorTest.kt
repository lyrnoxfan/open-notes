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
        for (line in listOf(31, 60, 100, 5)) {
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
        // Selection is initiated with a physical long press on the visible Latin word in
        // line 100; semantic SetSelection would not test scrolled hit testing/handles.
        scrollToAndTapLine(100)
        val word = document.indexOf("第100行") + 9
        val wordRect = layout().getBoundingBox(word + 1)
        val local = wordRect.center + textOriginInRoot() - field().fetchSemanticsNode().boundsInRoot.topLeft
        field().performTouchInput { longClick(local) }
        compose.waitForIdle()
        val selected = selection()
        assertEquals("long press on row 100 must select that row's word", "abc",
            text().substring(selected.min, selected.max))
        assertEquals(word, selected.min)
        field().performTextInput("已修改")
        val edited = document.replaceRange(word, word + 3, "已修改")
        assertEquals(edited, text())
        hideKeyboard()
        compose.onNodeWithContentDescription(compose.activity.getString(com.example.privatenotes.R.string.editor_back)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(documentTitle).performClick()
        compose.waitUntil(15_000) { runCatching { text() == edited }.getOrDefault(false) }
        Log.i("LongDocument", "long press selected row 100; edited content survived save/reopen")
    }

    @Test fun firstImeAfterEachListEntryKeepsTheTappedLongDocumentLine() {
        openDocument()
        for (line in listOf(31, 60, 100)) {
            for (entry in 1..3) {
                // Re-enter from the list every time: hiding the keyboard is not a new entry.
                val start = scrollToAndTapLine(line)
                assertEquals("first tap on row $line entry $entry", start, selection().start)
                assertCursorVisible("row $line entry $entry: first IME")
                if (entry == 1 || entry == 3) captureLiveView("row-$line-entry-$entry-first-ime")
                hideKeyboard()
                // Tap actual glyphs, not the existing caret/selection handle.
                val glyph = layout().getBoundingBox(start + 2)
                val point = glyph.center + textOriginInRoot() - field().fetchSemanticsNode().boundsInRoot.topLeft
                Log.i("LongDocument", "row $line entry $entry: second tap=$point")
                field().performTouchInput { click(point) }
                waitForKeyboard(true)
                assertCursorVisible("row $line entry $entry: second IME")
                val insertion = selection().start
                assertTrue("second tap still addresses row $line", insertion in start..(start + 14))
                field().performTextInput("验")
                assertEquals(document.replaceRange(insertion, insertion, "验"), text())
                field().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Backspace) }
                assertEquals(document, text())
                hideKeyboard()
                compose.onNodeWithContentDescription(compose.activity.getString(R.string.editor_back)).performClick()
                compose.waitForIdle()
                compose.onNodeWithText(documentTitle).performClick()
                compose.waitUntil(15_000) { runCatching { text() == document }.getOrDefault(false) }
                Log.i("LongDocument", "PASS row $line entry $entry: first/second IME and exact content after list re-entry")
            }
        }
    }

    /** Capture the current actual app view, not a synthetic host; retain FLAG_SECURE.
     * The IME is a separate system window and is deliberately not included. Its visible
     * insets and the real resized app viewport are independently asserted above.
     */
    private fun captureLiveView(name: String) {
        compose.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height,
                android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val directory = java.io.File(activity.filesDir, "longdoc-evidence").apply { mkdirs() }
            java.io.File(directory, "$name.png").outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
        }
    }

    private val documentTitle = "长文滚动验收-${java.util.UUID.randomUUID()}"

    private fun openDocument() {
        val id = runBlocking { container.noteRepository.createNote(documentTitle, document) }
        compose.waitForIdle()
        compose.onNodeWithText(documentTitle).performClick()
        compose.waitUntil(15_000) { runCatching { text() == document }.getOrDefault(false) }
        Log.i("LongDocument", "opened id=$id, 100 distinguishable lines")
    }

    private fun layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        field().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    /** Actual rendered glyph origin, independent of the text field's hit-testing code. */
    private fun textCoordinates(): androidx.compose.ui.layout.LayoutCoordinates {
        val result = layout()
        val node = field().fetchSemanticsNode()
        return compose.runOnIdle {
            fun leaves(n: LayoutNode): List<LayoutNode> =
                if (n.children.isEmpty()) listOf(n) else n.children.flatMap { leaves(it) }
            val candidates = leaves(node.layoutInfo as LayoutNode).filter {
                it.coordinates.size.width == result.size.width && it.coordinates.size.height == result.size.height
            }
            check(candidates.size == 1) { "Expected one rendered text layout, got ${candidates.map { it.coordinates.size }}" }
            candidates.single().coordinates
        }
    }

    private fun textOriginInRoot(): Offset {
        val coordinates = textCoordinates()
        return compose.runOnIdle { coordinates.localToRoot(Offset.Zero) }
    }

    private fun watchFirstImeFrames(line: Int, target: Rect, viewportTop: Float): List<Float> {
        val coordinates = textCoordinates()
        val samples = java.util.concurrent.CopyOnWriteArrayList<Float>()
        val until = android.os.SystemClock.uptimeMillis() + 1_600
        compose.activityRule.scenario.onActivity {
            val choreographer = android.view.Choreographer.getInstance()
            val callback = object : android.view.Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (coordinates.isAttached) {
                        val y = coordinates.localToRoot(Offset.Zero).y
                        samples.add(y)
                        Log.i("LongDocument", "FRAME line=$line originY=$y targetY=${y + target.center.y} viewportTop=$viewportTop")
                    }
                    if (android.os.SystemClock.uptimeMillis() < until) choreographer.postFrameCallback(this)
                }
            }
            choreographer.postFrameCallback(callback)
        }
        return samples
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
            val origin = textOriginInRoot()
            val localY = origin.y + target.center.y - bounds.top
            if (localY in 8f..(bounds.height - 8f)) {
                Log.i("LongDocument", "BEFORE IME line=$line origin=$origin viewport=$bounds tapY=$localY")
                val frames = watchFirstImeFrames(line, target, bounds.top)
                field().performTouchInput { click(Offset(target.left + 1f, localY)) }
                waitForKeyboard(true)
                Thread.sleep(1_000)
                assertTrue("IME opening must not flash the document top for offscreen line $line: $frames",
                    line <= 5 || frames.none { it >= bounds.top - 2f })
                Log.i("LongDocument", "AFTER IME line=$line origin=${textOriginInRoot()} viewport=${field().fetchSemanticsNode().boundsInRoot}")
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
