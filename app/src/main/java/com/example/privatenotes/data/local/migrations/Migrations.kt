package com.example.privatenotes.data.local.migrations

import androidx.room.migration.Migration

/**
 * Every schema change lands here as an explicit, tested [Migration] (§5.4).
 *
 * Empty at version 1 — nothing to migrate from yet. It exists now so the database builder is
 * already wired to use it, and so nobody is tempted to reach for a destructive fallback later.
 */
object Migrations {
    val ALL: Array<Migration> = emptyArray()
}
