package com.example.privatenotes.data.backup

import com.example.privatenotes.data.media.BackupMedia
import java.io.File
import java.io.ByteArrayInputStream
import java.util.Base64
import java.io.InputStream
import java.io.BufferedInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class BackupJsonStreamingTest {
    @Test fun `streamed JSON retains legacy schema and parses from Reader stream`() {
        val bytes = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        val media = BackupMedia("media", "m.png", "image/png", bytes.size.toLong(),
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, "")
        val document = BackupDocument(BackupDocument.FORMAT, 2, "test", 1_700_000_000_000L,
            listOf(BackupNote("note", "title", "body", 1, 2, media = listOf(media))),
            emptyList(), emptyList(), emptyList())
        val file = File.createTempFile("legacy-json-small", ".json")
        try {
            file.outputStream().writer(Charsets.UTF_8).use { writer ->
                BackupJson().writeStreaming(document, writer) { ByteArrayInputStream(bytes) }
            }
            val decoded = file.inputStream().use { BackupJson().decode(it) }
            assertEquals(Base64.getEncoder().encodeToString(bytes), decoded.notes.single().media.single().base64)
            assertEquals(document.notes.single().copy(media = decoded.notes.single().media), decoded.notes.single())
        } finally { file.delete() }
    }
    @Test fun `streamed JSON carries one media above 8 MiB and more than 40 MiB total`() {
        val sizes = listOf(9L * MIB, 32L * MIB)
        val entries = sizes.mapIndexed { index, size ->
            BackupMedia("media$index", "m$index.png", "image/png", size, digest(size), "")
        }
        val doc = BackupDocument(BackupDocument.FORMAT, 2, "test", 1_700_000_000_000L,
            listOf(BackupNote("n1", "one", "body", 1, 2, media = entries.take(1)),
                BackupNote("n2", "two", "body", 1, 2, media = entries.drop(1))),
            emptyList(), emptyList(), emptyList())
        val file = File.createTempFile("legacy-json-stream", ".json")
        try {
            file.outputStream().writer(Charsets.UTF_8).use { writer ->
                BackupJson().writeStreaming(doc, writer) { media -> GeneratedInput(sizes[media.id.last() - '0']) }
            }
            val expectedBase64 = sizes.sumOf { ((it + 2) / 3) * 4 }
            assertTrue(file.length() > 40L * MIB)
            RandomAccessFile(file, "r").use { output ->
                val head = ByteArray(10)
                output.readFully(head)
                assertEquals("{\"format\":", String(head, Charsets.UTF_8))
                output.seek(output.length() - 1)
                assertEquals('}'.code, output.read())
            }
            // The Base64 payload alone is this long; JSON metadata adds further bytes.
            assertTrue("large output contains complete Base64 media", file.length() > expectedBase64)
        } finally { file.delete() }
    }

    private fun digest(size: Long): String {
        val md = MessageDigest.getInstance("SHA-256")
        val input = GeneratedInput(size)
        val buffer = ByteArray(64 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private class GeneratedInput(private val size: Long) : InputStream() {
        private var position = 0L
        private val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        override fun read(): Int {
            if (position >= size) return -1
            val result = if (position < signature.size) signature[position.toInt()].toInt() and 255 else 0x42
            position++
            return result
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (position >= size) return -1
            val n = minOf(length.toLong(), size - position).toInt()
            for (i in 0 until n) bytes[offset + i] = if (position + i < signature.size) signature[(position + i).toInt()] else 0x42
            position += n
            return n
        }
    }
    companion object { const val MIB = 1024L * 1024 }
}
