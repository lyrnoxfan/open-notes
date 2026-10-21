package com.example.privatenotes.data.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSchedulerTest {

    private val day = BackupScheduler.CATCH_UP_INTERVAL_MILLIS
    private val hour = BackupScheduler.RETRY_AFTER_FAILURE_MILLIS
    private val now = 10 * day

    @Test
    fun `no previous success needs a catch-up`() {
        assertTrue(BackupScheduler.needsCatchUp(lastSuccessAt = null, lastFailure = null, now = now))
    }

    @Test
    fun `a success within a day does not need a catch-up`() {
        assertFalse(BackupScheduler.needsCatchUp(now - day + 1, null, now))
    }

    @Test
    fun `a success exactly one day old is stale`() {
        assertTrue(BackupScheduler.needsCatchUp(now - day, null, now))
    }

    @Test
    fun `a recent failure suppresses an automatic retry even when stale`() {
        assertFalse(
            BackupScheduler.needsCatchUp(
                lastSuccessAt = now - 2 * day,
                lastFailure = BackupFailure(BackupErrorKind.NETWORK, now - hour + 1),
                now = now,
            ),
        )
    }

    @Test
    fun `an old failure does not suppress a retry forever`() {
        assertTrue(
            BackupScheduler.needsCatchUp(
                lastSuccessAt = now - 2 * day,
                lastFailure = BackupFailure(BackupErrorKind.NETWORK, now - hour),
                now = now,
            ),
        )
    }

    @Test
    fun `a success in the future from a clock change is not treated as stale`() {
        assertFalse(BackupScheduler.needsCatchUp(now + day, null, now))
    }
}
