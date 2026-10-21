# Secure storage notes

## Why the key is wrapped instead of derived

A passphrase derived from device identifiers is reproducible: anything that can read those
identifiers can re-derive the key, which undermines encrypting the file at all. The passphrase
here is 32 bytes from `SecureRandom`, and it is only reachable through a Keystore key that
cannot be exported, even by this app.

## What each guard protects against

| Guard | Failure it prevents |
| --- | --- |
| `DatabaseFileGuard.requireNoPlaintextDatabase` | A build or downgrade that wrote plaintext; silently adopting it would mean reading unencrypted note data. |
| `DatabaseFileGuard.requireEncryptedDatabase` | A misconfigured Room builder that quietly drops the SQLCipher factory. |
| `DatabaseKeyManager` round-trip verification | Creating a database keyed by a passphrase that was never durably stored — an unopenable database. |
| `WrappedSecret` version byte | Future wrapping changes misreading old blobs as the current format. |
| Backup exclusions | Shipping ciphertext plus wrapped key off-device together. |

## Known limitation: key invalidation

If the Keystore entry is destroyed (factory reset of secure hardware, some lock-screen changes,
restoring data to a different device), the wrapped passphrase is unrecoverable and the existing
database cannot be opened. The app surfaces this as an error state rather than crashing.

Handling that case — user-visible recovery, optional export — is deliberately out of scope for
phase 1 and belongs with the backup/export work.
