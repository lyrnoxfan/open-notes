package com.example.privatenotes.feature.notes

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/** The list prints three different shapes of timestamp; the boundaries are worth pinning down. */
class ListTimestampTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun millis(text: String): Long =
        LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun timeOfDayForToday() {
        val now = millis("2024-03-05T21:30:00")

        assertEquals("09:05", formatListTimestamp(millis("2024-03-05T09:05:00"), now, zone))
    }

    @Test
    fun dayOfMonthForThisYear() {
        val now = millis("2024-12-31T23:00:00")

        assertEquals("3月5日", formatListTimestamp(millis("2024-03-05T09:05:00"), now, zone))
    }

    @Test
    fun fullDateBeforeThisYear() {
        val now = millis("2024-01-01T00:10:00")

        assertEquals("2023年3月5日", formatListTimestamp(millis("2023-03-05T22:00:00"), now, zone))
    }

    @Test
    fun midnightBelongsToItsOwnDay() {
        val now = millis("2024-03-05T23:59:00")

        assertEquals("3月4日", formatListTimestamp(millis("2024-03-04T23:59:00"), now, zone))
    }
}
