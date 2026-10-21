package com.example.privatenotes.data.backup

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.example.privatenotes.security.AndroidKeystoreSecretCipher
import com.example.privatenotes.security.SecretCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persisted backup configuration and the two independent result histories (§9.6).
 *
 * Local and remote status are separate on purpose: a broken WebDAV server must not make the
 * local backup look failed, and a missing SAF grant must not stop the remote upload.
 */
data class BackupFailure(
    val kind: BackupErrorKind,
    val at: Long,
    val detail: String? = null,
)

data class BackupStatus(
    val localDirectoryUri: String? = null,
    val localDirectoryName: String? = null,
    val localLastSuccessAt: Long? = null,
    val localLastFailure: BackupFailure? = null,
    val webDavBaseUrl: String = "",
    val webDavUsername: String = "",
    val webDavRemoteDirectory: String = "",
    val webDavPasswordSaved: Boolean = false,
    val remoteLastSuccessAt: Long? = null,
    val remoteLastFailure: BackupFailure? = null,
) {
    val localConfigured: Boolean get() = !localDirectoryUri.isNullOrBlank()

    val webDavConfigured: Boolean
        get() = webDavBaseUrl.isNotBlank() && webDavUsername.isNotBlank() && webDavPasswordSaved
}

data class WebDavSettings(
    val baseUrl: String,
    val username: String,
    val remoteDirectory: String,
    val hasPassword: Boolean,
)

data class WebDavCredentials(
    val username: String,
    val password: String,
)

/**
 * SharedPreferences-backed configuration store.
 *
 * The WebDAV password is the one secret kept here, and only in Keystore-wrapped form (§9.3).
 * The backup encryption password is deliberately absent: it is never persisted (§6.3).
 */
class BackupStatusStore(
    context: Context,
    private val cipher: SecretCipher = AndroidKeystoreSecretCipher(WEBDAV_KEY_ALIAS),
) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val lock = Any()
    private val _status = MutableStateFlow(readStatus())
    val status: StateFlow<BackupStatus> = _status.asStateFlow()

    fun setLocalDirectory(uri: String, displayName: String?) {
        synchronized(lock) {
            val editor = prefs.edit()
                .putString(KEY_LOCAL_URI, uri)
                .putString(KEY_LOCAL_NAME, displayName)
            if (prefs.getString(KEY_LOCAL_URI, null) != uri) {
                // A success timestamp belongs to the old folder, not to the new destination.
                editor.remove(KEY_LOCAL_SUCCESS)
                    .remove(KEY_LOCAL_FAILURE_AT)
                    .remove(KEY_LOCAL_FAILURE_KIND)
                    .remove(KEY_LOCAL_FAILURE_DETAIL)
            }
            editor.commit()
            refresh()
        }
    }

    fun clearLocalDirectory() {
        synchronized(lock) {
            prefs.edit()
                .remove(KEY_LOCAL_URI)
                .remove(KEY_LOCAL_NAME)
                .commit()
            refresh()
        }
    }

    fun saveWebDav(
        baseUrl: String,
        username: String,
        remoteDirectory: String,
        password: CharArray?,
    ) {
        synchronized(lock) {
            val sameDestination = prefs.getString(KEY_WEBDAV_URL, "") == baseUrl &&
                prefs.getString(KEY_WEBDAV_USER, "") == username &&
                prefs.getString(KEY_WEBDAV_DIR, "") == remoteDirectory
            val editor = prefs.edit()
                .putString(KEY_WEBDAV_URL, baseUrl)
                .putString(KEY_WEBDAV_USER, username)
                .putString(KEY_WEBDAV_DIR, remoteDirectory)
            if (!sameDestination) {
                // Do not attribute an old server's success, failure, or credential to a new
                // destination. Sending the old password to a newly typed host would leak it.
                editor.remove(KEY_REMOTE_SUCCESS)
                    .remove(KEY_REMOTE_FAILURE_AT)
                    .remove(KEY_REMOTE_FAILURE_KIND)
                    .remove(KEY_REMOTE_FAILURE_DETAIL)
                    .remove(KEY_WEBDAV_PASSWORD)
            }
            if (!password.isNullOrEmpty()) {
                val wrapped = wrapPassword(password)
                editor.putString(KEY_WEBDAV_PASSWORD, Base64.encodeToString(wrapped, Base64.NO_WRAP))
            }
            editor.commit()
            refresh()
        }
    }

    fun clearWebDavPassword() {
        synchronized(lock) {
            prefs.edit().remove(KEY_WEBDAV_PASSWORD).commit()
            refresh()
        }
    }

    fun webDavSettings(): WebDavSettings = WebDavSettings(
        baseUrl = prefs.getString(KEY_WEBDAV_URL, "") ?: "",
        username = prefs.getString(KEY_WEBDAV_USER, "") ?: "",
        remoteDirectory = prefs.getString(KEY_WEBDAV_DIR, "") ?: "",
        hasPassword = !prefs.getString(KEY_WEBDAV_PASSWORD, null).isNullOrBlank(),
    )

    /**
     * Unwraps the saved WebDAV password for one request.
     *
     * A destroyed Keystore entry surfaces as an authentication problem the user can fix by
     * re-entering the password, not as a silent empty credential.
     */
    fun webDavCredentials(): WebDavCredentials {
        val encoded = prefs.getString(KEY_WEBDAV_PASSWORD, null)
            ?: throw BackupException(BackupErrorKind.NOT_CONFIGURED, "No WebDAV password is saved")
        val wrapped = try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            throw BackupException(BackupErrorKind.UNAUTHORIZED, "Saved WebDAV password is malformed", e)
        }
        val plain = try {
            cipher.unwrap(wrapped)
        } catch (e: Exception) {
            throw BackupException(
                BackupErrorKind.UNAUTHORIZED,
                "Saved WebDAV password can no longer be decrypted; enter it again",
                e,
            )
        }
        return try {
            WebDavCredentials(
                username = prefs.getString(KEY_WEBDAV_USER, "") ?: "",
                password = String(plain, Charsets.UTF_8),
            )
        } finally {
            plain.fill(0)
        }
    }

    fun markLocalSuccess(at: Long) {
        synchronized(lock) {
            prefs.edit()
                .putLong(KEY_LOCAL_SUCCESS, at)
                .putLong(KEY_LOCAL_FAILURE_AT, 0L)
                .remove(KEY_LOCAL_FAILURE_KIND)
                .remove(KEY_LOCAL_FAILURE_DETAIL)
                .commit()
            refresh()
        }
    }

    fun markLocalFailure(kind: BackupErrorKind, at: Long, detail: String?) {
        markFailure(local = true, kind = kind, at = at, detail = detail)
    }

    fun markRemoteSuccess(at: Long) {
        synchronized(lock) {
            prefs.edit()
                .putLong(KEY_REMOTE_SUCCESS, at)
                .putLong(KEY_REMOTE_FAILURE_AT, 0L)
                .remove(KEY_REMOTE_FAILURE_KIND)
                .remove(KEY_REMOTE_FAILURE_DETAIL)
                .commit()
            refresh()
        }
    }

    fun markRemoteFailure(kind: BackupErrorKind, at: Long, detail: String?) {
        markFailure(local = false, kind = kind, at = at, detail = detail)
    }

    private fun markFailure(local: Boolean, kind: BackupErrorKind, at: Long, detail: String?) {
        synchronized(lock) {
            val editor = prefs.edit()
            if (local) {
                editor.putLong(KEY_LOCAL_FAILURE_AT, at)
                    .putString(KEY_LOCAL_FAILURE_KIND, kind.name)
                    .putString(KEY_LOCAL_FAILURE_DETAIL, detail?.take(MAX_DETAIL_LENGTH))
            } else {
                editor.putLong(KEY_REMOTE_FAILURE_AT, at)
                    .putString(KEY_REMOTE_FAILURE_KIND, kind.name)
                    .putString(KEY_REMOTE_FAILURE_DETAIL, detail?.take(MAX_DETAIL_LENGTH))
            }
            editor.commit()
            refresh()
        }
    }

    private fun refresh() {
        _status.value = readStatus()
    }

    private fun readStatus(): BackupStatus = BackupStatus(
        localDirectoryUri = prefs.getString(KEY_LOCAL_URI, null),
        localDirectoryName = prefs.getString(KEY_LOCAL_NAME, null),
        localLastSuccessAt = positiveOrNull(prefs.getLong(KEY_LOCAL_SUCCESS, 0L)),
        localLastFailure = readFailure(KEY_LOCAL_FAILURE_AT, KEY_LOCAL_FAILURE_KIND, KEY_LOCAL_FAILURE_DETAIL),
        webDavBaseUrl = prefs.getString(KEY_WEBDAV_URL, "") ?: "",
        webDavUsername = prefs.getString(KEY_WEBDAV_USER, "") ?: "",
        webDavRemoteDirectory = prefs.getString(KEY_WEBDAV_DIR, "") ?: "",
        webDavPasswordSaved = !prefs.getString(KEY_WEBDAV_PASSWORD, null).isNullOrBlank(),
        remoteLastSuccessAt = positiveOrNull(prefs.getLong(KEY_REMOTE_SUCCESS, 0L)),
        remoteLastFailure = readFailure(KEY_REMOTE_FAILURE_AT, KEY_REMOTE_FAILURE_KIND, KEY_REMOTE_FAILURE_DETAIL),
    )

    private fun readFailure(atKey: String, kindKey: String, detailKey: String): BackupFailure? {
        val at = prefs.getLong(atKey, 0L)
        if (at <= 0L) return null
        val kind = prefs.getString(kindKey, null)?.let {
            runCatching { BackupErrorKind.valueOf(it) }.getOrNull()
        } ?: BackupErrorKind.UNKNOWN
        return BackupFailure(kind = kind, at = at, detail = prefs.getString(detailKey, null))
    }

    private fun wrapPassword(password: CharArray): ByteArray {
        val bytes = String(password).toByteArray(Charsets.UTF_8)
        return try {
            cipher.wrap(bytes)
        } catch (e: Exception) {
            throw BackupException(BackupErrorKind.STORAGE, "Could not protect the WebDAV password", e)
        } finally {
            bytes.fill(0)
        }
    }

    private fun positiveOrNull(value: Long): Long? = if (value > 0L) value else null

    companion object {
        const val PREFS_NAME = "private_notes_backup"
        const val WEBDAV_KEY_ALIAS = "private_notes_webdav_v1"

        private const val KEY_LOCAL_URI = "local_tree_uri"
        private const val KEY_LOCAL_NAME = "local_tree_name"
        private const val KEY_LOCAL_SUCCESS = "local_last_success"
        private const val KEY_LOCAL_FAILURE_AT = "local_last_failure_at"
        private const val KEY_LOCAL_FAILURE_KIND = "local_last_failure_kind"
        private const val KEY_LOCAL_FAILURE_DETAIL = "local_last_failure_detail"
        private const val KEY_WEBDAV_URL = "webdav_base_url"
        private const val KEY_WEBDAV_USER = "webdav_username"
        private const val KEY_WEBDAV_DIR = "webdav_remote_directory"
        private const val KEY_WEBDAV_PASSWORD = "webdav_password_wrapped"
        private const val KEY_REMOTE_SUCCESS = "remote_last_success"
        private const val KEY_REMOTE_FAILURE_AT = "remote_last_failure_at"
        private const val KEY_REMOTE_FAILURE_KIND = "remote_last_failure_kind"
        private const val KEY_REMOTE_FAILURE_DETAIL = "remote_last_failure_detail"
        private const val MAX_DETAIL_LENGTH = 500
    }
}
