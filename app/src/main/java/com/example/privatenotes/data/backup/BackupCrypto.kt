package com.example.privatenotes.data.backup

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.InputStream
import java.io.File
import java.io.FileOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-protected backup envelope (§6.3, §9.1).
 *
 * The JSON body is not wrapped in a second JSON object: an encrypted file has a binary header
 * so it can never be mistaken for the plaintext format, and the header is authenticated as
 * GCM additional data. Changing the iteration count, salt or IV in a file therefore fails
 * decryption instead of silently deriving a different key.
 *
 * ```
 *  0..7    magic "PNBKP001"
 *  8       KDF id (1 = PBKDF2-HMAC-SHA256)
 *  9..12   iteration count, big-endian int
 *  13      salt length (16)
 *  14..29  salt
 *  30      IV length (12)
 *  31..42  IV
 *  43..    AES-256-GCM ciphertext, tag appended
 * ```
 *
 * The password is never stored by the app; it exists only in memory for the duration of one
 * export or import.
 */
object BackupCrypto {

    private val MAGIC = "PNBKP001".toByteArray(Charsets.US_ASCII)
    private const val KDF_PBKDF2_SHA256: Byte = 1
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val MIN_ITERATIONS = 10_000
    private const val MAX_ITERATIONS = 2_000_000

    /** 210k PBKDF2-HMAC-SHA256 rounds: slow enough to matter, still usable on a phone. */
    const val DEFAULT_ITERATIONS = 210_000

    private const val HEADER_BYTES = 8 + 1 + 4 + 1 + SALT_BYTES + 1 + IV_BYTES

    fun isEncrypted(bytes: ByteArray): Boolean =
        bytes.size >= MAGIC.size && MAGIC.indices.all { bytes[it] == MAGIC[it] }

    fun encrypt(
        plaintext: ByteArray,
        password: CharArray,
        iterations: Int = DEFAULT_ITERATIONS,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        requirePassword(password)
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "Unsupported PBKDF2 iteration count" }

        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val header = header(iterations, salt, iv)
        val key = deriveKey(password, salt, iterations)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, iv),
            )
            cipher.updateAAD(header)
            val ciphertext = cipher.doFinal(plaintext)
            return ByteArray(header.size + ciphertext.size).also {
                header.copyInto(it, destinationOffset = 0)
                ciphertext.copyInto(it, destinationOffset = header.size)
            }
        } finally {
            Arrays.fill(key, 0)
        }
    }

    /**
     * Encrypts incrementally using the exact PNBKP001 envelope used by [encrypt]. The caller
     * must close this stream to write the GCM authentication tag; any failure invalidates output.
     */
    fun encryptingOutputStream(
        output: OutputStream,
        password: CharArray,
        iterations: Int = DEFAULT_ITERATIONS,
        random: SecureRandom = SecureRandom(),
    ): OutputStream {
        requirePassword(password)
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "Unsupported PBKDF2 iteration count" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val header = header(iterations, salt, iv)
        val key = deriveKey(password, salt, iterations)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(header)
            output.write(header)
            return object : FilterOutputStream(output) {
                private var finished = false
                override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    check(!finished) { "Encrypted backup stream is closed" }
                    val encrypted = cipher.update(bytes, offset, length)
                    if (encrypted != null && encrypted.isNotEmpty()) out.write(encrypted)
                }
                override fun close() {
                    if (finished) return
                    finished = true
                    var failure: Throwable? = null
                    try {
                        out.write(cipher.doFinal())
                        out.flush()
                    } catch (error: Throwable) {
                        failure = error
                        if (error is IOException) throw error
                        throw IOException("Could not finalize encrypted backup", error)
                    } finally {
                        Arrays.fill(key, 0)
                        try { out.close() } catch (closeError: Throwable) {
                            if (failure != null) failure.addSuppressed(closeError) else throw closeError
                        }
                    }
                }
            }
        } catch (failure: Throwable) {
            Arrays.fill(key, 0)
            throw failure
        }
    }

    fun decrypt(bytes: ByteArray, password: CharArray): ByteArray {
        requirePassword(password)
        if (!isEncrypted(bytes)) {
            throw BackupException(BackupErrorKind.INVALID_FORMAT, "File is not an encrypted backup")
        }
        val header = try {
            parseHeader(bytes)
        } catch (e: BufferUnderflowException) {
            throw corrupt("Encrypted backup header is truncated", e)
        }
        if (header.kdf != KDF_PBKDF2_SHA256) {
            throw corrupt("Encrypted backup uses an unknown key derivation")
        }
        if (header.iterations !in MIN_ITERATIONS..MAX_ITERATIONS) {
            throw corrupt("Encrypted backup uses an unsupported iteration count")
        }
        val key = deriveKey(password, header.salt, header.iterations)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, header.iv),
            )
            cipher.updateAAD(bytes.copyOfRange(0, HEADER_BYTES))
            return try {
                cipher.doFinal(bytes, HEADER_BYTES, bytes.size - HEADER_BYTES)
            } catch (e: GeneralSecurityException) {
                // GCM cannot tell a wrong password from tampering; both must be rejected and
                // presented the same way to the user.
                throw BackupException(
                    BackupErrorKind.WRONG_PASSWORD_OR_CORRUPT,
                    "Could not decrypt the backup: wrong password or damaged file",
                    e,
                )
            }
        } finally {
            Arrays.fill(key, 0)
        }
    }

    /** Decrypts PNB to an untrusted temporary plaintext file and verifies its GCM tag before returning. */
    fun decryptToTemporaryFile(input: InputStream, password: CharArray, outputFile: File, maxEncryptedBytes: Long = (outputFile.parentFile?.usableSpace ?: 0L) / 2) {
        requirePassword(password)
        val headerBytes = ByteArray(HEADER_BYTES)
        var got = 0
        while (got < headerBytes.size) { val n = input.read(headerBytes, got, headerBytes.size - got); if (n < 0) throw corrupt("Encrypted backup header is truncated"); got += n }
        val header = try { parseHeader(headerBytes) } catch (e: Exception) { throw corrupt("Encrypted backup header is invalid", e) }
        if (header.kdf != KDF_PBKDF2_SHA256 || header.iterations !in MIN_ITERATIONS..MAX_ITERATIONS) throw corrupt("Encrypted backup uses unsupported key derivation parameters")
        val key = deriveKey(password, header.salt, header.iterations)
        var total = HEADER_BYTES.toLong()
        var success = false
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, header.iv))
            cipher.updateAAD(headerBytes)
            FileOutputStream(outputFile).use { out ->
                var tail = ByteArray(0)
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    total += n
                    if (total > maxEncryptedBytes) throw corrupt("Encrypted backup exceeds import storage limit")
                    val combined = ByteArray(tail.size + n)
                    tail.copyInto(combined); buffer.copyInto(combined, tail.size, 0, n)
                    val process = (combined.size - 16).coerceAtLeast(0)
                    if (process > 0) cipher.update(combined, 0, process)?.let(out::write)
                    tail = combined.copyOfRange(process, combined.size)
                }
                if (tail.size != 16) throw corrupt("Encrypted backup is truncated")
                try { cipher.doFinal(tail)?.let(out::write) }
                catch (e: GeneralSecurityException) { throw BackupException(BackupErrorKind.WRONG_PASSWORD_OR_CORRUPT, "Could not decrypt the backup: wrong password or damaged file", e) }
                out.fd.sync()
            }
            success = true
        } catch (e: BackupException) { throw e }
        catch (e: Exception) { throw BackupException(BackupErrorKind.WRONG_PASSWORD_OR_CORRUPT, "Could not decrypt the backup: wrong password or damaged file", e) }
        finally { Arrays.fill(key, 0); if (!success) outputFile.delete() }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
        } catch (e: GeneralSecurityException) {
            throw BackupException(BackupErrorKind.UNKNOWN, "PBKDF2 is unavailable", e)
        } finally {
            spec.clearPassword()
        }
    }

    private fun header(iterations: Int, salt: ByteArray, iv: ByteArray): ByteArray =
        ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN).apply {
            put(MAGIC)
            put(KDF_PBKDF2_SHA256)
            putInt(iterations)
            put(SALT_BYTES.toByte())
            put(salt)
            put(IV_BYTES.toByte())
            put(iv)
        }.array()

    private class Header(val kdf: Byte, val iterations: Int, val salt: ByteArray, val iv: ByteArray)

    private fun parseHeader(bytes: ByteArray): Header {
        if (bytes.size < HEADER_BYTES) throw BufferUnderflowException()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(MAGIC.size).also { buffer.get(it) }
        if (!magic.contentEquals(MAGIC)) throw BufferUnderflowException()
        val kdf = buffer.get()
        val iterations = buffer.int
        val saltLength = buffer.get().toInt() and 0xFF
        if (saltLength != SALT_BYTES) throw corrupt("Encrypted backup has an invalid salt")
        val salt = ByteArray(saltLength).also { buffer.get(it) }
        val ivLength = buffer.get().toInt() and 0xFF
        if (ivLength != IV_BYTES) throw corrupt("Encrypted backup has an invalid IV")
        val iv = ByteArray(ivLength).also { buffer.get(it) }
        return Header(kdf, iterations, salt, iv)
    }

    private fun requirePassword(password: CharArray) {
        if (password.isEmpty()) {
            throw BackupException(BackupErrorKind.INVALID_FORMAT, "A password is required for an encrypted backup")
        }
    }

    private fun corrupt(message: String, cause: Throwable? = null): BackupException =
        BackupException(BackupErrorKind.CORRUPT_FILE, message, cause)
}

/**
 * Both the backup password and the sync password arrive as a nullable `CharArray`.
 *
 * The stdlib only offers `isNullOrEmpty` for sequences and arrays; this keeps the call sites
 * readable instead of sprinkling `== null || isEmpty()` checks around.
 */
internal fun CharArray?.isNullOrEmpty(): Boolean = this == null || this.isEmpty()
