# SQLCipher reaches its JNI entry points natively; R8 must not rename or strip them.
-keep,includedescriptorclasses class net.zetetic.database.** { *; }
-keep,includedescriptorclasses interface net.zetetic.database.** { *; }
-keepclasseswithmembernames class net.zetetic.database.** { native <methods>; }
-dontwarn net.zetetic.database.**

# Room generated implementations are referenced by name at runtime.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
