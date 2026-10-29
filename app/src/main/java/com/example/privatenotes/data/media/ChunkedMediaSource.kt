package com.example.privatenotes.data.media

import android.media.MediaDataSource
import java.io.IOException

/** Random access to encrypted SQLCipher chunks; at most one decrypted chunk is cached. */
internal class ChunkedMediaSource(private val size: Int, private val load: (Int) -> ByteArray?) : MediaDataSource() {
    private var closed = false
    private var cachedPosition = -1
    private var cached: ByteArray? = null
    @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, count: Int): Int {
        if (closed) throw IOException("Media source closed")
        require(position >= 0 && offset >= 0 && count >= 0 && offset <= buffer.size - count)
        if (count == 0) return 0
        if (position >= size) return -1
        val total = minOf(count, size - position.toInt())
        var copied = 0
        try {
            while (copied < total) {
                val absolute = position.toInt() + copied
                val index = absolute / MediaRules.CHUNK_SIZE
                if (cachedPosition != index) {
                    cached?.fill(0)
                    cached = load(index) ?: throw IOException("Missing media chunk")
                    cachedPosition = index
                }
                val bytes = cached!!
                val expected = minOf(MediaRules.CHUNK_SIZE, size - index * MediaRules.CHUNK_SIZE)
                if (bytes.size != expected) throw IOException("Invalid media chunk")
                val within = absolute % MediaRules.CHUNK_SIZE
                val n = minOf(total - copied, bytes.size - within)
                bytes.copyInto(buffer, offset + copied, within, within + n)
                copied += n
            }
            return copied
        } catch (e: IOException) { throw e }
        catch (e: Exception) { throw IOException("Cannot read encrypted media", e) }
    }
    @Synchronized override fun getSize() = size.toLong()
    @Synchronized override fun close() { closed = true; cached?.fill(0); cached = null }
}
