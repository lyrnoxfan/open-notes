package com.example.privatenotes.ui.markdown

/**
 * Applies the project's single-newline rule to Markdown source before rendering (§7.2).
 *
 * The blog convention is that one newline in plain text is a visible line break, whereas
 * CommonMark treats it as a space and only honours a blank line between paragraphs. Rather than
 * hoping a renderer's defaults line up, the source is adjusted explicitly: a line that is
 * followed immediately by another line of text gets a hard break marker.
 *
 * Left alone: fenced code blocks (their content is literal), blank lines (already a paragraph
 * break), lines that already end in a hard break, and fence markers themselves.
 */
object MarkdownLineBreaks {

    fun apply(markdown: String): String {
        if (markdown.isEmpty()) return markdown
        val lines = markdown.split("\n")
        val result = ArrayList<String>(lines.size)
        var insideFence = false
        var fenceMarker: String? = null

        for (index in lines.indices) {
            val line = lines[index]
            val marker = fenceMarkerOf(line)

            if (marker != null) {
                if (!insideFence) {
                    insideFence = true
                    fenceMarker = marker
                } else if (marker == fenceMarker) {
                    insideFence = false
                    fenceMarker = null
                }
                result += line
                continue
            }

            if (insideFence || !needsHardBreak(lines, index)) {
                result += line
                continue
            }

            result += line + HARD_BREAK
        }

        return result.joinToString("\n")
    }

    private fun needsHardBreak(lines: List<String>, index: Int): Boolean {
        val line = lines[index]
        if (line.isBlank()) return false
        val next = lines.getOrNull(index + 1) ?: return false
        if (next.isBlank()) return false
        if (line.endsWith(HARD_BREAK) || line.endsWith(BACKSLASH_BREAK)) return false
        return true
    }

    /** Returns the fence marker (` ``` ` or `~~~`) when the line opens or closes a fenced block. */
    private fun fenceMarkerOf(line: String): String? {
        val trimmed = line.trimStart()
        return when {
            trimmed.startsWith("```") -> "```"
            trimmed.startsWith("~~~") -> "~~~"
            else -> null
        }
    }

    /** Two trailing spaces are CommonMark's hard line break. */
    private const val HARD_BREAK = "  "
    private const val BACKSLASH_BREAK = "\\"
}
