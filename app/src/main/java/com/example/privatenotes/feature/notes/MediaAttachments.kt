package com.example.privatenotes.feature.notes

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.privatenotes.data.media.NoteMediaEntity
import com.example.privatenotes.ui.markdown.MarkdownText
import kotlinx.coroutines.delay

/** The toolbar contains only actions; media themselves live at their position in the note body. */
@Composable
internal fun MediaAttachments(
    state: NoteEditorUiState,
    onImage: () -> Unit,
    onVideo: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().testTag("media_attachments"), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onImage, enabled = !state.mediaBusy && state.isLoaded && state.loadError == null,
            modifier = Modifier.testTag("add_image")) { Text("图片") }
        TextButton(onClick = onVideo, enabled = !state.mediaBusy && state.isLoaded && state.loadError == null,
            modifier = Modifier.testTag("add_video")) { Text("视频") }
    }
    if (state.mediaBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.mediaError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

/** Render note text and media blocks in a single scrolling column, preserving body order. */
@Composable
internal fun InlineMediaPreview(
    body: String,
    media: List<NoteMediaEntity>,
    imagePreview: suspend (NoteMediaEntity) -> Bitmap,
    videoSource: suspend (NoteMediaEntity) -> MediaDataSource,
    onRemove: (NoteMediaEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val byId = remember(media) { media.associateBy { it.id } }
    LazyColumn(modifier.fillMaxSize()) {
        itemsIndexed(InlineMediaSyntax.split(body)) { index, part ->
            part.text?.takeIf { it.isNotBlank() }?.let { text ->
                MarkdownText(text, Modifier.fillMaxWidth(), scrollable = false)
            }
            part.mediaId?.let { id ->
                val item = byId[id]
                if (item != null) {
                    InlineMediaBlock(item, imagePreview, videoSource, onRemove)
                } else {
                    Text("媒体暂不可用", color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
internal fun InlineMediaBlock(
    media: NoteMediaEntity,
    imagePreview: suspend (NoteMediaEntity) -> Bitmap,
    videoSource: suspend (NoteMediaEntity) -> MediaDataSource,
    onRemove: (NoteMediaEntity) -> Unit,
) {
    var removeConfirm by remember(media.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("inline_media_${media.id}")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = { removeConfirm = true }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = "移除正文媒体")
            }
        }
        if (media.mime.startsWith("video/")) {
            InlineVideo(media, videoSource)
        } else {
            InlineImage(media, imagePreview)
        }
    }
    if (removeConfirm) AlertDialog(
        onDismissRequest = { removeConfirm = false },
        title = { Text("从正文移除媒体？") },
        text = { Text("这会同时删除笔记中保存的媒体数据。") },
        confirmButton = { TextButton(onClick = { onRemove(media); removeConfirm = false }) { Text("移除") } },
        dismissButton = { TextButton(onClick = { removeConfirm = false }) { Text("取消") } },
    )
}

@Composable
private fun InlineImage(media: NoteMediaEntity, load: suspend (NoteMediaEntity) -> Bitmap) {
    var bitmap by remember(media.id) { mutableStateOf<Bitmap?>(null) }
    var error by remember(media.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(media.id) {
        try { bitmap = load(media) }
        catch (c: kotlinx.coroutines.CancellationException) { throw c }
        catch (_: Exception) { error = "图片读取或校验失败" }
    }
    val image = bitmap
    when {
        error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
        image == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
        else -> {
            var scale by remember(media.id) { mutableFloatStateOf(1f) }
            Image(image.asImageBitmap(), contentDescription = null,
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).testTag("media_image")
                    .graphicsLayer(scaleX = scale, scaleY = scale)
                    .transformable(rememberTransformableState { zoom, _, _ -> scale = (scale * zoom).coerceIn(1f, 5f) }))
            if (media.mime == "image/gif") Text("GIF 静态预览", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun InlineVideo(media: NoteMediaEntity, openSource: suspend (NoteMediaEntity) -> MediaDataSource) {
    var source by remember(media.id) { mutableStateOf<MediaDataSource?>(null) }
    var error by remember(media.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(media.id) {
        try { source = openSource(media) }
        catch (c: kotlinx.coroutines.CancellationException) { throw c }
        catch (_: Exception) { error = "视频读取或校验失败" }
    }
    when {
        error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
        source == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
        else -> InlineVideoPlayer(source!!)
    }
}

@Composable
private fun InlineVideoPlayer(source: MediaDataSource) {
    val player = remember(source) { MediaPlayer() }
    val owner = LocalLifecycleOwner.current
    var ready by remember(source) { mutableStateOf(false) }
    var playing by remember(source) { mutableStateOf(false) }
    var position by remember(source) { mutableFloatStateOf(0f) }
    var duration by remember(source) { mutableIntStateOf(0) }
    var error by remember(source) { mutableStateOf<String?>(null) }
    DisposableEffect(player, owner) {
        player.setOnPreparedListener { ready = true; duration = it.duration }
        player.setOnCompletionListener { playing = false; position = duration.toFloat() }
        player.setOnErrorListener { _, _, _ -> error = "此视频无法播放，可能是设备不支持其编码"; playing = false; ready = false; true }
        try { player.setDataSource(source); player.prepareAsync() } catch (_: Exception) { error = "视频无法打开" }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                if (ready) runCatching { player.pause() }; playing = false
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); player.release(); runCatching { source.close() } }
    }
    LaunchedEffect(playing) {
        while (playing) { position = runCatching { player.currentPosition.toFloat() }.getOrDefault(0f); delay(250) }
    }
    Column(Modifier.fillMaxWidth()) {
        AndroidView(factory = { context -> SurfaceView(context).apply {
            setSecure(true)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) { runCatching { player.setDisplay(holder) } }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) { runCatching { player.setDisplay(null) } }
            })
        } }, modifier = Modifier.fillMaxWidth().height(220.dp))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (ready) {
            Slider(value = position.coerceIn(0f, maxOf(1, duration).toFloat()),
                onValueChange = { position = it; player.seekTo(it.toInt()) }, valueRange = 0f..maxOf(1, duration).toFloat())
            Row {
                Button(onClick = {
                    if (playing) player.pause() else {
                        if (position >= duration) { player.seekTo(0); position = 0f }
                        player.start()
                    }
                    playing = !playing
                }, modifier = Modifier.testTag("media_play_pause")) { Text(if (playing) "暂停" else "播放") }
                Text("${position.toInt() / 1000} / ${duration / 1000} 秒",
                    modifier = Modifier.padding(12.dp).testTag("media_position"))
            }
        } else if (error == null) Text("正在读取视频…")
    }
}

// Kept for legacy callers/tests that provide already-decoded in-memory media.
internal class MemoryMediaSource(private var bytes: ByteArray) : android.media.MediaDataSource() {
    @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        require(position >= 0 && offset >= 0 && size >= 0 && offset <= buffer.size - size)
        if (size == 0) return 0
        if (position >= bytes.size) return -1
        val count = minOf(size, bytes.size - position.toInt())
        bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
        return count
    }
    @Synchronized override fun getSize(): Long = bytes.size.toLong()
    @Synchronized override fun close() { bytes = ByteArray(0) }
}
