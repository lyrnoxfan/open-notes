package com.example.privatenotes.data.backup

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.Locale

/** Structural limits, so a malicious or truncated file cannot exhaust the device. */
object BackupLimits {
    const val MAX_FILE_BYTES: Long = 64L * 1024 * 1024
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

        val noteIds = HashSet<String>(document.notes.size)
        for (note in document.notes) {
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
