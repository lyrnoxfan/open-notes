package com.example.privatenotes.data.backup.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.example.privatenotes.PrivateNotesApp
import com.example.privatenotes.data.backup.BackupException

/** Daily WebDAV upload, or a one-off retry (§9.3, §9.4). */
class RemoteBackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? PrivateNotesApp)?.container
            ?: return Result.failure()
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
