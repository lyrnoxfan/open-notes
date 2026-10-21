package com.example.privatenotes.data.backup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * HTTPS WebDAV uploader (§9.3).
 *
 * Deliberately narrow: it creates the configured collection, PUTs one uniquely named file,
 * and reads it back. It never lists, overwrites, moves or deletes anything except a file whose
 * own verification just failed. Certificate validation is the platform default and redirects
 * are refused, so credentials cannot be forwarded to a plaintext or foreign endpoint.
 */
interface WebDavRemote {
    suspend fun testConnection()
    suspend fun upload(fileName: String, bytes: ByteArray, contentType: MediaType): WebDavUploadResult
    suspend fun verify(fileName: String, expected: ByteArray): WebDavVerification
    suspend fun deleteQuietly(fileName: String)
}

class WebDavClient(
    private val http: OkHttpClient,
    private val settings: WebDavSettings,
    private val password: String,
) : WebDavRemote {

    private val baseUrl: HttpUrl = WebDavConfigValidator.parseBaseUrl(settings.baseUrl)
    private val remoteSegments: List<String> = WebDavConfigValidator.parseRemoteDirectory(settings.remoteDirectory)

    override suspend fun testConnection() {
        val directory = directoryUrl()
        var response = propfind(directory)
        try {
            if (response.code == HTTP_NOT_FOUND) {
                response.close()
                createDirectories()
                response = propfind(directory)
            }
            response.use {
                if (!it.isSuccessful) throw statusFailure(it.code, "test the connection")
            }
        } finally {
            response.close()
        }
    }

    override suspend fun upload(
        fileName: String,
        bytes: ByteArray,
        contentType: MediaType,
    ): WebDavUploadResult {
        requireSafeFileName(fileName)
        ensureDirectory()
        val request = authorized(
            Request.Builder()
                .url(fileUrl(fileName))
                .put(bytes.toRequestBody(contentType)),
        )
        var response = execute(request)
        if (response.code == HTTP_CONFLICT) {
            response.close()
            createDirectories()
            response = execute(request)
        }
        response.use {
            if (it.code !in setOf(200, 201, 204)) throw statusFailure(it.code, "upload the backup")
        }
        return WebDavUploadResult(
            remoteFileName = fileName,
            directoryUrl = directoryUrl().toString(),
            sizeBytes = bytes.size.toLong(),
        )
    }

    /**
     * Reads the uploaded object back and compares it with the bytes that were sent.
     *
     * Servers that refuse GET fall back to PROPFIND's content length, which is weaker but still
     * catches truncated uploads. The caller only records success after this returns.
     */
    override suspend fun verify(fileName: String, expected: ByteArray): WebDavVerification {
        requireSafeFileName(fileName)
        val request = authorized(Request.Builder().url(fileUrl(fileName)).get())
        val response = execute(request)
        response.use { res ->
            when {
                res.code == 200 -> {
                    val body = res.body ?: throw storageFailure("Remote backup has no body")
                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0
                    try {
                        body.byteStream().use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > expected.size) {
                                    throw storageFailure("Remote backup is larger than the uploaded file")
                                }
                                digest.update(buffer, 0, read)
                            }
                        }
                    } catch (e: IOException) {
                        throw BackupException(
                            BackupErrorKind.NETWORK,
                            "Could not read the remote backup back",
                            e,
                            retryable = true,
                        )
                    }
                    if (total != expected.size || !digest.digest().contentEquals(sha256(expected))) {
                        throw storageFailure("Remote backup did not read back identical")
                    }
                    return WebDavVerification.FULL
                }

                res.code == 405 || res.code == 501 -> {
                    val length = propfindLength(fileName)
                    if (length == expected.size.toLong()) return WebDavVerification.LENGTH_ONLY
                    throw storageFailure("Remote backup length does not match the uploaded file")
                }

                else -> throw statusFailure(res.code, "verify the backup")
            }
        }
    }

    /** Best-effort removal of a file that failed verification; never touches other files. */
    override suspend fun deleteQuietly(fileName: String) {
        try {
            requireSafeFileName(fileName)
            val request = authorized(Request.Builder().url(fileUrl(fileName)).delete())
            execute(request).use { if (!it.isSuccessful) return }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort: a failed cleanup must not mask the verification failure.
        }
    }

    private suspend fun ensureDirectory() {
        val response = propfind(directoryUrl())
        response.use {
            if (it.isSuccessful) return
            if (it.code != HTTP_NOT_FOUND) throw statusFailure(it.code, "open the remote directory")
        }
        createDirectories()
    }

    private suspend fun createDirectories() {
        if (remoteSegments.isEmpty()) return
        for (index in 1..remoteSegments.size) {
            val builder = baseUrl.newBuilder()
            for (segmentIndex in 0 until index) builder.addPathSegment(remoteSegments[segmentIndex])
            builder.addPathSegment("")
            val request = authorized(Request.Builder().url(builder.build()).method("MKCOL", null))
            execute(request).use { response ->
                when (response.code) {
                    200, 201, 204, 301, 302, 405 -> Unit // created, already there, or redirected to a slash
                    401, 403 -> throw unauthorized()
                    409 -> throw serverFailure("A parent collection is missing on the server", response.code)
                    else -> throw statusFailure(response.code, "create the remote directory")
                }
            }
        }
    }

    private suspend fun propfind(url: HttpUrl): Response {
        val body = """<?xml version="1.0" encoding="utf-8"?>
            |<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>
        """.trimMargin()
        val request = authorized(
            Request.Builder()
                .url(url)
                .header("Depth", "0")
                .header("Content-Type", "application/xml; charset=utf-8")
                .method("PROPFIND", body.toRequestBody(XML_MEDIA_TYPE)),
        )
        return execute(request)
    }

    private suspend fun propfindLength(fileName: String): Long {
        val response = propfind(fileUrl(fileName))
        response.use {
            if (!it.isSuccessful) throw statusFailure(it.code, "read the remote file size")
            val xml = readBodyText(it)
            return CONTENT_LENGTH_REGEX.find(xml)?.groupValues?.getOrNull(1)?.toLongOrNull()
                ?: throw storageFailure("Server did not report the remote file size")
        }
    }

    private fun readBodyText(response: Response): String = try {
        response.body?.string().orEmpty()
    } catch (e: IOException) {
        throw BackupException(BackupErrorKind.NETWORK, "Could not read the WebDAV response", e, retryable = true)
    }

    private fun authorized(builder: Request.Builder): Request =
        builder
            .header("Authorization", Credentials.basic(settings.username, password, Charsets.UTF_8))
            .header("User-Agent", USER_AGENT)
            .build()

    private suspend fun execute(request: Request): Response = withContext(Dispatchers.IO) {
        try {
            http.newCall(request).execute()
        } catch (e: SSLException) {
            throw BackupException(
                BackupErrorKind.SERVER,
                "TLS handshake failed; the server certificate is not trusted",
                e,
            )
        } catch (e: IOException) {
            throw BackupException(BackupErrorKind.NETWORK, "Could not reach the WebDAV server", e, retryable = true)
        } catch (e: IllegalArgumentException) {
            throw BackupException(BackupErrorKind.INVALID_CONFIG, "Invalid WebDAV URL", e)
        }
    }

    private fun statusFailure(code: Int, action: String): BackupException = when (code) {
        401, 403 -> unauthorized()
        404 -> BackupException(BackupErrorKind.SERVER, "WebDAV path was not found while trying to $action")
        409 -> BackupException(BackupErrorKind.SERVER, "WebDAV collection conflict while trying to $action")
        507 -> BackupException(BackupErrorKind.STORAGE, "WebDAV server is out of storage")
        in 500..599 -> serverFailure("WebDAV server error while trying to $action", code)
        in 300..399 -> BackupException(
            BackupErrorKind.SERVER,
            "WebDAV server redirected the request; redirects are refused",
        )
        else -> BackupException(BackupErrorKind.SERVER, "WebDAV request failed with HTTP $code while trying to $action")
    }

    private fun unauthorized(): BackupException = BackupException(
        BackupErrorKind.UNAUTHORIZED,
        "WebDAV authentication failed",
    )

    private fun serverFailure(message: String, code: Int): BackupException = BackupException(
        BackupErrorKind.SERVER,
        "$message (HTTP $code)",
        retryable = code in 500..599,
    )

    private fun storageFailure(message: String, cause: Throwable? = null): BackupException =
        BackupException(BackupErrorKind.STORAGE, message, cause, retryable = true)

    private fun directoryUrl(): HttpUrl {
        val builder = baseUrl.newBuilder()
        for (segment in remoteSegments) builder.addPathSegment(segment)
        builder.addPathSegment("")
        return builder.build()
    }

    private fun fileUrl(fileName: String): HttpUrl {
        val builder = baseUrl.newBuilder()
        for (segment in remoteSegments) builder.addPathSegment(segment)
        builder.addPathSegment(fileName)
        return builder.build()
    }

    private fun requireSafeFileName(fileName: String) {
        if (fileName.isBlank() ||
            fileName.length > 200 ||
            fileName.any { it == '/' || it == '\\' || it == '\u0000' } ||
            fileName == "." ||
            fileName == ".."
        ) {
            throw BackupException(BackupErrorKind.INVALID_CONFIG, "Unsafe remote file name")
        }
    }

    companion object {
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_CONFLICT = 409
        private const val USER_AGENT = "open-notes-backup/1.0"
        private val XML_MEDIA_TYPE: MediaType = "application/xml; charset=utf-8".toMediaType()
        private val CONTENT_LENGTH_REGEX =
            Regex("""(?is)<(?:[a-z0-9]+:)?getcontentlength[^>]*>\s*(\d+)\s*<""")

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build()
    }
}

enum class WebDavVerification { FULL, LENGTH_ONLY }

data class WebDavUploadResult(
    val remoteFileName: String,
    val directoryUrl: String,
    val sizeBytes: Long,
)

/**
 * Configuration checks shared by the settings screen and the uploader.
 *
 * `http://` is rejected outright: a WebDAV password and a full copy of the notes would travel
 * in clear text. Credentials embedded in the URL are rejected too, because they tend to end up
 * in logs and status messages.
 */
object WebDavConfigValidator {

    fun parseBaseUrl(input: String): HttpUrl {
        val trimmed = input.trim()
        val url = trimmed.toHttpUrlOrNull()
            ?: throw invalid("WebDAV 地址格式不正确")
        if (!url.isHttps) throw invalid("WebDAV 必须使用 HTTPS 地址")
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw invalid("请不要把用户名或密码写进 WebDAV 地址")
        }
        if (url.fragment != null) throw invalid("WebDAV 地址不能包含 # 片段")
        if (url.host.isBlank()) throw invalid("WebDAV 地址缺少主机名")
        return url
    }

    fun parseRemoteDirectory(input: String): List<String> {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return emptyList()
        if ('\\' in trimmed) throw invalid("远端目录请使用 / 分隔")
        val segments = trimmed.split('/').filter { it.isNotEmpty() }
        for (segment in segments) {
            if (segment == "." || segment == "..") throw invalid("远端目录不能包含 . 或 ..")
        }
        return segments
    }

    fun validateUsername(username: String) {
        if (username.isBlank()) throw invalid("请填写 WebDAV 用户名")
        if (':' in username) throw invalid("WebDAV 用户名不能包含冒号")
    }

    fun validate(baseUrl: String, username: String, remoteDirectory: String) {
        parseBaseUrl(baseUrl)
        validateUsername(username)
        parseRemoteDirectory(remoteDirectory)
    }

    fun normalizedRemoteDirectory(input: String): String =
        parseRemoteDirectory(input).joinToString("/")

    private fun invalid(message: String): BackupException =
        BackupException(BackupErrorKind.INVALID_CONFIG, message)
}

private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
