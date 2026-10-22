package com.example.privatenotes.data.backup

import com.example.privatenotes.security.AppLock
import com.example.privatenotes.security.AppLockState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Uploads a backup to WebDAV shortly after an article is finished, when the user asked for it.
 *
 * "Finished" means the editor wrote to the database, not that a key was pressed: the editor's own
 * debounce already decides that, so this only waits long enough that typing three sentences in a
 * row produces one upload instead of three.
 *
 * Four things must hold before an upload starts, and any of them failing means silence rather than
 * an error in the editor's status line:
 *  - the feature is on and the destination is configured;
 *  - the app is unlocked - §6.2 closes the database behind the lock screen, and a background
 *    upload must not reopen it;
 *  - no other upload is running: a snapshot is expensive and two overlapping ones would upload
 *    nearly identical files;
 *  - the write that triggered this was a real save.
 */
class NoteSyncCoordinator(
    private val statusStore: BackupStatusStore,
    private val appLock: AppLock,
    private val upload: suspend () -> Unit,
    scope: CoroutineScope,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MS,
) {

    /** Latest-signal-wins: a burst of saves collapses to one upload of the final state. */
    private val signals = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val uploadMutex = Mutex()

    init {
        scope.launch {
            signals.debounce(debounceMillis).collect {
                runCatching { syncNow() }
            }
        }
    }

    /** Called after a note reached the database. Cheap and non-blocking. */
    fun onNotePersisted() {
        signals.tryEmit(Unit)
    }

    /**
     * Uploads now, if the preconditions hold.
     *
     * Returns false when it deliberately did nothing (disabled, unconfigured, locked, or already
     * running) and true when an upload was attempted without throwing. Failures are recorded in
     * [BackupStatusStore] by the manager, where the settings page already shows them.
     */
    @OptIn(FlowPreview::class)
    suspend fun syncNow(): Boolean {
        val status = statusStore.status.value
        if (!status.autoSyncEnabled || !status.webDavConfigured) return false
        if (appLock.state.value != AppLockState.Unlocked) return false
        if (!uploadMutex.tryLock()) return false
        return try {
            upload()
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Recorded as a remote failure by WebDavBackupManager; the editor keeps its own status.
            false
        } finally {
            uploadMutex.unlock()
        }
    }

    companion object {
        /** Long enough to merge a burst of saves, short enough to feel immediate. */
        const val DEFAULT_DEBOUNCE_MS = 3_000L
    }
}
