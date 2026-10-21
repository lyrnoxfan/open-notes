package com.example.privatenotes.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-3 acceptance: one newline in plain text renders as a line break, matching the blog rule
 * rather than CommonMark's paragraph-only behaviour (§7.2).
 */
class MarkdownLineBreaksTest {

    @Test
    fun `a single newline becomes a hard break`() {
        assertEquals("第一行  \n第二行", MarkdownLineBreaks.apply("第一行\n第二行"))
    }

    @Test
    fun `a blank line is left as a paragraph break`() {
        assertEquals("第一段\n\n第二段", MarkdownLineBreaks.apply("第一段\n\n第二段"))
    }

    @Test
    fun `several consecutive lines all get breaks`() {
        val result = MarkdownLineBreaks.apply("a\nb\nc")

        assertEquals("a  \nb  \nc", result)
    }

    @Test
    fun `an existing hard break is not doubled`() {
        assertEquals("a  \nb", MarkdownLineBreaks.apply("a  \nb"))
    }

    @Test
    fun `a backslash line break is left alone`() {
        assertEquals("a\\\nb", MarkdownLineBreaks.apply("a\\\nb"))
    }

    @Test
    fun `fenced code keeps its content verbatim`() {
        val source = "说明\n\n```\nline1\nline2\n```\n\n结束"
        val result = MarkdownLineBreaks.apply(source)

        assertTrue("code block text must not be rewritten", result.contains("```\nline1\nline2\n```"))
        assertTrue("the fence itself must stay a fence", !result.contains("```  "))
    }

    @Test
    fun `tilde fences are treated the same way`() {
        val source = "~~~\ncode\ncode\n~~~"
        assertEquals(source, MarkdownLineBreaks.apply(source))
    }

    @Test
    fun `text after a code block returns to normal handling`() {
        val source = "```\ncode\n```\ntext1\ntext2"
        val result = MarkdownLineBreaks.apply(source)

        assertTrue(result.endsWith("text1  \ntext2"))
    }

    @Test
    fun `a trailing newline does not invent an extra break`() {
        assertEquals("a  \nb\n", MarkdownLineBreaks.apply("a\nb\n"))
    }

    @Test
    fun `empty input stays empty`() {
        assertEquals("", MarkdownLineBreaks.apply(""))
    }

    @Test
    fun `list items stay separate list items`() {
        val result = MarkdownLineBreaks.apply("- 一\n- 二")

        assertEquals("- 一  \n- 二", result)
    }
}
