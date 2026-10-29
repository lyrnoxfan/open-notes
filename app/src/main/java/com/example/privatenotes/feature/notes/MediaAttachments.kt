package com.example.privatenotes.feature.notes

import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.privatenotes.data.media.NoteMediaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun MediaAttachments(state: NoteEditorUiState, onImage: () -> Unit, onVideo: () -> Unit,
    onRemove: (NoteMediaEntity) -> Unit, read: suspend (NoteMediaEntity) -> ByteArray,
    videoSource: suspend (NoteMediaEntity) -> MediaDataSource) {
    var selected by remember { mutableStateOf<NoteMediaEntity?>(null) }
    var remove by remember { mutableStateOf<NoteMediaEntity?>(null) }
    Column(Modifier.fillMaxWidth().testTag("media_attachments")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onImage, enabled = !state.mediaBusy && state.isLoaded && state.loadError == null,
                modifier = Modifier.testTag("add_image")) { Text("添加图片") }
            TextButton(onClick = onVideo, enabled = !state.mediaBusy && state.isLoaded && state.loadError == null,
                modifier = Modifier.testTag("add_video")) { Text("添加视频") }
            Text("${state.media.size}/10", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.labelSmall)
        }
        Text("离线加密保存 · 图片 8 MiB · 视频 256 MiB · 每篇 512 MiB", style = MaterialTheme.typography.labelSmall)
        if (state.mediaBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.mediaError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (state.media.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.media.forEach { media ->
                InputChip(selected = false, onClick = { selected = media }, label = {
                    Text((if (media.mime.startsWith("video/")) "视频 · " else "图片 · ") + media.name.take(24), maxLines = 1)
                }, modifier = Modifier.testTag("media_${media.id}"))
            }
        }
    }
    remove?.let { media ->
        AlertDialog(onDismissRequest = { remove = null }, title = { Text("移除附件？") },
            text = { Text("将从这篇笔记中移除“${media.name}”。原始文件不受影响。") },
            confirmButton = { TextButton(onClick = { onRemove(media); remove = null; selected = null }) { Text("移除") } },
            dismissButton = { TextButton(onClick = { remove = null }) { Text("取消") } })
    }
    selected?.let { media ->
        Dialog(onDismissRequest = { selected = null }, properties = DialogProperties(
            usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { selected = null }) { Text("关闭") }
                        TextButton(onClick = { remove = media }) { Text("移除附件") }
                    }
                    Text(media.name, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                    Text("${media.size / 1024} KiB · 离线附件", style = MaterialTheme.typography.labelSmall)
                    MediaViewer(media, read, videoSource, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MediaViewer(media: NoteMediaEntity, read: suspend (NoteMediaEntity) -> ByteArray,
    videoSource: suspend (NoteMediaEntity) -> MediaDataSource, modifier: Modifier) {
    var bytes by remember(media.id) { mutableStateOf<ByteArray?>(null) }
    var error by remember(media.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(media.id) {
        try { if (!media.mime.startsWith("video/")) bytes = read(media) }
        catch (c: kotlinx.coroutines.CancellationException) { throw c }
        catch (_: Exception) { error = "附件读取失败，请检查备份或重新导入" }
    }
    Box(modifier.fillMaxWidth()) {
        when {
            error != null -> Text(error!!)
            media.mime.startsWith("video/") -> VideoViewer(media, videoSource)
            bytes == null -> CircularProgressIndicator()
            else -> {
                var bitmap by remember(media.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
                LaunchedEffect(bytes) {
                    bitmap = withContext(Dispatchers.Default) {
                        val b = bytes!!
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(b, 0, b.size, bounds)
                        var sample = 1
                        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
                        BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    }
                    if (bitmap == null) error = "图片无法解码"
                }
                var scale by remember(media.id) { mutableFloatStateOf(1f) }
                bitmap?.let {
                    Column {
                        if (media.mime == "image/gif") Text("GIF 静态预览")
                        Image(it.asImageBitmap(), media.name, Modifier.fillMaxSize().testTag("media_image")
                            .graphicsLayer(scaleX = scale, scaleY = scale)
                            .transformable(rememberTransformableState { zoom, _, _ -> scale = (scale * zoom).coerceIn(1f, 5f) }))
                    }
                }
            }
        }
    }
}

// Decrypted bytes stay in memory. No plaintext cache, external viewer or exported provider.
internal class MemoryMediaSource(private var bytes: ByteArray) : MediaDataSource() {
    @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        require(position >= 0 && offset >= 0 && size >= 0 && offset <= buffer.size - size)
        if (size == 0) return 0
        if (position >= bytes.size) return -1
        val count = minOf(size, bytes.size - position.toInt())
        bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
        return count
    }
    @Synchronized override fun getSize() = bytes.size.toLong()
    @Synchronized override fun close() { bytes = ByteArray(0) }
}

@Composable
private fun VideoViewer(media: NoteMediaEntity, openSource: suspend (NoteMediaEntity) -> MediaDataSource) {
    var source by remember(media.id) { mutableStateOf<MediaDataSource?>(null) }
    var loadError by remember(media.id) { mutableStateOf(false) }
    LaunchedEffect(media.id) {
        try { source = openSource(media) }
        catch (c: kotlinx.coroutines.CancellationException) { throw c }
        catch (_: Exception) { loadError = true }
    }
    DisposableEffect(media.id) { onDispose { source?.close() } }
    if (loadError) { Text("附件读取或校验失败"); return }
    val data = source ?: run { CircularProgressIndicator(); return }
    VideoPlayer(data)
}

@Composable
private fun VideoPlayer(source: MediaDataSource) {
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
        try { player.setDataSource(source); player.prepareAsync() }
        catch (_: Exception) { error = "视频无法打开" }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                if (ready) runCatching { player.pause() }
                playing = false
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); player.release(); source.close() }
    }
    LaunchedEffect(playing) {
        while (playing) { position = runCatching { player.currentPosition.toFloat() }.getOrDefault(0f); delay(250) }
    }
    Column(Modifier.fillMaxSize()) {
        AndroidView(factory = { context -> SurfaceView(context).apply {
            setSecure(true)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) { runCatching { player.setDisplay(holder) } }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) { runCatching { player.setDisplay(null) } }
            })
        } }, modifier = Modifier.fillMaxWidth().weight(1f))
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
                Text("${position.toInt() / 1000} / ${duration / 1000} 秒", modifier = Modifier.padding(12.dp).testTag("media_position"))
            }
        } else if (error == null) Text("正在读取视频…")
    }
}
