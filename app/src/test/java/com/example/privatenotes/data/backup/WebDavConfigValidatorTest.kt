package com.example.privatenotes.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavConfigValidatorTest {

    @Test
    fun `https urls are accepted and normalised`() {
        val url = WebDavConfigValidator.parseBaseUrl("  https://example.com/dav/  ")

        assertEquals("https", url.scheme)
        assertEquals("example.com", url.host)
        assertEquals("/dav/", url.encodedPath)
    }

    @Test
    fun `http is refused`() {
        val failure = failureOf {
            WebDavConfigValidator.parseBaseUrl("http://example.com/dav/")
        }

        assertEquals(BackupErrorKind.INVALID_CONFIG, failure.kind)
    }

    @Test
    fun `credentials in the url are refused`() {
        assertThrows(BackupException::class.java) {
            WebDavConfigValidator.parseBaseUrl("https://user:pass@example.com/dav/")
        }
    }

    @Test
    fun `a fragment is refused`() {
        assertThrows(BackupException::class.java) {
            WebDavConfigValidator.parseBaseUrl("https://example.com/dav/#fragment")
        }
    }

    @Test
    fun `remote directories are split and normalised`() {
        assertEquals(emptyList<String>(), WebDavConfigValidator.parseRemoteDirectory("  "))
        assertEquals(listOf("notes", "backup"), WebDavConfigValidator.parseRemoteDirectory("/notes/backup/"))
        assertEquals("notes/backup", WebDavConfigValidator.normalizedRemoteDirectory("notes//backup"))
    }

    @Test
    fun `path traversal in the remote directory is refused`() {
        assertThrows(BackupException::class.java) {
            WebDavConfigValidator.parseRemoteDirectory("notes/../other")
        }
        assertThrows(BackupException::class.java) {
            WebDavConfigValidator.parseRemoteDirectory("notes\\backup")
        }
    }

    @Test
    fun `username must be non-blank and must not contain a colon`() {
        assertThrows(BackupException::class.java) {
            WebDavConfigValidator.validateUsername("")
        }
        assertThrows(BackupException::class.java) {
            WebDavConfigValidator.validateUsername("user:name")
        }
    }

    @Test
    fun `a full configuration is validated together`() {
        WebDavConfigValidator.validate("https://example.com/dav/", "user", "notes/backup")
    }

    @Test
    fun `plain http is refused even when the rest is valid`() {
        val failure = failureOf {
            WebDavConfigValidator.validate("http://example.com/dav/", "user", "notes")
        }

        assertEquals(BackupErrorKind.INVALID_CONFIG, failure.kind)
        assertTrue(failure.message.orEmpty().contains("HTTPS"))
    }

    private fun failureOf(block: () -> Unit): BackupException =
        assertThrows(BackupException::class.java) { block() }
}
