package com.example.privatenotes.feature.notes

import kotlin.system.measureTimeMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `plainExcerpt` runs for every visible list row, so its cost matters as much as its output.
 *
 * The behavioural assertions pin the output; the last test measures the 500 KB case that the
 * truncation was added for and prints the number the perf report quotes.
 */
class PlainExcerptTest {

    @Test
    fun stripsMarkdownPunctuation() {
        assertEquals("标题 正文", plainExcerpt("**标题** `正文`"))
        // List markers stay: the existing punctuation set never included "-" and this change does
        // not alter output, only the amount of text the regexes see.
        assertEquals("- 一 - 二 - 三", plainExcerpt("- 一\n- 二\n- 三"))
    }

    @Test
    fun codeFenceDisappearsBeforeTruncation() {
        val text = "开头\n```kotlin\nval x = 1\n```\n结尾"
        assertEquals("开头 结尾", plainExcerpt(text))
    }

    @Test
    fun outputIsBoundedEvenForAHugeNote() {
        val huge = "长".repeat(500_000)
        val excerpt = plainExcerpt(huge)
        assertTrue("excerpt must be bounded, was ${excerpt.length}", excerpt.length <= EXCERPT_CHARS)
    }

    @Test
    fun fiveHundredKilobytesCostMillisecondsNotSeconds() {
        val huge = buildString {
            repeat(20_000) { appendLine("**第 $it 行** 的正文，包含 `代码` 与 [链接](https://example.com)。") }
        }
        assertTrue("fixture must be large, was ${huge.length}", huge.length > 500_000)

        // Both implementations measured in the same run, so the JIT state is the same for both.
        repeat(5) { legacyExcerpt(huge); plainExcerpt(huge) }
        val before = measureTimeMillis { repeat(20) { legacyExcerpt(huge) } }
        val after = measureTimeMillis { repeat(20) { plainExcerpt(huge) } }
        println("PERF plainExcerpt_500kb_20x before=${before}ms after=${after}ms")
        println("PERF plainExcerpt_per_call before=${before / 20.0}ms after=${after / 20.0}ms")

        assertTrue(
            "20 calls on a 500 KB note took ${after}ms - truncation is not doing its job",
            after < 2_000,
        )
    }

    /** The implementation before this change: three regexes over the whole body. */
    private fun legacyExcerpt(markdown: String): String =
        markdown
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("[#>*_`~\\[\\]()]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
}
