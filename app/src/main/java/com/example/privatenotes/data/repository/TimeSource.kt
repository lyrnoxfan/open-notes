package com.example.privatenotes.data.repository

/**
 * Single source of "now" for persisted timestamps (§5.4: timestamp updates are centralised).
 *
 * Funnelling time through one place keeps `createdAt`/`updatedAt`/`deletedAt` consistent and
 * makes time-dependent behaviour testable without sleeping.
 */
fun interface TimeSource {
    fun nowMillis(): Long

    companion object {
        val System: TimeSource = TimeSource { java.lang.System.currentTimeMillis() }
    }
}
