# SQLCipher ships JNI entry points reached reflectively by net.sqlcipher.
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.** { *; }

# Room generated implementations are referenced by name at runtime.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**
