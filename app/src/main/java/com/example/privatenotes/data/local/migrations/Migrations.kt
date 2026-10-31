package com.example.privatenotes.data.local.migrations

import androidx.room.migration.Migration

object Migrations {
    val MEDIA_1_2 = object : Migration(1, 2) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `note_media` (`id` TEXT NOT NULL, `noteId` TEXT NOT NULL, `name` TEXT NOT NULL, `mime` TEXT NOT NULL, `size` INTEGER NOT NULL, `sha256` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_media_noteId` ON `note_media` (`noteId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `media_chunks` (`mediaId` TEXT NOT NULL, `position` INTEGER NOT NULL, `bytes` BLOB NOT NULL, PRIMARY KEY(`mediaId`, `position`), FOREIGN KEY(`mediaId`) REFERENCES `note_media`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        }
    }
    // Existing payloads stay encrypted and untouched. Their missing checksums are backfilled
    // lazily, in one verified transaction, rather than scanning every video during app upgrade.
    val MEDIA_2_3 = object : Migration(2, 3) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `media_chunks` ADD COLUMN `sha256` TEXT NOT NULL DEFAULT ''")
        }
    }
    val ALL: Array<Migration> = arrayOf(MEDIA_1_2, MEDIA_2_3)
}
