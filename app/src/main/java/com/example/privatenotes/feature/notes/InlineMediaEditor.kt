package com.example.privatenotes.feature.notes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaDataSource
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ReplacementSpan
import android.view.Gravity
import android.view.MotionEvent
import android.widget.EditText
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.viewinterop.AndroidView
import com.example.privatenotes.data.media.NoteMediaEntity
import kotlinx.coroutines.launch

/** A single native editable buffer gives Android/IME a real cross-media selection. Media occupy
 * one replacement character, rather than separate text fields or editable Markdown tokens. */
@Composable
internal fun InlineMediaEditor(
    state: TextFieldState,
    media: List<NoteMediaEntity>,
    imagePreview: suspend (NoteMediaEntity) -> Bitmap,
    videoSource: suspend (NoteMediaEntity) -> MediaDataSource,
    onRemove: (NoteMediaEntity) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val currentMedia by rememberUpdatedState(media.associateBy { it.id })
    val currentPreview by rememberUpdatedState(imagePreview)
    val currentFocus by rememberUpdatedState(onFocusChanged)
    var opened by remember { mutableStateOf<String?>(null) }
    val color = MaterialTheme.colorScheme.onBackground.toArgb()
    AndroidView(
        factory = { context ->
            RichMediaEditText(context).apply {
                onDocumentChanged = { body, start, end ->
                    state.edit {
                        if (toString() != body) replace(0, length, body)
                        selection = TextRange(start, end)
                    }
                }
                setOnFocusChangeListener { _, focused -> currentFocus(focused) }
                openMedia = { opened = it }
                requestImage = { id ->
                    val item = currentMedia[id]
                    if (item != null && item.mime.startsWith("image/")) scope.launch {
                        try { cacheImage(id, currentPreview(item)) }
                        catch (c: kotlinx.coroutines.CancellationException) { throw c }
                        catch (_: Exception) { failImage(id) }
                    }
                }
            }
        },
        update = { editor ->
            editor.setTextColor(color)
            editor.isEnabled = enabled
            editor.mediaById = currentMedia
            editor.setDocument(state.text.toString(), state.selection.start, state.selection.end)
        },
        modifier = modifier.fillMaxSize().testTag("inline_media_editor"),
    )
    currentMedia[opened]?.let { item ->
        AlertDialog(
            onDismissRequest = { opened = null },
            confirmButton = { TextButton(onClick = { opened = null }) { Text("关闭") } },
            text = { InlineMediaBlock(item, imagePreview, videoSource, { onRemove(it); opened = null }) },
        )
    }
}

/** Serialization only expands intact object spans. Typed/pasted text cannot create a media span.
 * Clipboard export is deliberately plain text: internal references and private span objects never
 * leave the app, and clipboard paste cannot duplicate a database-owned span. */
internal class RichMediaEditText(context: Context) : EditText(context) {
    var onDocumentChanged: ((String, Int, Int) -> Unit)? = null
    var openMedia: ((String) -> Unit)? = null
    var requestImage: ((String) -> Unit)? = null
    private var resetHistory = false
    var mediaById: Map<String, NoteMediaEntity> = emptyMap()
        set(value) {
            // Deleted rows cannot be resurrected by Android's private undo stack. Once an
            // atomic deletion commits, rebuild the editable to discard that stale history.
            if ((field.keys - value.keys).isNotEmpty()) resetHistory = true
            field = value
            invalidate()
        }
    private var changing = false
    private val loading = mutableSetOf<String>()
    private val failed = mutableSetOf<String>()
    private val images = object : android.util.LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private var downX = 0f
    private var downY = 0f

    init {
        tag = "rich_media_native_editor"
        background = null
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
        setPadding(0, 0, 0, (32 * resources.displayMetrics.density).toInt())
        setHorizontallyScrolling(false)
        setTextIsSelectable(true)
        // Native view hierarchy state must not restore raw object characters without their spans;
        // the canonical Compose/SavedState document owns restoration instead.
        isSaveEnabled = false
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { publish() }
        })
    }

    fun setDocument(body: String, start: Int, end: Int) {
        if (!resetHistory && canonical(text) == body) return // preserves composition, selection and undo on save echoes
        resetHistory = false
        changing = true
        try {
            val projected = SpannableStringBuilder()
            InlineMediaSyntax.split(body).forEach { part ->
                part.text?.let { projected.append(it) }
                part.mediaId?.let { id ->
                    val at = projected.length
                    projected.append('\uFFFC')
                    projected.setSpan(MediaSpan(id), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            setText(projected)
            setSelection(displayOffset(body, start), displayOffset(body, end))
        } finally { changing = false }
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        publish()
    }

    private fun publish() {
        if (changing || selectionStart < 0 || selectionEnd < 0) return
        val value = text ?: return
        onDocumentChanged?.invoke(canonical(value), canonicalOffset(value, selectionStart), canonicalOffset(value, selectionEnd))
    }

    override fun onTextContextMenuItem(id: Int): Boolean {
        val lo = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
        val hi = maxOf(selectionStart, selectionEnd).coerceAtLeast(lo)
        return when (id) {
            android.R.id.copy, android.R.id.cut -> {
                val selected = text.subSequence(lo, hi)
                val clip = StringBuilder()
                for (i in selected.indices) {
                    val span = if (selected is Spanned) selected.getSpans(i, i + 1, MediaSpan::class.java).firstOrNull() else null
                    clip.append(if (selected[i] == '\uFFFC' && span != null) "[媒体]" else selected[i].toString())
                }
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("笔记", clip.toString()))
                if (id == android.R.id.cut && isEnabled) text.delete(lo, hi)
                true
            }
            android.R.id.paste, android.R.id.pasteAsPlainText -> {
                if (isEnabled) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val item = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                    val plain = item?.coerceToText(context)?.toString()
                    if (plain != null) text.replace(lo, hi, plain)
                }
                true
            }
            else -> super.onTextContextMenuItem(id)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) { downX = event.x; downY = event.y }
        if (event.actionMasked == MotionEvent.ACTION_UP &&
            kotlin.math.abs(event.x - downX) + kotlin.math.abs(event.y - downY) < 8 * resources.displayMetrics.density) {
            val at = getOffsetForPosition(event.x, event.y)
            val value = text
            val span = value.getSpans((at - 1).coerceAtLeast(0), (at + 1).coerceAtMost(value.length), MediaSpan::class.java)
                .firstOrNull { token ->
                    val p = value.getSpanStart(token)
                    val l = layout
                    if (p < 0 || l == null) false else {
                        val line = l.getLineForOffset(p)
                        val x = event.x - totalPaddingLeft + scrollX
                        val y = event.y - totalPaddingTop + scrollY
                        x >= l.getPrimaryHorizontal(p) && x <= l.getPrimaryHorizontal(p) + availableWidth() &&
                            y >= l.getLineTop(line) && y <= l.getLineBottom(line)
                    }
                }
            if (span != null && !isLongClickableSelection()) { performClick(); openMedia?.invoke(span.id); return true }
        }
        return super.onTouchEvent(event)
    }

    private fun isLongClickableSelection() = selectionStart != selectionEnd
    override fun performClick(): Boolean { super.performClick(); return true }
    private fun availableWidth() = (width - totalPaddingLeft - totalPaddingRight).coerceAtLeast((180 * resources.displayMetrics.density).toInt())
    fun cacheImage(id: String, bitmap: Bitmap) { loading.remove(id); images.put(id, bitmap); invalidate() }
    fun failImage(id: String) { loading.remove(id); failed.add(id); invalidate() }

    private inner class MediaSpan(val id: String) : ReplacementSpan() {
        private fun boxHeight() = (200 * resources.displayMetrics.density).toInt()
        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            fm?.let { it.ascent = -boxHeight(); it.top = it.ascent; it.descent = 0; it.bottom = 0 }
            return availableWidth()
        }
        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val saved = Paint(paint)
            val box = RectF(x, (y - boxHeight()).toFloat(), x + availableWidth(), y.toFloat())
            saved.color = android.graphics.Color.rgb(225, 235, 228)
            canvas.drawRoundRect(box, 12f, 12f, saved)
            val bitmap = images.get(id)
            if (bitmap != null) {
                val ratio = minOf(box.width() / bitmap.width, box.height() / bitmap.height)
                val w = bitmap.width * ratio; val h = bitmap.height * ratio
                canvas.drawBitmap(bitmap, null, RectF(box.centerX() - w/2, box.centerY() - h/2, box.centerX() + w/2, box.centerY() + h/2), saved)
            } else {
                val video = mediaById[id]?.mime?.startsWith("video/") == true
                saved.color = android.graphics.Color.rgb(42, 66, 51)
                saved.textSize = 16 * resources.displayMetrics.scaledDensity
                canvas.drawText(if (video) "▶  点按播放视频" else if (id in failed) "图片读取失败，点按查看" else "正在读取图片…", x + 16, box.centerY(), saved)
                if (!video && mediaById[id]?.mime?.startsWith("image/") == true && id !in failed && loading.add(id))
                    post { requestImage?.invoke(id) }
            }
        }
    }

    companion object {
        fun canonical(value: CharSequence): String {
            if (value !is Spanned) return value.toString()
            val out = StringBuilder()
            var cursor = 0
            for (span in value.getSpans(0, value.length, RichMediaEditText.MediaSpan::class.java).sortedBy { value.getSpanStart(it) }) {
                val start = value.getSpanStart(span)
                if (start < cursor || start !in value.indices || value.getSpanEnd(span) != start + 1 || value[start] != '\uFFFC') continue
                out.append(value, cursor, start)
                out.append(InlineMediaSyntax.reference(span.id))
                cursor = start + 1
            }
            out.append(value, cursor, value.length)
            return out.toString()
        }
        fun canonicalOffset(value: CharSequence, offset: Int): Int = canonical(value.subSequence(0, offset.coerceIn(0, value.length))).length
        fun displayOffset(body: String, offset: Int): Int {
            var raw = 0; var shown = 0
            val target = offset.coerceIn(0, body.length)
            InlineMediaSyntax.split(body).forEach { part ->
                val length = part.text?.length ?: InlineMediaSyntax.reference(part.mediaId!!).length
                if (target <= raw + length) return shown + if (part.text != null) target - raw else if (target == raw) 0 else 1
                raw += length; shown += part.text?.length ?: 1
            }
            return shown
        }
    }
}
