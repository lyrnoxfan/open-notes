package com.example.privatenotes.data.backup

import com.example.privatenotes.data.media.BackupMedia
import com.example.privatenotes.data.media.MediaRules
import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LegacyImportLimitTest {
    private val json = BackupJson()

    @Test fun `legacy JSON rejects media over 8 MiB before decoding its payload`() {
        val doc = backupDocument(notes = listOf(noteWithMedia("m1", MediaRules.LEGACY_MAX_BYTES.toLong() + 1L)))
        val error = assertThrows(BackupException::class.java) { json.validate(doc) }
        assertEquals(BackupErrorKind.INVALID_FORMAT, error.kind)
        assertEquals("Legacy JSON/PNB media exceeds the safe import limits (8 MiB per item, 40 MiB total)", error.message)
    }

    @Test fun `legacy JSON rejects more than 40 MiB aggregate before decoding payloads`() {
        val notes = (0 until 6).map { index -> noteWithMedia("m$index", 7L * MIB) }
        val error = assertThrows(BackupException::class.java) {
            json.validate(backupDocument(notes = notes))
        }
        assertEquals(BackupErrorKind.INVALID_FORMAT, error.kind)
    }

    @Test fun `bounded legacy file reader accepts limit and rejects first byte beyond it`() {
        val exactlyAtLimit = ByteArray(32) { it.toByte() }
        assertArrayEquals(exactlyAtLimit, readBoundedBackupDocument(ByteArrayInputStream(exactlyAtLimit), 32))
        assertThrows(BackupException::class.java) {
            readBoundedBackupDocument(ByteArrayInputStream(ByteArray(33)), 32)
        }
        assertEquals(64L * MIB, BackupLimits.MAX_FILE_BYTES)
    }

    private fun noteWithMedia(id: String, size: Long): BackupNote {
        val media = BackupMedia(id, "$id.png", "image/png", size, "0".repeat(64), "")
        return backupNote("note-$id").copy(media = listOf(media))
    }

    companion object { private const val MIB = 1024L * 1024 }
}
