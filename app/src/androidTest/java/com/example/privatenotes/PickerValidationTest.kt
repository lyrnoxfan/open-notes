@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.example.privatenotes

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputConnection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.StaleObjectException
import com.example.privatenotes.data.backup.StreamingMediaArchive
import com.example.privatenotes.feature.notes.InlineMediaSyntax
import com.example.privatenotes.feature.notes.RichMediaEditText
import com.example.privatenotes.feature.notes.EditorTags
import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Separate synthetic install, real ACTION_OPEN_DOCUMENT UI, not a mocked ActivityResult. */
@ScriptDriven
class PickerValidationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<PrivateNotesApp>()
    private val container get() = app.container
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private fun editor(): RichMediaEditText {
        fun find(v: View): RichMediaEditText? = if (v is RichMediaEditText) v else
            (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
        return checkNotNull(find(compose.activity.window.decorView))
    }
    private fun hideKeyboard() {
        compose.activityRule.scenario.onActivity { activity ->
            androidx.core.view.WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
        compose.waitForIdle()
    }
    private fun pick(tag: String, filename: String) {
        hideKeyboard()
        compose.onNodeWithTag(tag).assertIsEnabled().performClick()
        val roots = device.wait(Until.findObject(By.descContains("Show roots")), 5000)
            ?: device.findObject(By.descContains("Browse"))
        if (device.findObject(By.text(filename)) == null) {
            roots?.click()
            val downloads = device.wait(Until.findObject(By.textContains("Downloads")), 5000)
            if (downloads != null) {
                try { downloads.click() } catch (_: StaleObjectException) {
                    checkNotNull(device.wait(Until.findObject(By.textContains("Downloads")), 5000)).click()
                }
            }
        }
        val file = device.wait(Until.findObject(By.text(filename)), 15000)
        if (file == null) {
            val output = java.io.File(app.cacheDir, "picker-tree.xml")
            device.dumpWindowHierarchy(output)
            throw AssertionError("System picker did not show $filename: ${output.readText()}")
        }
        try { file.click() } catch (_: StaleObjectException) {
            checkNotNull(device.wait(Until.findObject(By.text(filename)), 5000)).click()
        }
        device.waitForIdle()
    }
    @Test fun pickerInsertCrossMediaEditAndArchiveRoundTrip() {
        val image = InstrumentationRegistry.getInstrumentation().context.assets.open("media/picker-image.png").use { it.readBytes() }
        val video = InstrumentationRegistry.getInstrumentation().context.assets.open("media/sample.mp4").use { it.readBytes() }
        val id = runBlocking { container.noteRepository.createNote("picker validation", "before middle after") }
        compose.waitUntil(20000) { compose.onAllNodesWithTag("note_row_$id").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("note_row_$id").performClick()
        compose.waitUntil(20000) { compose.onAllNodesWithTag(EditorTags.CONTENT).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(EditorTags.CONTENT).assertTextContains("before middle after")
        compose.onNodeWithTag(EditorTags.CONTENT).performClick()
        compose.onNodeWithTag(EditorTags.CONTENT).performTextInputSelection(TextRange("before middle after".length))
        pick("add_image", "wabi-picker-image.png")
        compose.waitUntil(30000) { runBlocking { container.mediaRepository.list(id).size == 1 } }
        compose.waitUntil(30000) { compose.onAllNodesWithTag("inline_media_editor").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.runOnUiThread { editor().apply { requestFocus(); setSelection(text.length) } }
        pick("add_video", "wabi-picker-video.mp4")
        compose.waitUntil(30000) { runBlocking { container.mediaRepository.list(id).size == 2 } }
        val original = runBlocking {
            val media = container.mediaRepository.list(id)
            assertArrayEquals(image, container.mediaRepository.bytes(media.single { it.mime.startsWith("image/") }))
            assertArrayEquals(video, container.mediaRepository.bytes(media.single { it.mime.startsWith("video/") }))
            val body = container.noteRepository.findById(id)!!.content
            assertEquals(2, InlineMediaSyntax.ids(body).size)
            assertTrue(body.startsWith("before middle after"))
            body
        }
        val archive = java.io.File(app.cacheDir, "picker-validation.pnm")
        runBlocking { archive.outputStream().use { StreamingMediaArchive(container.database).write(it, "test-password".toCharArray(), "test", 1000) } }
        compose.waitForIdle()
        compose.runOnUiThread {
            val e = editor(); val str = e.text.toString()
            val first = str.indexOf('\uFFFC'); val last = str.lastIndexOf('\uFFFC')
            assertTrue(first >= 0 && last > first)
            e.requestFocus(); e.setSelection(first, last + 1)
            val info = android.view.inputmethod.EditorInfo()
            val input = e.onCreateInputConnection(info)
            assertNotNull(input)
            assertTrue(input.commitText("cross-media-replaced", 1))
        }
        compose.waitUntil(30000) { runBlocking {
            val body = container.noteRepository.findById(id)?.content.orEmpty()
            body.contains("cross-media-replaced") && InlineMediaSyntax.ids(body).isEmpty() && container.mediaRepository.list(id).isEmpty()
        } }
        val replaced = runBlocking { container.noteRepository.findById(id)!!.content }
        hideKeyboard()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithTag("note_row_$id").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("note_row_$id").performClick()
        // The editor node can exist before its asynchronous repository load completes.
        compose.waitUntil(30000) {
            compose.onAllNodes(hasTestTag(EditorTags.CONTENT) and hasText(replaced))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(EditorTags.CONTENT).assertTextEquals(replaced)
        hideKeyboard(); compose.onNodeWithContentDescription("返回").performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithTag("note_row_$id").fetchSemanticsNodes().isNotEmpty() }
        runBlocking {
            container.database.clearAllTables()
            archive.inputStream().use { StreamingMediaArchive(container.database).restore(it, "test-password".toCharArray()) }
            assertEquals(original, container.noteRepository.findById(id)!!.content)
            val restored = container.mediaRepository.list(id)
            assertEquals(2, restored.size)
            assertArrayEquals(image, container.mediaRepository.bytes(restored.single { it.mime.startsWith("image/") }))
            assertArrayEquals(video, container.mediaRepository.bytes(restored.single { it.mime.startsWith("video/") }))
        }
        archive.delete()
    }
}
