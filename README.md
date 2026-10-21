# Open Notes

A local-first notes app for Android. Everything you write is stored in a SQLCipher-encrypted
database whose key never leaves the device.

## Status

Phase 1 — engineering foundation and secure storage. The app builds, the theme is in place,
the Room schema is defined, and the database is encrypted from the moment it is created.

## Stack

| Concern | Choice |
| --- | --- |
| Language | Kotlin 2.0.21 |
| UI | Jetpack Compose, Material 3 |
| Storage | Room 2.6.1 over SQLCipher 4.5.4 |
| Build | AGP 8.7.3, Gradle 8.9, version catalog |
| Min / target SDK | 26 / 35 |

## Encryption model

The database file is encrypted by SQLCipher, which means the bytes on disk are never
plaintext SQLite — not on the first write, not ever.

```
Keystore (hardware-backed, non-exportable AES-256-GCM key)
        │  wraps
        ▼
32-byte random passphrase  ──stored wrapped in SharedPreferences──►  app-private storage
        │  keys
        ▼
SQLCipher database  ──►  open_notes.db (ciphertext)
```

Design rules enforced in code, not by convention:

1. **One way to open the database.** `EncryptedDatabaseProvider` is the only place that
   builds an `OpenNotesDatabase`. It attaches the SQLCipher `SupportFactory` before Room
   creates anything, so there is no window in which a plaintext file exists.
2. **Plaintext is refused, not migrated.** `DatabaseFileGuard.requireNoPlaintextDatabase`
   runs before the open and throws if a plaintext SQLite header is found at the database path.
   The app stops rather than silently reading unencrypted data.
3. **The post-open check is real.** After Room opens, the file on disk is re-read and must not
   carry the `SQLite format 3\0` magic.
4. **The key is created once and proven durable.** `DatabaseKeyManager` generates the
   passphrase, wraps it, persists it, then reads it back and unwraps it before returning. If
   persistence failed, the database is never created with a key that could not be stored.
5. **Backups carry nothing useful.** Auto-backup and device transfer exclude both the database
   and the preferences holding the wrapped key.

Because the wrapped key lives in the Android Keystore, copying the app's data directory off the
device yields ciphertext with no usable key.

## Layout

```
app/src/main/java/com/lyrnox/opennotes/
├── core/security/     Keystore cipher, key manager, plaintext-database guard
├── data/local/        Room database, encrypted provider, entities, DAOs
├── data/              repository
└── ui/                theme framework, notes screen
```

## Build

```bash
./gradlew assembleDebug
```

## Test

```bash
# Host tests: key manager, wrapped-secret format, plaintext guard, DAO/schema behaviour
./gradlew testDebugUnitTest

# Device tests: real SQLCipher encryption-on-disk and real Android Keystore
./gradlew connectedDebugAndroidTest
```

The encryption guarantees can only be verified on a device or emulator, because SQLCipher's
native library is what produces the on-disk bytes.

## Schema

Room exports the schema to `app/schemas/` on every build. Version 1 holds `notes`, `folders`,
`tags`, and the `note_tag_refs` join table. Migrations are mandatory going forward — there is
no destructive fallback, since that would discard user notes.
