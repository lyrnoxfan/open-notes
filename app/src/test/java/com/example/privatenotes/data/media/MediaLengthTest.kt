package com.example.privatenotes.data.media

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.serialization.json.Json

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaLengthTest {
    @Test fun chunkArithmeticUsesLongBeforeAddingOrMultiplying() {
        for (size in listOf(1L, MediaRules.CHUNK_SIZE.toLong(), Int.MAX_VALUE.toLong(),
            Int.MAX_VALUE.toLong() + 1, 3L*1024*1024*1024 + 17, MediaRules.MAX_BYTES)) {
            val count = MediaRules.chunkCount(size)
            assertEquals(size, (count - 1).toLong() * MediaRules.CHUNK_SIZE + MediaRules.chunkBytes(size, count - 1))
            assertTrue(MediaRules.chunkBytes(size, count - 1) in 1..MediaRules.CHUNK_SIZE)
        }
        assertEquals(MediaRules.MAX_NOTE_BYTES, MediaRules.noteBytes(listOf(MediaRules.MAX_BYTES)))
        assertThrows(IllegalArgumentException::class.java) { MediaRules.noteBytes(listOf(MediaRules.MAX_BYTES, 1)) }
        assertThrows(IllegalArgumentException::class.java) { MediaRules.chunkCount(0) }
        assertThrows(IllegalArgumentException::class.java) { MediaRules.chunkCount(Long.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { MediaRules.chunkBytes(10, -1) }
    }
    @Test fun seeksAcrossSignedIntBoundaryWithoutAllocatingWholeMedia() {
        val size = 3L * 1024*1024*1024 + 17
        val loaded = mutableListOf<Int>()
        val source = ChunkedMediaSource(size) { index ->
            loaded += index
            ByteArray(MediaRules.chunkBytes(size, index)) { ((index.toLong()*MediaRules.CHUNK_SIZE + it) % 251).toByte() }
        }
        val output = ByteArray(32)
        for (position in listOf(Int.MAX_VALUE.toLong()-7, Int.MAX_VALUE.toLong()+1, size-9)) {
            val n = minOf(32L, size-position).toInt()
            assertEquals(n, source.readAt(position, output, 0, 32))
            assertArrayEquals(ByteArray(n) { ((position+it)%251).toByte() }, output.copyOf(n))
        }
        assertEquals(size, source.getSize())
        assertTrue(loaded.size <= 5)
        assertEquals(-1, source.readAt(size, output, 0, 1))
        source.close()
    }
    @Test fun backupLongLengthsDecodeOldIntegerJsonAndRoundTripAboveTwoGiB() {
        val legacy = """{"id":"one","name":"old.png","mime":"image/png","size":12,"sha256":"hash","base64":""}"""
        assertEquals(12L, Json.decodeFromString(BackupMedia.serializer(), legacy).size)
        val large = BackupMedia("two", "large.mp4", "video/mp4", 3L*1024*1024*1024, "hash", "")
        assertEquals(large, Json.decodeFromString(BackupMedia.serializer(), Json.encodeToString(BackupMedia.serializer(), large)))
    }
}
