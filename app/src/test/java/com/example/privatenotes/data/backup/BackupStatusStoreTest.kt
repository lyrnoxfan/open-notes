package com.example.privatenotes.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.privatenotes.security.FakeSecretCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupStatusStoreTest {

    private lateinit var context: Context
    private lateinit var store: BackupStatusStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(BackupStatusStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        store = BackupStatusStore(context, FakeSecretCipher())
    }

    @Test
    fun `the local directory and its result history are remembered`() {
        store.setLocalDirectory("content://tree/backups", "Backups")

        assertEquals("Backups", store.status.value.localDirectoryName)
        assertTrue(store.status.value.localConfigured)

        store.markLocalFailure(BackupErrorKind.PERMISSION_LOST, 100L, "grant revoked")
        assertEquals(BackupErrorKind.PERMISSION_LOST, store.status.value.localLastFailure?.kind)

        store.markLocalSuccess(200L)
        assertNull("success clears the previous failure", store.status.value.localLastFailure)
        assertEquals(200L, store.status.value.localLastSuccessAt ?: -1L)
    }

    @Test
    fun `local and remote histories stay independent`() {
        store.markLocalSuccess(100L)
        store.markRemoteFailure(BackupErrorKind.NETWORK, 200L, "offline")

        assertEquals(100L, store.status.value.localLastSuccessAt ?: -1L)
        assertNull(store.status.value.localLastFailure)
        assertNull(store.status.value.remoteLastSuccessAt)
        assertEquals(BackupErrorKind.NETWORK, store.status.value.remoteLastFailure?.kind)
    }

    @Test
    fun `the webdav password is stored wrapped and can be read back`() {
        val password = "s3cret-value".toCharArray()

        store.saveWebDav(
            baseUrl = "https://example.com/dav/",
            username = "user",
            remoteDirectory = "notes",
            password = password,
        )

        assertTrue(store.status.value.webDavConfigured)
        assertEquals("s3cret-value", store.webDavCredentials().password)
        assertFalse(
            "the plaintext password must not sit in preferences",
            context.getSharedPreferences(BackupStatusStore.PREFS_NAME, Context.MODE_PRIVATE)
                .all
                .values
                .any { it.toString().contains("s3cret-value") },
        )
    }

    @Test
    fun `saving without a password keeps the existing credential`() {
        store.saveWebDav("https://example.com/dav/", "user", "notes", "first-secret".toCharArray())

        store.saveWebDav("https://example.com/dav/", "user", "notes", CharArray(0))

        assertTrue(store.status.value.webDavPasswordSaved)
        assertEquals("first-secret", store.webDavCredentials().password)
    }

    @Test
    fun `clearing the webdav password makes the configuration incomplete`() {
        store.saveWebDav("https://example.com/dav/", "user", "notes", "secret".toCharArray())

        store.clearWebDavPassword()

        assertFalse(store.status.value.webDavConfigured)
        assertFalse(store.webDavSettings().hasPassword)
    }

    @Test
    fun `a failure detail survives a fresh store instance`() {
        store.markRemoteFailure(BackupErrorKind.UNAUTHORIZED, 300L, "HTTP 401")

        val reopened = BackupStatusStore(context, FakeSecretCipher())

        assertEquals(BackupErrorKind.UNAUTHORIZED, reopened.status.value.remoteLastFailure?.kind)
        assertEquals("HTTP 401", reopened.status.value.remoteLastFailure?.detail)
        assertEquals(300L, reopened.status.value.remoteLastFailure?.at ?: -1L)
    }
}
