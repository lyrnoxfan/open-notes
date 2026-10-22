package com.example.privatenotes.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.privatenotes.security.AppLock
import com.example.privatenotes.security.AppLockState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The auto-sync coordinator: what it does after an article is saved.
 *
 * Counting uploads, not timing them. The cases that matter: off by default, on when enabled, one
 * upload for a burst of saves, silence (never a crash into the editor) when locked or offline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteSyncCoordinatorTest {

    private lateinit var statusStore: BackupStatusStore
    private lateinit var scope: CoroutineScope
    private var uploads = 0
    private var failUpload = false
    private var lockState = MutableStateFlow<AppLockState>(AppLockState.Unlocked)

    private val lock = object : AppLock {
        override val enabled: StateFlow<Boolean> = MutableStateFlow(false)
        override val state: StateFlow<AppLockState> get() = lockState
        override fun onForeground() = Unit
        override fun onBackground() = Unit
        override fun unlock() = Unit
        override fun beginSystemUi() = Unit
        override fun endSystemUi() = Unit
        override fun setEnabled(enabled: Boolean) = Unit
    }

    // A lambda, not a manager: the coordinator only needs "upload now", and a fake manager
    // would have to subclass a final class.
    private val uploadNow: suspend () -> Unit = { if (failUpload) error("network down") else uploads++ }

    private lateinit var coordinator: NoteSyncCoordinator

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        statusStore = BackupStatusStore(context, cipher = PlainTextTestCipher())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        coordinator = NoteSyncCoordinator(
            statusStore = statusStore,
            appLock = lock,
            upload = uploadNow,
            scope = scope,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun configureWebDav() {
        statusStore.saveWebDav(
            baseUrl = "https://dav.example.com/notes",
            username = "user",
            remoteDirectory = "open-notes",
            password = "pw".toCharArray(),
        )
    }

    @Test
    fun offByDefaultAndSilentWhenUnconfigured() = runBlocking {
        coordinator.onNotePersisted()
        coordinator.syncNow()
        assertEquals("no upload without the feature on", 0, uploads)
    }

    @Test
    fun uploadsWhenEnabledAndConfigured() = runBlocking {
        configureWebDav()
        statusStore.setAutoSyncEnabled(true)
        assertTrue(coordinator.syncNow())
        assertEquals(1, uploads)
    }

    @Test
    fun noUploadWhileLocked() = runBlocking {
        configureWebDav()
        statusStore.setAutoSyncEnabled(true)
        lockState.value = AppLockState.Locked
        assertFalse(coordinator.syncNow())
        assertEquals("the database is closed behind the lock; syncing would reopen it", 0, uploads)
    }

    @Test
    fun aBurstOfSavesCollapsesToOneUpload() {
        configureWebDav()
        statusStore.setAutoSyncEnabled(true)
        repeat(5) { coordinator.onNotePersisted() }
        // The coordinator runs on a real dispatcher, so this waits on wall time, not virtual.
        val deadline = System.currentTimeMillis() + NoteSyncCoordinator.DEFAULT_DEBOUNCE_MS + 2_000
        while (uploads == 0 && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals("one upload after the burst settles", 1, uploads)
    }

    @Test
    fun aFailedUploadDoesNotThrowIntoTheEditor() = runBlocking {
        configureWebDav()
        statusStore.setAutoSyncEnabled(true)
        failUpload = true
        coordinator.onNotePersisted()
        val result = coordinator.syncNow()
        assertFalse("failure is reported as false, not thrown", result)
    }
}

/** Stands in for the Keystore in Robolectric: wrap = identity. */
private class PlainTextTestCipher : com.example.privatenotes.security.SecretCipher {
    override fun wrap(plain: ByteArray): ByteArray = plain.copyOf()
    override fun unwrap(wrapped: ByteArray): ByteArray = wrapped.copyOf()
}
