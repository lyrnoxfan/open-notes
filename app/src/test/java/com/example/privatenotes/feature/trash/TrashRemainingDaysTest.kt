package com.example.privatenotes.feature.trash

import com.example.privatenotes.data.repository.NoteRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

/** §8: the trash shows how long is left before automatic cleanup. */
class TrashRemainingDaysTest {

    private val day = TimeUnit.DAYS.toMillis(1)

    @Test
    fun `a freshly deleted note has the full window left`() {
        val deletedAt = 1_000_000L

        assertEquals(30, remainingDays(deletedAt, deletedAt))
    }

    @Test
    fun `remaining days shrink as time passes`() {
        val deletedAt = 1_000_000L

        assertEquals(20, remainingDays(deletedAt, deletedAt + 10 * day))
    }

    @Test
    fun `an expired note reports zero rather than a negative number`() {
        val deletedAt = 1_000_000L

        assertEquals(0, remainingDays(deletedAt, deletedAt + NoteRepository.TRASH_RETENTION_MILLIS + day))
    }

    @Test
    fun `a partial day left rounds up so the label never says zero early`() {
        val deletedAt = 1_000_000L
        val justBeforeExpiry = deletedAt + NoteRepository.TRASH_RETENTION_MILLIS - 1

        assertEquals(1, remainingDays(deletedAt, justBeforeExpiry))
    }
}
