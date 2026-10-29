package com.example.privatenotes.data.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.example.privatenotes.data.local.AppDatabase
import com.example.privatenotes.data.repository.TimeSource
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class MediaRepository(private val database: AppDatabase, private val context: Context,
    private val clock: TimeSource) {
    suspend fun list(noteId: String) = database.mediaDao().forNote(noteId)
    suspend fun bytes(media: NoteMediaEntity): ByteArray = withContext(Dispatchers.IO) {
        MediaRules.read(database.mediaDao(), media)
    }
    /** Verify sequentially, then let MediaPlayer request only the chunks it needs. */
    suspend fun videoSource(media: NoteMediaEntity): android.media.MediaDataSource = withContext(Dispatchers.IO) {
        require(media.mime.startsWith("video/"))
        val dao = database.mediaDao()
        require(media.size in 1..MediaRules.MAX_BYTES)
        val hash = java.security.MessageDigest.getInstance("SHA-256")
        val count = (media.size + MediaRules.CHUNK_SIZE - 1) / MediaRules.CHUNK_SIZE
        repeat(count) { position ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val chunk = dao.chunk(media.id, position) ?: error("附件分块缺失")
            require(chunk.bytes.size == minOf(MediaRules.CHUNK_SIZE, media.size - position * MediaRules.CHUNK_SIZE))
            hash.update(chunk.bytes)
        }
        val actual = hash.digest().joinToString("") { "%02x".format(it) }
        check(actual == media.sha256) { "附件校验失败" }
        ChunkedMediaSource(media.size) { position -> dao.chunk(media.id, position)?.bytes }
    }
    suspend fun prepare(uri: Uri, expectedVideo: Boolean): BackupMedia = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "请选择系统文件选择器中的媒体" }
        val resolver = context.contentResolver
        val mime = resolver.getType(uri).orEmpty().lowercase()
        require(mime in MediaRules.MIME_TYPES && mime.startsWith(if (expectedVideo) "video/" else "image/")) {
            "支持 JPEG、PNG、WebP、GIF 图片及 MP4、WebM、3GP 视频"
        }
        var name = if (expectedVideo) "视频" else "图片"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0).orEmpty().take(200).ifBlank { name }
        }
        val bytes = resolver.openInputStream(uri)?.use { MediaRules.readBounded(it) }
            ?: error("无法读取文件")
        val media = BackupMedia(UUID.randomUUID().toString(), name, mime, bytes.size,
            MediaRules.digest(bytes), java.util.Base64.getEncoder().encodeToString(bytes))
        MediaRules.validate(media)
        media
    }
    suspend fun add(noteId: String, media: BackupMedia) = database.withTransaction {
        val note = database.noteDao().findById(noteId)
        check(note != null && note.deletedAt == null) { "笔记已删除，无法添加附件" }
        val current = list(noteId)
        require(current.size < MediaRules.MAX_COUNT && current.sumOf { it.size.toLong() } + media.size <= MediaRules.MAX_NOTE_BYTES) {
            "每篇最多 10 个附件、合计 512 MiB"
        }
        MediaRules.insert(database.mediaDao(), noteId, media)
        database.noteDao().update(note.copy(updatedAt = maxOf(clock.nowMillis(), note.updatedAt + 1)))
    }
    /** Bounded-memory import; a cancelled/failed stream rolls back every chunk and row. */
    suspend fun importStream(noteId: String, uri: Uri, expectedVideo: Boolean) = withContext(Dispatchers.IO) {
        require(uri.scheme == "content")
        val resolver = context.contentResolver
        val mime = resolver.getType(uri).orEmpty().lowercase()
        require(mime in MediaRules.MIME_TYPES && mime.startsWith(if (expectedVideo) "video/" else "image/")) { "不支持的媒体格式" }
        var name = if (expectedVideo) "视频" else "图片"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0).orEmpty().take(200).ifBlank { name }
        }
        resolver.openInputStream(uri)?.use { input -> importStream(noteId, name, mime, input) }
            ?: error("无法读取文件")
    }
    internal suspend fun importStream(noteId: String, name: String, mime: String, input: java.io.InputStream) = database.withTransaction {
        require(mime in MediaRules.MIME_TYPES && name.isNotBlank() && name.length <= 200)
        val note = database.noteDao().findById(noteId)
        check(note != null && note.deletedAt == null) { "笔记已删除" }
        val dao = database.mediaDao()
        val current = list(noteId)
        require(current.size < MediaRules.MAX_COUNT) { "每篇最多 10 个附件" }
        val allowed = minOf(if (mime.startsWith("video/")) MediaRules.MAX_BYTES else MediaRules.LEGACY_MAX_BYTES,
            MediaRules.MAX_NOTE_BYTES - current.sumOf { it.size })
        val id = UUID.randomUUID().toString()
        val entity = NoteMediaEntity(id, noteId, name, mime, 0, "")
        dao.insert(entity)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var total = 0
        var index = 0
        while (true) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val chunk = ByteArray(MediaRules.CHUNK_SIZE)
            var used = 0
            while (used < chunk.size) {
                val n = input.read(chunk, used, chunk.size - used)
                if (n < 0) break
                if (n == 0) throw java.io.IOException("媒体读取无进展")
                used += n
            }
            if (used == 0) break
            require(total.toLong() + used <= allowed) { "视频最多 256 MiB，图片最多 8 MiB，每篇合计 512 MiB" }
            val bytes = if (used == chunk.size) chunk else chunk.copyOf(used)
            if (index == 0) require(MediaRules.matchesSignature(mime, bytes)) { "附件内容与格式不符" }
            digest.update(bytes)
            dao.insertChunks(listOf(MediaChunkEntity(id, index++, bytes)))
            total += used
        }
        require(total > 0) { "文件为空" }
        dao.update(entity.copy(size = total, sha256 = digest.digest().joinToString("") { "%02x".format(it) }))
        database.noteDao().update(note.copy(updatedAt = maxOf(clock.nowMillis(), note.updatedAt + 1)))
    }
    suspend fun remove(noteId: String, mediaId: String) = database.withTransaction {
        val note = database.noteDao().findById(noteId)
        check(note != null && note.deletedAt == null) { "笔记已删除，无法修改附件" }
        database.mediaDao().remove(noteId, mediaId)
        database.noteDao().update(note.copy(updatedAt = maxOf(clock.nowMillis(), note.updatedAt + 1)))
    }
}
