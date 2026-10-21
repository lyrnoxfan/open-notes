package com.example.privatenotes.data.backup.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.privatenotes.PrivateNotesApp
import com.example.privatenotes.data.backup.BackupException

/** Daily local backup, or a one-off catch-up run (§9.2, §9.4). */
class LocalBackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? PrivateNotesApp)?.container
            ?: return Result.failure()
        if (!container.localBackupManager.isConfigured()) {
            // Not configured is not a failure: the user may only use WebDAV or manual export.
            return Result.success()
        }
        return try {
            container.localBackupManager.backup(password = null)
            Result.success()
        } catch (e: BackupException) {
            e.toWorkerResult()
        }
    }
}

internal fun BackupException.toWorkerResult(): Result =
    if (retryable) {
        Result.retry()
    } else {
        Result.failure(workDataOf(KEY_ERROR_KIND to kind.name))
    }

const val KEY_ERROR_KIND = "backup_error_kind"
