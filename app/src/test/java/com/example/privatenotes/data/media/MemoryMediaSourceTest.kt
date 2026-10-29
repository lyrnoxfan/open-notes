package com.example.privatenotes.data.media

import com.example.privatenotes.feature.notes.MemoryMediaSource
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryMediaSourceTest {
    @Test fun randomAccessEofAndCloseNeverUseAPlaintextFile() {
        val source = MemoryMediaSource(byteArrayOf(1,2,3,4))
        val output = ByteArray(5)
        assertEquals(4L, source.size)
        assertEquals(2, source.readAt(2, output, 1, 4))
        assertArrayEquals(byteArrayOf(0,3,4,0,0), output)
        assertEquals(-1, source.readAt(Long.MAX_VALUE, output, 0, 1))
        assertEquals(0, source.readAt(0, output, 0, 0))
        assertThrows(IllegalArgumentException::class.java) { source.readAt(-1, output, 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { source.readAt(0, output, 4, 2) }
        source.close()
        assertEquals(0L, source.size)
        assertEquals(-1, source.readAt(0, output, 0, 1))
    }
}
