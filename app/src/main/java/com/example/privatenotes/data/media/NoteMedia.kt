package com.example.privatenotes.data.media

import androidx.room.*
import com.example.privatenotes.data.local.NoteEntity
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64

@Entity(tableName = "note_media", foreignKeys = [ForeignKey(entity = NoteEntity::class,
    parentColumns = ["id"], childColumns = ["noteId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("noteId")])
data class NoteMediaEntity(@PrimaryKey val id: String, val noteId: String,
    val name: String, val mime: String, val size: Int, val sha256: String)

// Never put a video in one CursorWindow: all payload rows are at most 256 KiB.
@Entity(tableName = "media_chunks", primaryKeys = ["mediaId", "position"],
    foreignKeys = [ForeignKey(entity = NoteMediaEntity::class, parentColumns = ["id"],
        childColumns = ["mediaId"], onDelete = ForeignKey.CASCADE)])
data class MediaChunkEntity(val mediaId: String, val position: Int, val bytes: ByteArray)

@Dao
interface MediaDao {
    @Query("SELECT * FROM note_media WHERE noteId = :noteId ORDER BY id")
    suspend fun forNote(noteId: String): List<NoteMediaEntity>
    @Query("SELECT * FROM note_media ORDER BY id")
    suspend fun all(): List<NoteMediaEntity>
    @Query("SELECT * FROM media_chunks WHERE mediaId = :id ORDER BY position")
    suspend fun chunks(id: String): List<MediaChunkEntity>
    @Query("SELECT * FROM media_chunks WHERE mediaId = :id AND position = :position")
    fun chunk(id: String, position: Int): MediaChunkEntity?
    @Update suspend fun update(media: NoteMediaEntity)
    @Insert suspend fun insert(media: NoteMediaEntity)
    @Insert suspend fun insertChunks(chunks: List<MediaChunkEntity>)
    @Query("DELETE FROM note_media WHERE id = :id AND noteId = :noteId")
    suspend fun remove(noteId: String, id: String)
    @Query("DELETE FROM note_media WHERE noteId = :noteId")
    suspend fun clear(noteId: String)
}

@Serializable
data class BackupMedia(val id: String, val name: String, val mime: String, val size: Int,
    val sha256: String, val base64: String)

object MediaRules {
    const val CHUNK_SIZE = 256 * 1024
    const val LEGACY_MAX_BYTES = 8 * 1024 * 1024
    const val MAX_BYTES = 256 * 1024 * 1024
    const val MAX_NOTE_BYTES = 512 * 1024 * 1024
    const val MAX_COUNT = 10
    val MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif", "video/mp4", "video/webm", "video/3gpp")
    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    fun readBounded(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(out.size() + count <= LEGACY_MAX_BYTES) { "单个图片或视频不能超过 8 MiB" }
            out.write(buffer, 0, count)
        }
        require(out.size() > 0) { "文件为空" }
        return out.toByteArray()
    }
    fun validate(media: BackupMedia): ByteArray {
        require(media.id.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "无效附件标识" }
        require(media.name.isNotBlank() && media.name.length <= 200) { "附件名过长或为空" }
        require(media.mime in MIME_TYPES) { "不支持的图片或视频格式" }
        require(media.size in 1..LEGACY_MAX_BYTES && media.base64.length <= (LEGACY_MAX_BYTES + 2) / 3 * 4) { "附件过大或为空" }
        val bytes = Base64.getDecoder().decode(media.base64)
        require(bytes.size == media.size && digest(bytes) == media.sha256) { "附件校验失败" }
        require(matchesSignature(media.mime, bytes)) { "附件内容与格式不符" }
        return bytes
    }
    fun matchesSignature(mime: String, b: ByteArray): Boolean {
        fun ascii(start: Int, text: String) = b.size >= start + text.length &&
            b.copyOfRange(start, start + text.length).contentEquals(text.toByteArray(Charsets.US_ASCII))
        return when (mime) {
            "image/jpeg" -> b.size >= 3 && b[0] == 0xff.toByte() && b[1] == 0xd8.toByte() && b[2] == 0xff.toByte()
            "image/png" -> b.size >= 8 && b.take(8).toByteArray().contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10))
            "image/gif" -> ascii(0, "GIF87a") || ascii(0, "GIF89a")
            "image/webp" -> ascii(0, "RIFF") && ascii(8, "WEBP")
            "video/mp4", "video/3gpp" -> ascii(4, "ftyp")
            "video/webm" -> b.size >= 4 && b.take(4).toByteArray().contentEquals(byteArrayOf(0x1a,0x45,0xdf.toByte(),0xa3.toByte()))
            else -> false
        }
    }
    fun encode(entity: NoteMediaEntity, bytes: ByteArray) = BackupMedia(entity.id, entity.name,
        entity.mime, entity.size, entity.sha256, Base64.getEncoder().encodeToString(bytes))
    suspend fun read(dao: MediaDao, media: NoteMediaEntity): ByteArray {
        require(media.size in 1..LEGACY_MAX_BYTES) { "大视频必须通过流式接口读取" }
        val chunks = dao.chunks(media.id)
        require(media.size in 1..LEGACY_MAX_BYTES && chunks.size == (media.size + CHUNK_SIZE - 1) / CHUNK_SIZE) { "附件大小损坏" }
        require(chunks.map { it.position } == chunks.indices.toList()) { "附件分块损坏" }
        require(chunks.withIndex().all { (index, chunk) ->
            chunk.bytes.size == minOf(CHUNK_SIZE, media.size - index * CHUNK_SIZE)
        }) { "附件分块长度损坏" }
        val bytes = ByteArrayOutputStream().apply { chunks.forEach { write(it.bytes) } }.toByteArray()
        require(bytes.size == media.size && digest(bytes) == media.sha256) { "附件已损坏" }
        return bytes
    }
    suspend fun insert(dao: MediaDao, noteId: String, media: BackupMedia) {
        val bytes = validate(media)
        dao.insert(NoteMediaEntity(media.id, noteId, media.name, media.mime, media.size, media.sha256))
        var offset = 0
        while (offset < bytes.size) {
            dao.insertChunks(listOf(MediaChunkEntity(media.id, offset / CHUNK_SIZE,
                bytes.copyOfRange(offset, minOf(offset + CHUNK_SIZE, bytes.size)))))
            offset += CHUNK_SIZE
        }
    }
}
