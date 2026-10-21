package com.example.privatenotes.data.backup

/**
 * Failure categories that the UI has to tell apart.
 *
 * The backup path touches storage, the network, passwords and the database, and "something went
 * wrong" is not an acceptable status message when a backup is the last copy of someone's notes.
 * Each kind maps to a concrete next step: re-authorise the folder, re-enter the password, retry
 * later, or fix the server configuration.
 */
enum class BackupErrorKind {
    NOT_CONFIGURED,
    PERMISSION_LOST,
    WRONG_PASSWORD_OR_CORRUPT,
    CORRUPT_FILE,
    UNSUPPORTED_VERSION,
    INVALID_FORMAT,
    INVALID_CONFIG,
    NETWORK,
    UNAUTHORIZED,
    SERVER,
    STORAGE,
    SAFETY_BACKUP_FAILED,
    UNKNOWN,
}

/**
 * A backup failure with an explicit retry decision.
 *
 * [retryable] is what WorkManager keys off: a flaky network or a 503 deserves another attempt,
 * a wrong password or a revoked SAF grant does not. Retrying those would only hide the real
 * problem behind a permanently spinning status.
 */
class BackupException(
    val kind: BackupErrorKind,
    message: String,
    cause: Throwable? = null,
    val retryable: Boolean = false,
    detail: String? = null,
) : Exception(message, cause) {

    val detail: String? = detail?.take(MAX_DETAIL_LENGTH)

    companion object {
        const val MAX_DETAIL_LENGTH = 500
    }
}
