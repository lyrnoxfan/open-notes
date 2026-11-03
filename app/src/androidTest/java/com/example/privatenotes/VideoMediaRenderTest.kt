package com.example.privatenotes

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.privatenotes.data.media.NoteMediaEntity
import com.example.privatenotes.feature.notes.InlineMediaEditor
import com.example.privatenotes.feature.notes.InlineMediaSyntax
import com.example.privatenotes.feature.notes.RichMediaEditText
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Actual hardware window pixels, NOT View.draw(Canvas(bitmap)): that software path bypasses
 * TextView's cached RenderNodes and cannot catch a stale loading placeholder. Synthetic host
 * only; production MainActivity FLAG_SECURE is never altered. No tap/scroll/focus after load. */
class VideoMediaRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun encryptedVideoPosterAppearsWithoutTouchOrTextReset() {
        val pending = CompletableDeferred<Bitmap>()
        val requested = AtomicBoolean(false)
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val name = "video-poster-render.db"
        context.deleteDatabase(name)
        System.loadLibrary("sqlcipher")
        val db = com.example.privatenotes.data.local.EncryptedDatabaseProvider.openWithPassphrase(
            context, "synthetic-poster-test".toByteArray(), name)
        val (item, poster) = try { kotlinx.coroutines.runBlocking {
            db.noteDao().insert(com.example.privatenotes.data.local.NoteEntity("n", "video", "body", 1, 1))
            val repo = com.example.privatenotes.data.media.MediaRepository(db, context,
                com.example.privatenotes.data.repository.TimeSource { 1000L })
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
                .open("media/poster-magenta.mp4").use { repo.importStream("n", "poster.mp4", "video/mp4", it) }
            val item = repo.list("n").single()
            val poster = repo.videoPreview(item, 240)
            assertTrue(poster.width <= 240 && poster.height <= 240)
            assertTrue("Decode an actual encrypted video, not a synthetic Bitmap", magentaPixels(poster) > 1000)
            // Restored bytes must produce a poster too, without any original URI or plaintext file.
            val archive = java.io.ByteArrayOutputStream()
            com.example.privatenotes.data.backup.StreamingMediaArchive(db).write(archive, "test-password".toCharArray(), "poster", 1000)
            db.clearAllTables()
            com.example.privatenotes.data.backup.StreamingMediaArchive(db).restore(archive.toByteArray().inputStream(), "test-password".toCharArray())
            assertTrue(magentaPixels(repo.videoPreview(repo.list("n").single(), 240)) > 1000)
            item to poster
        } } finally { db.close(); context.deleteDatabase(name) }
        val state = TextFieldState(InlineMediaSyntax.reference(item.id) + "\nafter")
        var root: View? = null
        compose.setContent {
            root = LocalView.current.rootView
            MaterialTheme {
                InlineMediaEditor(state, listOf(item), { requested.set(true); pending.await() },
                    { error("No video requested") }, {}, {})
            }
        }
        lateinit var editor: RichMediaEditText
        lateinit var editable: android.text.Editable
        val composition = Any()
        compose.runOnIdle {
            fun find(v: View): RichMediaEditText? = if (v is RichMediaEditText) v else
                (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
            editor = checkNotNull(find(checkNotNull(root)))
            assertTrue("Test requires real hardware text display-list caching", editor.isHardwareAccelerated)
            editor.isCursorVisible = false // no blinking cursor or focus change to mask the bug
            editor.setSelection(2, 4)
            editable = editor.text
            editable.setSpan(composition, 2, 4, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or android.text.Spanned.SPAN_COMPOSING)
        }
        compose.waitUntil(5_000) { requested.get() }
        SystemClock.sleep(250) // let the pending placeholder reach the window's RenderNode
        val before = windowPixels()
        assertEquals(0, magentaPixels(before))
        save("video-before-loading.png", before)
        pending.complete(poster)
        val deadline = SystemClock.uptimeMillis() + 3_000
        var after: Bitmap
        do {
            SystemClock.sleep(100)
            after = windowPixels()
        } while (magentaPixels(after) < 1_000 && SystemClock.uptimeMillis() < deadline)
        save("video-after-loaded.png", after)
        assertTrue("Decoded video poster must appear automatically, without touching the loading block", magentaPixels(after) >= 1_000)
        compose.runOnIdle {
            assertSame("Do not reset the native editable to force a redraw", editable, editor.text)
            assertEquals(2, editor.selectionStart)
            assertEquals(4, editor.selectionEnd)
            assertEquals(2, editor.text.getSpanStart(composition))
            assertEquals(InlineMediaSyntax.reference(item.id) + "\nafter", RichMediaEditText.canonical(editor.text))
        }
    }

    private fun windowPixels(): Bitmap {
        lateinit var bitmap: Bitmap
        val latch = CountDownLatch(1)
        var result = -1
        compose.runOnUiThread {
            val window = compose.activity.window
            bitmap = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(window, bitmap, { result = it; latch.countDown() }, Handler(Looper.getMainLooper()))
        }
        assertTrue("Window copy callback", latch.await(5, TimeUnit.SECONDS))
        assertEquals(PixelCopy.SUCCESS, result)
        return bitmap
    }

    private fun magentaPixels(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count { Color.red(it) > 240 && Color.blue(it) > 240 && Color.green(it) < 20 }
    }

    private fun save(name: String, bitmap: Bitmap) {
        val dir = File(compose.activity.filesDir, "media-render-evidence").apply { mkdirs() }
        File(dir, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
