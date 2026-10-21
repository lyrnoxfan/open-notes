package com.example.privatenotes.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class LikePatternTest {

    @Test
    fun `plain text is left untouched`() {
        assertEquals("grocery list", LikePattern.escape("grocery list"))
    }

    @Test
    fun `percent becomes literal`() {
        assertEquals("100\\%", LikePattern.escape("100%"))
    }

    @Test
    fun `underscore becomes literal`() {
        assertEquals("a\\_b", LikePattern.escape("a_b"))
    }

    @Test
    fun `the escape character itself is escaped first`() {
        assertEquals("a\\\\b", LikePattern.escape("a\\b"))
    }

    @Test
    fun `mixed input escapes every special character`() {
        assertEquals("\\%\\_\\\\", LikePattern.escape("%_\\"))
    }
}
