package com.example.privatenotes.data.backup

import com.example.privatenotes.data.media.BackupMedia
import com.example.privatenotes.data.media.MediaRules
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.FilterInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/** Stages schema-2 media payloads to disk; no database is touched while parsing/authenticating. */
internal class LegacyJsonMediaStager(private val maxArchiveBytes: Long? = null) {
    data class StagedMedia(val file: File, val decodedBytes: Long, val sha256: String, val prefix: ByteArray)
    data class StagedDocument(val document: BackupDocument, val media: List<StagedMedia>, val directory: File) : AutoCloseable {
        override fun close() { directory.deleteRecursively() }
    }

    fun stage(input: InputStream, json: BackupJson, parent: File): StagedDocument {
        val dir = File(parent, "legacy-stage-${System.nanoTime()}")
        if (!dir.mkdirs()) throw BackupException(BackupErrorKind.STORAGE, "Could not create temporary import storage")
        try {
            val staged = mutableListOf<StagedMedia>()
            val sanitized = File(dir, "metadata.json")
            // Keep enough free space for the sanitized metadata plus decoded media staging files.
            // This is a runtime storage bound, not the old fixed 64 MiB JSON cap.
            val archiveLimit = minOf(maxArchiveBytes ?: Long.MAX_VALUE, parent.usableSpace / 2)
            if (archiveLimit <= 0) throw BackupException(BackupErrorKind.STORAGE, "Not enough free space to stage this backup")
            val bounded = object : FilterInputStream(input) {
                var count = 0L
                private fun add(n: Int): Int {
                    if (n > 0) { if (count > archiveLimit - n) throw BackupException(BackupErrorKind.CORRUPT_FILE, "Legacy backup exceeds available staging storage"); count += n }
                    return n
                }
                override fun read(): Int = add(super.read())
                override fun read(b: ByteArray, off: Int, len: Int): Int = add(super.read(b, off, len))
            }
            InputStreamReader(bounded, StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)).use { reader ->
                BufferedWriter(OutputStreamWriter(FileOutputStream(sanitized), StandardCharsets.UTF_8)).use { writer ->
                    Parser(BufferedReader(reader), writer, dir, staged).parse()
                }
            }
            val document = FileInputStream(sanitized).use { json.decodeStreamingMetadata(it) }
            val actualMedia = document.notes.flatMap { it.media }
            if (actualMedia.size != staged.size) throw BackupException(BackupErrorKind.INVALID_FORMAT, "Legacy backup media payloads do not match metadata")
            actualMedia.zip(staged).forEach { (meta, payload) -> validateMedia(meta, payload) }
            return StagedDocument(document, staged, dir)
        } catch (e: Throwable) {
            dir.deleteRecursively()
            throw e
        }
    }

    private fun validateMedia(meta: BackupMedia, data: StagedMedia) {
        try {
            require(meta.id.matches(Regex("[A-Za-z0-9_-]{1,100}")))
            require(meta.name.isNotBlank() && meta.name.length <= 200)
            require(meta.mime in MediaRules.MIME_TYPES)
            require(meta.size in 1..MediaRules.MAX_BYTES && meta.size == data.decodedBytes)
            require(meta.sha256.matches(Regex("[0-9a-f]{64}")) && meta.sha256 == data.sha256)
            require(MediaRules.matchesSignature(meta.mime, data.prefix))
        } catch (e: Exception) {
            throw BackupException(BackupErrorKind.INVALID_FORMAT, "Invalid or damaged legacy media", e)
        }
    }

    private class Parser(private val r: BufferedReader, private val w: BufferedWriter, private val dir: File, private val staged: MutableList<StagedMedia>) {
        private var next = -2
        private fun peek(): Int { if (next == -2) next = r.read(); return next }
        private fun take(): Int { val c = peek(); next = -2; return c }
        private fun emit(c: Int) { if (c >= 0) w.write(c) }
        private fun whitespace() { while (peek() in listOf(0x20, 0x09, 0x0a, 0x0d)) emit(take()) }
        fun parse() { whitespace(); if (peek() == 0xfeff) take(); value(); whitespace(); if (peek() >= 0) error("Trailing JSON data") }
        private fun value() {
            whitespace()
            when (peek()) {
                '{'.code -> obj()
                '['.code -> array()
                '"'.code -> string(null)
                -1 -> error("Unexpected end of JSON")
                else -> { while (peek() >= 0 && peek().toChar() !in ",]} \t\r\n") emit(take()) }
            }
        }
        private fun obj() {
            emit(take()); whitespace(); if (peek() == '}'.code) { emit(take()); return }
            while (true) {
                whitespace(); if (peek() != '"'.code) error("Expected object key")
                val key = string(StringBuilder())
                whitespace(); if (take() != ':'.code) error("Expected colon"); emit(':'.code)
                whitespace()
                if (key == "base64" && peek() == '"'.code) base64Value() else value()
                whitespace(); when (take()) { ','.code -> emit(','.code); '}'.code -> { emit('}'.code); return }; else -> error("Expected object separator") }
            }
        }
        private fun array() {
            emit(take()); whitespace(); if (peek() == ']'.code) { emit(take()); return }
            while (true) { value(); whitespace(); when (take()) { ','.code -> emit(','.code); ']'.code -> { emit(']'.code); return }; else -> error("Expected array separator") } }
        }
        /** Copies a JSON string unchanged; when capture is non-null, returns its decoded value. */
        private fun string(capture: StringBuilder?): String {
            if (take() != '"'.code) error("Expected string")
            emit('"'.code)
            while (true) {
                val c = take(); if (c < 0) error("Unterminated string")
                emit(c)
                if (c == '"'.code) break
                if (c == '\\'.code) {
                    val escaped = take(); if (escaped < 0) error("Bad escape"); emit(escaped)
                    if (capture != null) capture.append(when (escaped.toChar()) { '"' -> '"'; '\\' -> '\\'; '/' -> '/'; 'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; 'u' -> { val hex = CharArray(4); repeat(4) { val h = take(); if (h < 0) error("Bad unicode escape"); emit(h); hex[it] = h.toChar() }; hex.concatToString().toInt(16).toChar() }; else -> error("Bad escape") })
                } else if (capture != null) capture.append(c.toChar())
            }
            return capture?.toString().orEmpty()
        }
        private fun base64Value() {
            take() // opening quote
            emit('"'.code); emit('"'.code) // sanitized JSON contains an empty payload
            val file = File(dir, "media-${staged.size}.bin")
            val digest = MessageDigest.getInstance("SHA-256")
            val prefix = ByteArray(16); var prefixSize = 0; var count = 0L; val quartet = StringBuilder(4); var padded = false
            FileOutputStream(file).use { out ->
                fun flushQuartet() {
                    val text = quartet.toString()
                    val decoded = try { Base64.getDecoder().decode(text) } catch (e: IllegalArgumentException) { throw BackupException(BackupErrorKind.INVALID_FORMAT, "Malformed legacy media Base64", e) }
                    if (text.contains('=') && peek() != '"'.code) padded = true
                    if (padded && peek() != '"'.code) throw BackupException(BackupErrorKind.INVALID_FORMAT, "Data after Base64 padding")
                    if (decoded.isNotEmpty()) {
                        if (count > MediaRules.MAX_BYTES - decoded.size) throw BackupException(BackupErrorKind.INVALID_FORMAT, "Legacy media exceeds SQLite's representable size")
                        val copy = minOf(prefix.size - prefixSize, decoded.size); if (copy > 0) { decoded.copyInto(prefix, prefixSize, 0, copy); prefixSize += copy }
                        digest.update(decoded); out.write(decoded); count += decoded.size
                    }
                    quartet.setLength(0)
                }
                while (true) {
                    val c = take(); if (c < 0) error("Unterminated Base64 value")
                    if (c == '"'.code) break
                    val ch = c.toChar(); if (!(ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '+' || ch == '/' || ch == '=')) throw BackupException(BackupErrorKind.INVALID_FORMAT, "Invalid Base64 character")
                    if (padded) throw BackupException(BackupErrorKind.INVALID_FORMAT, "Data after Base64 padding")
                    quartet.append(ch); if (quartet.length == 4) flushQuartet()
                }
                if (quartet.isNotEmpty()) throw BackupException(BackupErrorKind.INVALID_FORMAT, "Incomplete Base64 value")
            }
            staged += StagedMedia(file, count, digest.digest().joinToString("") { "%02x".format(it) }, prefix.copyOf(prefixSize))
        }
    }
}
