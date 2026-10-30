package com.example.privatenotes.feature.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineMediaSyntaxTest {
    @Test fun insertsMediaAsItsOwnBlockAtCaret() {
        assertEquals("before\n\n${InlineMediaSyntax.reference("abc_1")}\n\nafter",
            InlineMediaSyntax.insert("beforeafter", 6, "abc_1"))
    }

    @Test fun splitPreservesTextAndOrdersMedia() {
        val first = InlineMediaSyntax.reference("one")
        val second = InlineMediaSyntax.reference("two")
        assertEquals(listOf("before", "one", "middle", "two", "after"),
            InlineMediaSyntax.split("before$first\n\nmiddle${second}after").mapNotNull { part ->
                part.mediaId ?: part.text?.trim('\n')?.takeIf(String::isNotEmpty)
            })
    }

    @Test fun removeMediaKeepsSurroundingTextAndOtherMedia() {
        val first = InlineMediaSyntax.reference("one")
        val second = InlineMediaSyntax.reference("two")
        val result = InlineMediaSyntax.remove("before\n\n$first\n\n$second\n\nafter", "one")
        assertTrue(result.contains("before"))
        assertTrue(result.contains("after"))
        assertEquals(setOf("two"), InlineMediaSyntax.ids(result))
    }

    @Test fun ignoresMalformedAndUnrelatedMarkdownImages() {
        assertEquals(emptySet<String>(), InlineMediaSyntax.ids("![x](https://example.com/x.png) ![media](private-note-media://bad.id)"))
    }
    @Test fun editingBlocksNeverExposeMediaMarkersAndPreserveEveryCharacter() {
        val body = "前文\n\n" + InlineMediaSyntax.reference("one") + InlineMediaSyntax.reference("two") + "\n后文"
        val blocks = InlineMediaSyntax.blocks(body)
        assertEquals(3, blocks.filterIsInstance<InlineMediaSyntax.Block.Text>().size)
        assertTrue(blocks.filterIsInstance<InlineMediaSyntax.Block.Text>().none { it.value.contains("private-note-media://") })
        val middle = blocks.filterIsInstance<InlineMediaSyntax.Block.Text>()[1]
        val edited = InlineMediaSyntax.replaceText(body, middle.key, "新段落")
        assertEquals(listOf("前文\n\n", "新段落", "\n后文"), InlineMediaSyntax.blocks(edited).filterIsInstance<InlineMediaSyntax.Block.Text>().map { it.value })
        assertEquals(setOf("one", "two"), InlineMediaSyntax.ids(edited))
        val before = blocks.filterIsInstance<InlineMediaSyntax.Block.Text>().first()
        assertEquals("" + InlineMediaSyntax.reference("one") + InlineMediaSyntax.reference("two") + "\n后文",
            InlineMediaSyntax.replaceText(body, before.key, ""))
    }

    @Test fun stableKeysSurviveTextEditingAndSupportEmptyBodySlots() {
        val initial = InlineMediaSyntax.reference("one")
        val before = InlineMediaSyntax.blocks(initial).filterIsInstance<InlineMediaSyntax.Block.Text>()
        assertEquals(listOf("", ""), before.map { it.value })
        val changed = InlineMediaSyntax.replaceText(initial, before.last().key, "after\n")
        assertEquals(before.map { it.key }, InlineMediaSyntax.blocks(changed).filterIsInstance<InlineMediaSyntax.Block.Text>().map { it.key })
        assertEquals("after\n", InlineMediaSyntax.remove(changed, "one"))
    }
    @Test fun listSnippetHidesInternalMarkers() {
        assertEquals("before after", plainExcerpt("before" + InlineMediaSyntax.reference("one") + "after"))
    }
}
