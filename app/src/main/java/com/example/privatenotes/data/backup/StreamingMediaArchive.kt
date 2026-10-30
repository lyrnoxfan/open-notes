package com.example.privatenotes.data.backup

import androidx.room.withTransaction
import com.example.privatenotes.data.local.AppDatabase
import com.example.privatenotes.data.media.*
import java.io.*
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext

/** Versioned bounded-memory media archive. PNMEDIA3 is authenticated/encrypted;
 * PNPLAIN3 is an explicitly unencrypted backup. Neither embeds media in Base64. */
class StreamingMediaArchive(private val database: AppDatabase) {
    companion object {
        private val MAGIC = "PNMEDIA3".toByteArray(Charsets.US_ASCII)
        private val PLAIN_MAGIC = "PNPLAIN3".toByteArray(Charsets.US_ASCII)
        fun isArchive(head: ByteArray): Boolean = head.take(8).toByteArray().let { it.contentEquals(MAGIC) || it.contentEquals(PLAIN_MAGIC) }
        fun isEncryptedArchive(head: ByteArray): Boolean = head.take(8).toByteArray().contentEquals(MAGIC)
        private const val MAX_METADATA = 16 * 1024 * 1024
        private const val MAX_TOTAL = Long.MAX_VALUE
        private val END = "END-PNMEDIA3".toByteArray()
    }
    suspend fun write(output: OutputStream, password: CharArray, appVersion: String, now: Long) {
        require(password.isEmpty() || password.size >= 8) { "加密备份密码至少 8 个字符" }
        database.withTransaction {
            val dao = database.backupDao()
            val media = database.mediaDao().all().sortedBy { it.id }
            require(media.fold(0L) { sum, item -> Math.addExact(sum, item.size) } <= MAX_TOTAL) { "备份媒体总量超出可表示范围" }
            val byNote = media.groupBy { it.noteId }
            val snapshot = BackupSnapshot(dao.allNotes(), dao.allTags(), dao.allNoteTags(), dao.allDeletionMarkers())
            val doc = snapshot.toDocument(now, appVersion).let { d -> d.copy(notes = d.notes.map { n ->
                n.copy(media = byNote[n.id].orEmpty().map { BackupMedia(it.id, it.name, it.mime, it.size, it.sha256, "") })
            }) }
            validateMetadata(doc)
            val metadata = BackupJson().encode(doc)
            require(metadata.size <= MAX_METADATA) { "备份文字元数据超过 16 MiB" }
            val writer = Records(output, password)
            writer.write(java.nio.ByteBuffer.allocate(4).putInt(metadata.size).array())
            metadata.asListChunks { writer.write(it) }
            for (note in doc.notes) for (m in note.media) {
                val hash = MessageDigest.getInstance("SHA-256")
                repeat(MediaRules.chunkCount(m.size)) { index ->
                    currentCoroutineContext().ensureActive()
                    val bytes = database.mediaDao().chunk(m.id, index)?.bytes ?: error("附件分块缺失")
                    require(bytes.size == MediaRules.chunkBytes(m.size, index))
                    hash.update(bytes); writer.write(bytes)
                }
                check(hex(hash.digest()) == m.sha256) { "附件校验失败，未完成备份" }
            }
            writer.write(END); output.flush()
        }
    }
    /** One SQLCipher transaction: authentication, missing data or storage failure rolls it all back. */
    suspend fun restore(input: InputStream, password: CharArray): MergeReport = database.withTransaction {
        val reader = RecordReader(input, password)
        val sizeBytes = reader.read(); require(sizeBytes.size == 4)
        val length = java.nio.ByteBuffer.wrap(sizeBytes).int
        require(length in 1..MAX_METADATA)
        val metadata = ByteArray(length)
        var at = 0
        while (at < length) {
            val bytes = reader.read()
            require(bytes.size == minOf(MediaRules.CHUNK_SIZE, length - at))
            bytes.copyInto(metadata, at); at += bytes.size
        }
        // Decode without treating metadata placeholders as embedded Base64 payloads.
        val doc = kotlinx.serialization.json.Json { ignoreUnknownKeys = false }
            .decodeFromString(BackupDocument.serializer(), metadata.toString(Charsets.UTF_8))
        validateMetadata(doc)
        val dao = database.backupDao()
        val plan = MergePlanner.plan(dao.allNotes(), dao.allTags(), dao.allNoteTags(), dao.allDeletionMarkers(), doc,
            database.mediaDao().all().groupBy { it.noteId })
        applyMergePlan(database, plan, doc, restoreMedia = false)
        val winners = plan.notesToUpsert.mapTo(HashSet()) { it.id }
        for (id in winners) database.mediaDao().clear(id)
        for (note in doc.notes) for (m in note.media) {
            val keep = note.id in winners
            if (keep) database.mediaDao().insert(NoteMediaEntity(m.id, note.id, m.name, m.mime, m.size, m.sha256))
            val hash = MessageDigest.getInstance("SHA-256")
            repeat(MediaRules.chunkCount(m.size)) { index ->
                currentCoroutineContext().ensureActive()
                val bytes = reader.read()
                require(bytes.size == MediaRules.chunkBytes(m.size, index))
                if (index == 0) require(MediaRules.matchesSignature(m.mime, bytes))
                hash.update(bytes)
                if (keep) database.mediaDao().insertChunks(listOf(MediaChunkEntity(m.id, index, bytes)))
            }
            check(hex(hash.digest()) == m.sha256) { "附件校验失败" }
        }
        check(reader.read().contentEquals(END)) { "备份未完整结束" }
        require(input.read() == -1) { "备份有额外内容" }
        plan.report
    }
    private fun validateMetadata(doc: BackupDocument) {
        require(doc.schemaVersion == 2)
        BackupJson().validate(doc.copy(notes = doc.notes.map { it.copy(media = emptyList()) }))
        val ids = HashSet<String>()
        var total = 0L
        for (note in doc.notes) {
            require(note.media.fold(0L) { sum, item -> Math.addExact(sum, item.size) } <= MediaRules.MAX_NOTE_BYTES)
            for (m in note.media) {
                require(m.id.matches(Regex("[A-Za-z0-9_-]{1,100}")) && ids.add(m.id))
                require(m.base64.isEmpty() && m.name.isNotBlank() && m.name.length <= 200)
                require(m.mime in MediaRules.MIME_TYPES && m.sha256.matches(Regex("[0-9a-f]{64}")))
                require(m.size in 1..MediaRules.MAX_BYTES)
                total = Math.addExact(total, m.size)
                require(total <= MAX_TOTAL)
            }
        }
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private inline fun ByteArray.asListChunks(block: (ByteArray) -> Unit) {
        var at = 0
        while (at < size) { val end = minOf(size, at + MediaRules.CHUNK_SIZE); block(copyOfRange(at, end)); at = end }
    }
    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, 210_000, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES").also { bytes.fill(0) }
        } finally { spec.clearPassword() }
    }
    private inner class Records(out: OutputStream, password: CharArray) {
        private val data = DataOutputStream(out)
        private val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        private val prefix = ByteArray(4).also(SecureRandom()::nextBytes)
        private val header = (if (password.isEmpty()) PLAIN_MAGIC else MAGIC) + salt + prefix
        private val key = if (password.isEmpty()) null else key(password, salt)
        private var index = 0L
        init { data.write(header) }
        fun write(bytes: ByteArray) {
            require(bytes.size in 1..MediaRules.CHUNK_SIZE)
            if (key == null) { data.writeInt(bytes.size); data.write(bytes); return }
            val nonce = java.nio.ByteBuffer.allocate(12).put(prefix).putLong(index).array()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
            cipher.updateAAD(header + java.nio.ByteBuffer.allocate(8).putLong(index++).array())
            val encrypted = cipher.doFinal(bytes)
            data.writeInt(encrypted.size); data.write(encrypted)
        }
    }
    private inner class RecordReader(input: InputStream, password: CharArray) {
        private val data = DataInputStream(input)
        private val header = ByteArray(MAGIC.size + 20).also(data::readFully)
        private val key: SecretKeySpec?
        private val prefix: ByteArray
        private var index = 0L
        init {
            require(isArchive(header)) { "不是流式媒体备份" }
            key = if (isEncryptedArchive(header)) {
                require(password.isNotEmpty()) { "此备份需要密码" }
                key(password, header.copyOfRange(MAGIC.size, MAGIC.size + 16))
            } else null
            prefix = header.copyOfRange(MAGIC.size + 16, header.size)
        }
        fun read(): ByteArray {
            val size = data.readInt()
            if (key == null) {
                require(size in 1..MediaRules.CHUNK_SIZE)
                return ByteArray(size).also(data::readFully)
            }
            require(size in 17..MediaRules.CHUNK_SIZE + 16)
            val encrypted = ByteArray(size).also(data::readFully)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128,
                java.nio.ByteBuffer.allocate(12).put(prefix).putLong(index).array()))
            cipher.updateAAD(header + java.nio.ByteBuffer.allocate(8).putLong(index++).array())
            return cipher.doFinal(encrypted)
        }
    }
}
