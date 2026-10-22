package com.example.privatenotes.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupJsonTest {

    private val json = BackupJson()

    private val valid = backupDocument(
        notes = listOf(backupNote("note-1")),
        tags = listOf(backupTag("tag-1")),
        noteTags = listOf(BackupNoteTag("note-1", "tag-1")),
        markers = listOf(BackupDeletionMarker("gone", deletedAt = 200L, lastKnownUpdatedAt = 150L)),
    )

    @Test
    fun `invalid UTF8 is rejected instead of silently corrupting note text`() {
        val bytes = json.encode(valid.copy(notes = listOf(backupNote("note-1", title = "UTF8CANARY"))))
        val needle = "UTF8CANARY".toByteArray()
        val start = bytes.indices.first { i -> i + needle.size <= bytes.size && bytes.copyOfRange(i, i + needle.size).contentEquals(needle) }
        bytes[start] = 0xFF.toByte()
        val failure = assertThrows(BackupException::class.java) { json.decode(bytes) }
        assertEquals(BackupErrorKind.INVALID_FORMAT, failure.kind)
    }

    @Test
    fun `a valid document survives encode and decode`() {
        val decoded = json.decode(json.encode(valid))

        assertEquals(valid, decoded)
    }

    @Test
    fun `unknown fields are tolerated for forward compatibility`() {
        val encoded = String(json.encode(valid), Charsets.UTF_8)
            .replaceFirst("{", """{"futureField":{"nested":true},""")

        val decoded = json.decode(encoded.toByteArray(Charsets.UTF_8))

        assertEquals(valid.notes, decoded.notes)
    }

    @Test
    fun `a file with the wrong format marker is rejected`() {
        val failure = failureOf { json.validate(valid.copy(format = "something-else")) }

        assertEquals(BackupErrorKind.INVALID_FORMAT, failure.kind)
    }

    @Test
    fun `a backup from a newer schema is reported as unsupported`() {
        val failure = failureOf { json.validate(valid.copy(schemaVersion = 2)) }

        assertEquals(BackupErrorKind.UNSUPPORTED_VERSION, failure.kind)
    }

    @Test
    fun `duplicate note ids are rejected`() {
        val failure = failureOf {
            json.validate(valid.copy(notes = listOf(backupNote("same"), backupNote("same", title = "other"))))
        }

        assertEquals(BackupErrorKind.INVALID_FORMAT, failure.kind)
    }

    @Test
    fun `duplicate tag names are rejected case-insensitively`() {
        val failure = failureOf {
            json.validate(
                valid.copy(
                    tags = listOf(backupTag("a", name = "Work"), backupTag("b", name = "work")),
                    noteTags = emptyList(),
                ),
            )
        }

        assertEquals(BackupErrorKind.INVALID_FORMAT, failure.kind)
    }

    @Test
    fun `a reference to a missing note is rejected`() {
        val failure = failureOf {
            json.validate(valid.copy(noteTags = listOf(BackupNoteTag("missing", "tag-1"))))
        }

        assertEquals(BackupErrorKind.INVALID_FORMAT, failure.kind)
    }

    @Test
    fun `a reference to a missing tag is rejected`() {
        val failure = failureOf {
            json.validate(valid.copy(noteTags = listOf(BackupNoteTag("note-1", "missing"))))
        }

        assertEquals(BackupErrorKind.INVALID_FORMAT, failure.kind)
    }

    @Test
    fun `timestamps must be non-negative and internally ordered`() {
        val failure = failureOf {
            json.validate(valid.copy(notes = listOf(backupNote("n", createdAt = 500L, updatedAt = 400L))))
        }

        assertEquals(BackupErrorKind.INVALID_FORMAT, failure.kind)
    }

    @Test
    fun `malformed json is rejected without leaking a raw exception`() {
        val failure = failureOf { json.decode("{not-json".toByteArray()) }

        assertEquals(BackupErrorKind.INVALID_FORMAT, failure.kind)
    }

    @Test
    fun `missing required fields are rejected`() {
        val failure = failureOf {
            json.decode("""{"format":"private-notes-backup","schemaVersion":1}""".toByteArray())
        }

        assertTrue(failure.kind == BackupErrorKind.INVALID_FORMAT)
    }

    @Test
    fun `empty lists are valid`() {
        json.validate(backupDocument())
    }

    private fun failureOf(block: () -> Unit): BackupException =
        assertThrows(BackupException::class.java) { block() }
}
