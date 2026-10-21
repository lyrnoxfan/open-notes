package com.example.privatenotes.data.backup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.privatenotes.data.backup.work.LocalBackupWorker
import com.example.privatenotes.data.backup.work.RemoteBackupWorker
import com.example.privatenotes.data.repository.TimeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.concurrent.TimeUnit

/**
 * WorkManager wiring (§9.4).
 *
 * Local and remote backups are separate unique jobs, so a dead WebDAV server cannot fail the
 * local copy, and vice versa. Daily periodic work is the baseline; [onAppForeground] adds a
 * catch-up job when the last success is older than a day, because Android may defer or kill
 * background work and the periodic schedule alone cannot promise it ran.
 */
class BackupScheduler(
    context: Context,
    private val statusStore: BackupStatusStore,
    private val timeSource: TimeSource,
) {

    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun onAppForeground() {
        ensurePeriodicWork()
        catchUpIfStale()
    }

    fun ensurePeriodicWork() {
        // One destination failing to enqueue must not suppress the other.
        runCatching {
            workManager.enqueueUniquePeriodicWork(
                LOCAL_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<LocalBackupWorker>(
                    1, TimeUnit.DAYS, 1, TimeUnit.HOURS,
                )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }
        runCatching {
            workManager.enqueueUniquePeriodicWork(
                REMOTE_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RemoteBackupWorker>(
                    1, TimeUnit.DAYS, 1, TimeUnit.HOURS,
                )
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }
    }

    fun catchUpIfStale() {
        val now = timeSource.nowMillis()
        val status = statusStore.status.value
        if (status.localConfigured && needsCatchUp(status.localLastSuccessAt, status.localLastFailure, now)) {
            runCatching { requestLocalNow() }
        }
        if (status.webDavConfigured && needsCatchUp(status.remoteLastSuccessAt, status.remoteLastFailure, now)) {
            runCatching { requestRemoteNow() }
        }
    }

    /** @return false when there is nothing configured to back up. */
    fun requestLocalNow(): Boolean {
        if (!statusStore.status.value.localConfigured) return false
        workManager.enqueueUniqueWork(
            LOCAL_NOW,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<LocalBackupWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
        return true
    }

    /** @return false when WebDAV is not fully configured. */
    fun requestRemoteNow(): Boolean {
        if (!statusStore.status.value.webDavConfigured) return false
        workManager.enqueueUniqueWork(
            REMOTE_NOW,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RemoteBackupWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
        return true
    }

    fun observeLocalWork(): Flow<WorkInfo.State?> = combine(
        workManager.getWorkInfosForUniqueWorkFlow(LOCAL_NOW),
        workManager.getWorkInfosForUniqueWorkFlow(LOCAL_PERIODIC),
    ) { immediate, periodic -> currentState(immediate, periodic) }

    fun observeRemoteWork(): Flow<WorkInfo.State?> = combine(
        workManager.getWorkInfosForUniqueWorkFlow(REMOTE_NOW),
        workManager.getWorkInfosForUniqueWorkFlow(REMOTE_PERIODIC),
    ) { immediate, periodic -> currentState(immediate, periodic) }

    /**
     * Prefer work that is actually queued or running over a historical one-off result. Without
     * this, a successful manual backup would keep masking the daily job that is about to run.
     */
    private fun currentState(immediate: List<WorkInfo>, periodic: List<WorkInfo>): WorkInfo.State? {
        val all = immediate + periodic
        return all.firstOrNull { it.state == WorkInfo.State.RUNNING }?.state
            ?: all.firstOrNull { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }?.state
            ?: immediate.lastOrNull()?.state
            ?: periodic.lastOrNull()?.state
    }

    companion object {
        const val LOCAL_PERIODIC = "backup-local-periodic"
        const val LOCAL_NOW = "backup-local-now"
        const val REMOTE_PERIODIC = "backup-remote-periodic"
        const val REMOTE_NOW = "backup-remote-now"

        const val CATCH_UP_INTERVAL_MILLIS: Long = 24L * 60 * 60 * 1000
        const val RETRY_AFTER_FAILURE_MILLIS: Long = 60L * 60 * 1000

        /**
         * A day has passed without success, and the last failure is old enough that retrying
         * is not just hammering a configuration that will keep failing.
         */
        fun needsCatchUp(
            lastSuccessAt: Long?,
            lastFailure: BackupFailure?,
            now: Long,
        ): Boolean {
            val stale = lastSuccessAt == null || now - lastSuccessAt >= CATCH_UP_INTERVAL_MILLIS
            if (!stale) return false
            val recentlyFailed = lastFailure != null && now - lastFailure.at < RETRY_AFTER_FAILURE_MILLIS
            return !recentlyFailed
        }
    }
}
