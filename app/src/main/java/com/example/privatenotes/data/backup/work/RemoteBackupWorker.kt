package com.example.privatenotes.data.backup.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.example.privatenotes.PrivateNotesApp
import com.example.privatenotes.data.backup.BackupException
import com.example.privatenotes.security.AppLockState

/** Daily WebDAV upload, or a one-off retry (§9.3, §9.4). */
class RemoteBackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? PrivateNotesApp)?.container
            ?: return Result.failure()
        // Same rule as the local worker: no database work behind the lock screen.
        if (container.appLock.state.value == AppLockState.Locked) return Result.success()
        if (!container.webDavBackupManager.isConfigured()) {
            return Result.success()
        }
        return try {
            container.webDavBackupManager.backup(password = null)
            Result.success()
        } catch (e: BackupException) {
            e.toWorkerResult()
        }
    }
}
