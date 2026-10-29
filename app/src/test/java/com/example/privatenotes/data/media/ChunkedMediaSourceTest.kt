package com.example.privatenotes.data.media

import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChunkedMediaSourceTest {
    @Test fun randomAccessCrossesBoundariesAndDoesNotLoadWholeVideo() {
        val original = ByteArray(MediaRules.CHUNK_SIZE * 3 + 9) { (it % 251).toByte() }
        val loaded = mutableListOf<Int>()
        val source = ChunkedMediaSource(original.size) { position ->
            loaded += position
            original.copyOfRange(position * MediaRules.CHUNK_SIZE, minOf((position + 1) * MediaRules.CHUNK_SIZE, original.size))
        }
        val output = ByteArray(20)
        assertEquals(10, source.readAt((MediaRules.CHUNK_SIZE - 3).toLong(), output, 5, 10))
        assertArrayEquals(original.copyOfRange(MediaRules.CHUNK_SIZE - 3, MediaRules.CHUNK_SIZE + 7), output.copyOfRange(5, 15))
        assertEquals(listOf(0, 1), loaded)
        assertEquals(3, source.readAt((MediaRules.CHUNK_SIZE + 3).toLong(), output, 0, 3))
        assertEquals(listOf(0, 1), loaded)
        assertEquals(9, source.readAt((MediaRules.CHUNK_SIZE * 3).toLong(), output, 0, 20))
        assertEquals(listOf(0, 1, 3), loaded)
        assertEquals(-1, source.readAt(Long.MAX_VALUE, output, 0, 1))
        assertEquals(0, source.readAt(0, output, 0, 0))
        assertEquals(original.size.toLong(), source.size)
        source.close()
        assertThrows(IOException::class.java) { source.readAt(0, output, 0, 1) }
    }
    @Test fun missingCorruptChunksAndInvalidBoundsFailClosed() {
        val missing = ChunkedMediaSource(20) { null }
        val corrupt = ChunkedMediaSource(20) { byteArrayOf(1) }
        val output = ByteArray(2)
        assertThrows(IOException::class.java) { missing.readAt(0, output, 0, 1) }
        assertThrows(IOException::class.java) { corrupt.readAt(0, output, 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { missing.readAt(-1, output, 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { missing.readAt(0, output, 1, 2) }
    }
}
