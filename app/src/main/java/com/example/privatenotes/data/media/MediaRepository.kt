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
    suspend fun imagePreview(media: NoteMediaEntity, maxDimension: Int = 1800): android.graphics.Bitmap = withContext(Dispatchers.IO) {
        require(media.mime.startsWith("image/") && media.size in 1..MediaRules.MAX_BYTES && maxDimension > 0)
        val dao = database.mediaDao()
        ensureChunkIntegrity(media)
        require(MediaRules.matchesSignature(media.mime, checkedChunk(dao, media, 0))) { "媒体格式校验失败" }
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ChunkInputStream(media, dao).use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片无法解码" }
        var sample = 1
        while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) sample *= 2
        val bitmap = ChunkInputStream(media, dao).use {
            android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        }
        bitmap ?: error("图片无法解码")
    }

    private class ChunkInputStream(
        private val media: NoteMediaEntity, private val dao: MediaDao,
    ) : java.io.InputStream() {
        private val size = media.size
        private val count = MediaRules.chunkCount(size)

        private var position = 0L
        private var chunkIndex = -1
        private var chunk = ByteArray(0)
        override fun read(): Int {
            if (position >= size) return -1
            loadChunk()
            return chunk[(position++ % MediaRules.CHUNK_SIZE).toInt()].toInt() and 0xff
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
            if (length == 0) return 0
            if (position >= size) return -1
            var copied = 0
            while (copied < length && position < size) {
                loadChunk()
                val within = (position % MediaRules.CHUNK_SIZE).toInt()
                val n = minOf((length - copied).toLong(), (chunk.size - within).toLong(), size - position).toInt()
                chunk.copyInto(buffer, offset + copied, within, within + n)
                copied += n; position += n
            }
            return copied
        }
        private fun loadChunk() {
            val wanted = (position / MediaRules.CHUNK_SIZE).toInt()
            if (wanted == chunkIndex) return
            require(wanted < count)
            chunk = checkedChunk(dao, media, wanted)
            require(chunk.size == MediaRules.chunkBytes(size, wanted))
            chunkIndex = wanted
        }
    }
    /** Playback starts with the requested chunk, not a full-file hash pass. Payloads are
     * copied into the encrypted app database at import; original provider URIs are never stored. */
    suspend fun videoSource(media: NoteMediaEntity): android.media.MediaDataSource = withContext(Dispatchers.IO) {
        require(media.mime.startsWith("video/") && media.size in 1..MediaRules.MAX_BYTES)
        ensureChunkIntegrity(media)
        val dao = database.mediaDao()
        ChunkedMediaSource(media.size) { position -> checkedChunk(dao, media, position) }
    }

    /** v2 media get one whole-file verification before hashes are committed. New imports and
     * restores already have chunk hashes, so opening them never scans the payload in advance. */
    private suspend fun ensureChunkIntegrity(media: NoteMediaEntity) {
        val dao = database.mediaDao()
        check(dao.find(media.id) == media) { "媒体已更改或删除" }
        require(dao.chunkCount(media.id) == MediaRules.chunkCount(media.size)) { "媒体分块缺失" }
        if (dao.unhashedCount(media.id) == 0) return
        database.withTransaction {
            check(dao.find(media.id) == media)
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            repeat(MediaRules.chunkCount(media.size)) { position ->
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val part = dao.chunk(media.id, position) ?: error("媒体分块缺失")
                require(part.bytes.size == MediaRules.chunkBytes(media.size, position))
                digest.update(part.bytes)
                dao.setChunkHash(media.id, position, MediaRules.digest(part.bytes))
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == media.sha256) { "媒体校验失败" }
        }
    }

    private companion object {
        fun checkedChunk(dao: MediaDao, media: NoteMediaEntity, position: Int): ByteArray {
            val part = dao.chunk(media.id, position) ?: error("媒体分块缺失")
            require(part.bytes.size == MediaRules.chunkBytes(media.size, position))
            check(part.sha256.length == 64 && MediaRules.digest(part.bytes) == part.sha256) { "媒体分块校验失败" }
            return part.bytes
        }
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
        require(media.size <= MediaRules.MAX_NOTE_BYTES - MediaRules.noteBytes(current.map { it.size })) {
            "此笔记的媒体存储空间已用完"
        }
        MediaRules.insert(database.mediaDao(), noteId, media)
        database.noteDao().update(note.copy(updatedAt = maxOf(clock.nowMillis(), note.updatedAt + 1)))
    }
    /** Bounded-memory import; a cancelled/failed stream rolls back every chunk and row. */
    suspend fun importStream(noteId: String, uri: Uri, expectedVideo: Boolean,
        title: String? = null, bodyForId: ((String) -> String)? = null) = withContext(Dispatchers.IO) {
        require(uri.scheme == "content")
        val resolver = context.contentResolver
        val mime = resolver.getType(uri).orEmpty().lowercase()
        require(mime in MediaRules.MIME_TYPES && mime.startsWith(if (expectedVideo) "video/" else "image/")) { "不支持的媒体格式" }
        var name = if (expectedVideo) "视频" else "图片"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0).orEmpty().take(200).ifBlank { name }
        }
        resolver.openInputStream(uri)?.use { input -> importStream(noteId, name, mime, input, title, bodyForId) }
            ?: error("无法读取文件")
    }
    internal suspend fun importStream(noteId: String, name: String, mime: String, input: java.io.InputStream,
        title: String? = null, bodyForId: ((String) -> String)? = null): String = database.withTransaction {
        require(mime in MediaRules.MIME_TYPES && name.isNotBlank() && name.length <= 200)
        val note = database.noteDao().findById(noteId)
        check(note != null && note.deletedAt == null) { "笔记已删除" }
        val dao = database.mediaDao()
        val current = list(noteId)
        val allowed = minOf(MediaRules.MAX_BYTES, MediaRules.MAX_NOTE_BYTES - MediaRules.noteBytes(current.map { it.size }))
        val id = UUID.randomUUID().toString()
        val entity = NoteMediaEntity(id, noteId, name, mime, 0, "")
        dao.insert(entity)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var total = 0L
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
            require(total + used <= allowed) { "媒体长度超出当前分块格式的可表示范围" }
            val bytes = if (used == chunk.size) chunk else chunk.copyOf(used)
            if (index == 0) require(MediaRules.matchesSignature(mime, bytes)) { "附件内容与格式不符" }
            digest.update(bytes)
            dao.insertChunks(listOf(MediaChunkEntity(id, index++, bytes)))
            total += used
        }
        require(total > 0) { "文件为空" }
        dao.update(entity.copy(size = total, sha256 = digest.digest().joinToString("") { "%02x".format(it) }))
        // Commit bytes and their note reference together. A failed transform rolls back chunks too.
        database.noteDao().update(note.copy(title = title ?: note.title,
            content = bodyForId?.invoke(id) ?: note.content,
            updatedAt = maxOf(clock.nowMillis(), note.updatedAt + 1)))
        id
    }
    suspend fun removeInline(noteId: String, mediaId: String, title: String, body: () -> String) = database.withTransaction {
        val note = database.noteDao().findById(noteId)
        check(note != null && note.deletedAt == null) { "笔记已删除，无法修改媒体" }
        database.mediaDao().remove(noteId, mediaId)
        database.noteDao().update(note.copy(title = title, content = body(),
            updatedAt = maxOf(clock.nowMillis(), note.updatedAt + 1)))
    }
    /** A native rich-text selection can erase several objects at once; commit the selected body
     * and every corresponding row deletion atomically, including interrupted saves. */
    suspend fun removeInlineSelection(noteId: String, ids: Set<String>, title: String, body: String) = database.withTransaction {
        val note = database.noteDao().findById(noteId)
        check(note != null && note.deletedAt == null) { "笔记已删除，无法修改媒体" }
        ids.forEach { database.mediaDao().remove(noteId, it) }
        database.noteDao().update(note.copy(title = title, content = body,
            updatedAt = maxOf(clock.nowMillis(), note.updatedAt + 1)))
    }
    suspend fun remove(noteId: String, mediaId: String) = database.withTransaction {
        val note = database.noteDao().findById(noteId)
        check(note != null && note.deletedAt == null) { "笔记已删除，无法修改附件" }
        database.mediaDao().remove(noteId, mediaId)
        database.noteDao().update(note.copy(updatedAt = maxOf(clock.nowMillis(), note.updatedAt + 1)))
    }
}
