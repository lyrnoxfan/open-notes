package com.example.privatenotes.data.backup

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.builtins.ListSerializer
import java.io.InputStream
import java.io.OutputStream
import java.io.Writer
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

/** Structural limits, so a malicious or truncated file cannot exhaust the device. */
object BackupLimits {
    const val MAX_FILE_BYTES: Long = 64L * 1024 * 1024
    /** Legacy schema-1/2 JSON and PNB imports retain their historical safe media limits. */
    const val MAX_LEGACY_MEDIA_TOTAL_BYTES: Long = 40L * 1024 * 1024
    const val MAX_NOTES = 200_000
    const val MAX_TAGS = 50_000
    const val MAX_NOTE_TAGS = 1_000_000
    const val MAX_DELETION_MARKERS = 200_000
    const val MAX_ID_LENGTH = 200
    const val MAX_TITLE_LENGTH = 1_000_000
    const val MAX_CONTENT_LENGTH = 16_000_000
    const val MAX_TAG_NAME_LENGTH = 128
    const val MAX_APP_VERSION_LENGTH = 100

    /** 9999-12-31T23:59:59.999Z. Anything above this is corruption, not a clock skew. */
    const val MAX_TIMESTAMP = 253_402_300_799_999L
}

/**
 * Reads and writes the JSON backup body.
 *
 * Decoding is deliberately forgiving about unknown keys (a newer app may add fields) and
 * deliberately strict about everything else: format id, schema version, duplicate identities,
 * timestamps and referential integrity are all checked before a single row is written.
 */
class BackupJson(
    private val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    },
) {

    fun encode(document: BackupDocument): ByteArray =
        json.encodeToString(BackupDocument.serializer(), document).toByteArray(Charsets.UTF_8)

    /**
     * Writes the legacy JSON shape without materializing media as Base64 strings. The document
     * carries metadata with empty payload placeholders; [openMedia] supplies each original byte
     * stream in document order. The emitted JSON retains the existing schema-2 field layout and serialization format.
     */
    fun writeStreaming(document: BackupDocument, writer: Writer, openMedia: (com.example.privatenotes.data.media.BackupMedia) -> InputStream) {
        validate(document.copy(notes = document.notes.map { it.copy(media = emptyList()) }))
        val mediaIds = HashSet<String>()
        for (note in document.notes) {
            if (document.schemaVersion == 1 && note.media.isNotEmpty()) throw invalid("Schema 1 cannot contain media")
            try { com.example.privatenotes.data.media.MediaRules.noteBytes(note.media.map { it.size }) }
            catch (_: IllegalArgumentException) { throw invalid("Too much media in note") }
            note.media.forEach { media ->
                validateStreamingMediaMetadata(media)
                if (!mediaIds.add(media.id)) throw invalid("Duplicate media id")
            }
        }
        writer.write("{\"format\":")
        writer.write(json.encodeToString(String.serializer(), document.format))
        writer.write(",\"schemaVersion\":${document.schemaVersion},\"appVersion\":")
        writer.write(json.encodeToString(String.serializer(), document.appVersion))
        writer.write(",\"exportedAt\":${document.exportedAt},\"notes\":[")
        document.notes.forEachIndexed { index, note ->
            if (index > 0) writer.write(','.code)
            val plain = json.encodeToString(BackupNote.serializer(), note.copy(media = emptyList()))
            writer.write(plain.dropLast(1))
            writer.write(",\"media\":[")
            note.media.forEachIndexed { mediaIndex, media ->
                if (mediaIndex > 0) writer.write(','.code)
                validateStreamingMediaMetadata(media)
                val metadata = json.encodeToString(com.example.privatenotes.data.media.BackupMedia.serializer(), media.copy(base64 = ""))
                val key = metadata.lastIndexOf("\"base64\"")
                require(key >= 0) { "Missing base64 property" }
                val valueStart = metadata.indexOf('\"', metadata.indexOf(':', key) + 1)
                val valueEnd = metadata.indexOf('\"', valueStart + 1)
                require(valueStart >= 0 && valueEnd == valueStart + 1) { "Invalid base64 placeholder" }
                writer.write(metadata.substring(0, valueStart + 1))
                openMedia(media).use { input -> writeBase64AndVerify(input, writer, media) }
                writer.write(metadata.substring(valueEnd))
            }
            writer.write("]}")
        }
        writer.write("],\"tags\":")
        writer.write(json.encodeToString(ListSerializer(BackupTag.serializer()), document.tags))
        writer.write(",\"noteTags\":")
        writer.write(json.encodeToString(ListSerializer(BackupNoteTag.serializer()), document.noteTags))
        writer.write(",\"deletionMarkers\":")
        writer.write(json.encodeToString(ListSerializer(BackupDeletionMarker.serializer()), document.deletionMarkers))
        writer.write("}")
        writer.flush()
    }

    /** Decode directly from a stream to avoid a second full-file byte array. */
    @kotlinx.serialization.ExperimentalSerializationApi
    fun decode(input: InputStream): BackupDocument {
        val document = try {
            json.decodeFromStream(BackupDocument.serializer(), input)
        } catch (e: SerializationException) {
            throw BackupException(BackupErrorKind.INVALID_FORMAT, "Backup JSON is malformed", e)
        } catch (e: IllegalArgumentException) {
            throw BackupException(BackupErrorKind.INVALID_FORMAT, "Backup JSON is malformed", e)
        }
        validate(document)
        return document
    }

    /** Decode staged legacy JSON whose Base64 payloads were replaced with empty strings. */
    @kotlinx.serialization.ExperimentalSerializationApi
    fun decodeStreamingMetadata(input: InputStream): BackupDocument {
        val document = try { json.decodeFromStream(BackupDocument.serializer(), input) }
        catch (e: SerializationException) { throw BackupException(BackupErrorKind.INVALID_FORMAT, "Backup JSON is malformed", e) }
        catch (e: IllegalArgumentException) { throw BackupException(BackupErrorKind.INVALID_FORMAT, "Backup JSON is malformed", e) }
        // Validate all ordinary schema, row-count, identity and reference constraints before any DB write.
        validate(document.copy(notes = document.notes.map { it.copy(media = emptyList()) }))
        if (document.schemaVersion == 1 && document.notes.any { it.media.isNotEmpty() }) throw invalid("Schema 1 cannot contain media")
        val ids = HashSet<String>()
        for (note in document.notes) {
            try { com.example.privatenotes.data.media.MediaRules.noteBytes(note.media.map { it.size }) }
            catch (_: IllegalArgumentException) { throw invalid("Media exceeds the representable SQLite size") }
            for (media in note.media) {
                validateStreamingMediaMetadata(media)
                if (!ids.add(media.id)) throw invalid("Duplicate media id")
            }
        }
        return document
    }

    fun validateStagedMediaMetadata(media: com.example.privatenotes.data.media.BackupMedia) = validateStreamingMediaMetadata(media)

    fun validateStagedMetadata(document: BackupDocument) {
        validate(document.copy(notes = document.notes.map { it.copy(media = emptyList()) }))
        if (document.schemaVersion == 1 && document.notes.any { it.media.isNotEmpty() }) throw invalid("Schema 1 cannot contain media")
        val ids = HashSet<String>()
        for (note in document.notes) {
            try { com.example.privatenotes.data.media.MediaRules.noteBytes(note.media.map { it.size }) }
            catch (_: IllegalArgumentException) { throw invalid("Media exceeds SQLite representable size") }
            for (media in note.media) {
                validateStreamingMediaMetadata(media)
                if (!ids.add(media.id)) throw invalid("Duplicate media id")
            }
        }
    }

    private fun validateStreamingMediaMetadata(media: com.example.privatenotes.data.media.BackupMedia) {
        require(media.id.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "无效附件标识" }
        require(media.name.isNotBlank() && media.name.length <= 200) { "附件名过长或为空" }
        require(media.mime in com.example.privatenotes.data.media.MediaRules.MIME_TYPES) { "不支持的图片或视频格式" }
        require(media.size in 1..com.example.privatenotes.data.media.MediaRules.MAX_BYTES) { "附件长度无效" }
        require(media.sha256.matches(Regex("[0-9a-f]{64}"))) { "附件摘要无效" }
    }

    private fun writeBase64AndVerify(input: InputStream, writer: Writer, media: com.example.privatenotes.data.media.BackupMedia) {
        val digest = MessageDigest.getInstance("SHA-256")
        val prefix = ByteArray(16)
        var prefixSize = 0
        var count = 0L
        val ascii = object : OutputStream() {
            override fun write(value: Int) { writer.write(value and 0xff) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                val chars = CharArray(length)
                for (i in 0 until length) chars[i] = (bytes[offset + i].toInt() and 0xff).toChar()
                writer.write(chars, 0, chars.size)
            }
            override fun close() { writer.flush() }
        }
        val encoder = Base64.getEncoder().wrap(ascii)
        try {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                if (prefixSize < prefix.size) {
                    val copy = minOf(prefix.size - prefixSize, n)
                    buffer.copyInto(prefix, prefixSize, 0, copy)
                    prefixSize += copy
                }
                count = Math.addExact(count, n.toLong())
                digest.update(buffer, 0, n)
                encoder.write(buffer, 0, n)
            }
            encoder.close()
        } catch (failure: Throwable) {
            try { encoder.close() } catch (_: Throwable) { }
            throw failure
        }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        require(count == media.size && actualHash == media.sha256 && com.example.privatenotes.data.media.MediaRules.matchesSignature(media.mime, prefix.copyOf(prefixSize))) {
            "附件长度、摘要或文件签名不匹配"
        }
    }

    fun decode(bytes: ByteArray): BackupDocument {
        if (bytes.size > BackupLimits.MAX_FILE_BYTES) {
            throw BackupException(
                BackupErrorKind.INVALID_FORMAT,
                "Backup file is larger than ${BackupLimits.MAX_FILE_BYTES} bytes",
            )
        }
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(stripBom(bytes))).toString()
        } catch (failure: java.nio.charset.CharacterCodingException) {
            throw BackupException(BackupErrorKind.INVALID_FORMAT, "Backup is not valid UTF-8", failure)
        }
        val document = try {
            json.decodeFromString(BackupDocument.serializer(), text)
        } catch (e: SerializationException) {
            throw BackupException(BackupErrorKind.INVALID_FORMAT, "Backup JSON is malformed", e)
        } catch (e: IllegalArgumentException) {
            throw BackupException(BackupErrorKind.INVALID_FORMAT, "Backup JSON is malformed", e)
        }
        validate(document)
        return document
    }

    fun validate(document: BackupDocument) {
        if (document.format != BackupDocument.FORMAT) {
            throw invalid("Not a private-notes backup")
        }
        if (document.schemaVersion !in BackupDocument.SUPPORTED_SCHEMA_VERSIONS) {
            if (document.schemaVersion > BackupDocument.CURRENT_SCHEMA_VERSION) {
                throw BackupException(
                    BackupErrorKind.UNSUPPORTED_VERSION,
                    "Backup schema ${document.schemaVersion} is newer than this app supports",
                )
            }
            throw invalid("Unsupported backup schema ${document.schemaVersion}")
        }
        if (document.appVersion.isBlank() || document.appVersion.length > BackupLimits.MAX_APP_VERSION_LENGTH) {
            throw invalid("Backup appVersion is missing or too long")
        }
        requireTimestamp(document.exportedAt, "exportedAt")
        if (document.notes.size > BackupLimits.MAX_NOTES ||
            document.tags.size > BackupLimits.MAX_TAGS ||
            document.noteTags.size > BackupLimits.MAX_NOTE_TAGS ||
            document.deletionMarkers.size > BackupLimits.MAX_DELETION_MARKERS
        ) {
            throw invalid("Backup contains more rows than this app can import")
        }

        var legacyMediaBytes = 0L
        val mediaIds = HashSet<String>()
        val noteIds = HashSet<String>(document.notes.size)
        for (note in document.notes) {
            if (document.schemaVersion == 1 && note.media.isNotEmpty()) throw invalid("Schema 1 cannot contain media")
            try { com.example.privatenotes.data.media.MediaRules.noteBytes(note.media.map { it.size }) }
            catch (failure: IllegalArgumentException) { throw invalid("Too much media in note") }
            for (media in note.media) {
                if (media.size !in 1..com.example.privatenotes.data.media.MediaRules.LEGACY_MAX_BYTES) {
                    throw invalid("Legacy JSON/PNB media exceeds the safe import limits (8 MiB per item, 40 MiB total)")
                }
                legacyMediaBytes = try { Math.addExact(legacyMediaBytes, media.size) }
                catch (_: ArithmeticException) { throw invalid("Legacy media size overflow") }
                if (legacyMediaBytes > BackupLimits.MAX_LEGACY_MEDIA_TOTAL_BYTES) {
                    throw invalid("Legacy JSON/PNB media exceeds the safe import limits (8 MiB per item, 40 MiB total)")
                }
                if (!mediaIds.add(media.id)) throw invalid("Duplicate media id")
                try { com.example.privatenotes.data.media.MediaRules.validate(media) }
                catch (failure: IllegalArgumentException) { throw invalid("Invalid media: ${failure.message}") }
            }
            requireId(note.id, "note.id")
            if (!noteIds.add(note.id)) throw invalid("Duplicate note id in backup")
            if (note.title.length > BackupLimits.MAX_TITLE_LENGTH) throw invalid("Note title is too long")
            if (note.content.length > BackupLimits.MAX_CONTENT_LENGTH) throw invalid("Note body is too long")
            requireTimestamp(note.createdAt, "note.createdAt")
            requireTimestamp(note.updatedAt, "note.updatedAt")
            if (note.updatedAt < note.createdAt) throw invalid("Note updatedAt precedes createdAt")
            note.deletedAt?.let {
                requireTimestamp(it, "note.deletedAt")
                if (it < note.createdAt) throw invalid("Note deletedAt precedes createdAt")
            }
        }

        val tagIds = HashSet<String>(document.tags.size)
        val tagNames = HashSet<String>(document.tags.size)
        for (tag in document.tags) {
            requireId(tag.id, "tag.id")
            if (!tagIds.add(tag.id)) throw invalid("Duplicate tag id in backup")
            if (tag.name.isBlank() || tag.name.length > BackupLimits.MAX_TAG_NAME_LENGTH) {
                throw invalid("Tag name is empty or too long")
            }
            if (!tagNames.add(tag.name.lowercase(Locale.ROOT))) {
                throw invalid("Duplicate tag name in backup")
            }
            requireTimestamp(tag.createdAt, "tag.createdAt")
            requireTimestamp(tag.updatedAt, "tag.updatedAt")
            if (tag.updatedAt < tag.createdAt) throw invalid("Tag updatedAt precedes createdAt")
        }

        val refs = HashSet<Pair<String, String>>(document.noteTags.size)
        for (ref in document.noteTags) {
            requireId(ref.noteId, "noteTag.noteId")
            requireId(ref.tagId, "noteTag.tagId")
            if (ref.noteId !in noteIds) throw invalid("note_tag_ref points at a missing note")
            if (ref.tagId !in tagIds) throw invalid("note_tag_ref points at a missing tag")
            if (!refs.add(ref.noteId to ref.tagId)) throw invalid("Duplicate note_tag_ref in backup")
        }

        val markerIds = HashSet<String>(document.deletionMarkers.size)
        for (marker in document.deletionMarkers) {
            requireId(marker.noteId, "deletionMarker.noteId")
            if (!markerIds.add(marker.noteId)) throw invalid("Duplicate deletion marker in backup")
            requireTimestamp(marker.deletedAt, "deletionMarker.deletedAt")
            requireTimestamp(marker.lastKnownUpdatedAt, "deletionMarker.lastKnownUpdatedAt")
            if (marker.schemaVersion !in 1..BackupDocument.CURRENT_SCHEMA_VERSION) {
                throw invalid("Deletion marker has an unsupported schema version")
            }
        }
    }

    private fun requireId(value: String, field: String) {
        if (value.isBlank() || value.length > BackupLimits.MAX_ID_LENGTH) {
            throw invalid("$field is empty or too long")
        }
    }

    private fun requireTimestamp(value: Long, field: String) {
        if (value < 0 || value > BackupLimits.MAX_TIMESTAMP) {
            throw invalid("$field is outside the supported timestamp range")
        }
    }

    private fun stripBom(bytes: ByteArray): ByteArray {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return bytes.copyOfRange(3, bytes.size)
        }
        return bytes
    }

    private fun invalid(message: String): BackupException =
        BackupException(BackupErrorKind.INVALID_FORMAT, message)
}
