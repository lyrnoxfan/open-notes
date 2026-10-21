package com.example.privatenotes.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class BackupFileNamesTest {

    private val exportedAt = Instant.parse("2024-01-02T03:04:05Z").toEpochMilli()

    @Test
    fun `names are timestamped in UTC and carry the encryption extension`() {
        assertEquals(
            "private-notes-20240102T030405Z-ab12cd.json",
            BackupFileNames.create(exportedAt, encrypted = false, uniqueSuffix = "ab12cd"),
        )
        assertEquals(
            "private-notes-20240102T030405Z-ab12cd.pnb",
            BackupFileNames.create(exportedAt, encrypted = true, uniqueSuffix = "ab12cd"),
        )
    }

    @Test
    fun `generated names are recognised as backup files`() {
        assertTrue(BackupFileNames.isBackupFile(BackupFileNames.create(exportedAt, false)))
        assertTrue(BackupFileNames.isBackupFile(BackupFileNames.create(exportedAt, true)))
        assertFalse(BackupFileNames.isBackupFile("notes.txt"))
        assertFalse(BackupFileNames.isBackupFile("other-20240102T030405Z.json"))
    }

    @Test
    fun `a collision gets a numeric suffix before the extension`() {
        assertEquals(
            "private-notes-20240102T030405Z-ab12cd-2.json",
            BackupFileNames.withCollisionSuffix(
                "private-notes-20240102T030405Z-ab12cd.json",
                2,
            ),
        )
        assertEquals(
            "private-notes-20240102T030405Z-ab12cd.json",
            BackupFileNames.withCollisionSuffix(
                "private-notes-20240102T030405Z-ab12cd.json",
                1,
            ),
        )
    }

    @Test
    fun `random suffixes are short and hexadecimal`() {
        val suffix = BackupFileNames.randomSuffix()

        assertEquals(6, suffix.length)
        assertTrue(suffix.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `config validator rejects plaintext and credential-bearing URLs`() {
        assertThrows(BackupException::class.java) {
            WebDavConfigValidator.parseBaseUrl("http://example.com/dav/")
        }
        assertThrows(BackupException::class.java) {
            WebDavConfigValidator.parseBaseUrl("https://user:pass@example.com/dav/")
        }
    }
}
