package com.example.privatenotes.data.backup

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupDownloadLimitTest {
    @Test fun `unknown length stream stops at the configured restore limit`() {
        assertThrows(BackupException::class.java) {
            readBackupBytes(ByteArrayInputStream(ByteArray(33)), maxBytes = 32)
        }
    }

    @Test fun `a backup exactly at the limit remains readable`() {
        val bytes = ByteArray(32) { it.toByte() }
        assertArrayEquals(bytes, readBackupBytes(ByteArrayInputStream(bytes), maxBytes = 32))
    }
}
