package com.example.privatenotes.feature.notes

/** Stable, portable note-body references for encrypted media rows. The renderer replaces these
 * references with the actual media; their bytes never enter the note text or Markdown backup. */
internal object InlineMediaSyntax {
    private val pattern = Regex("!\\[media]\\(private-note-media://([A-Za-z0-9_-]{1,100})\\)")
    data class Part(val text: String? = null, val mediaId: String? = null)

    fun reference(id: String) = "![media](private-note-media://$id)"
    fun ids(body: String): Set<String> = pattern.findAll(body).map { it.groupValues[1] }.toSet()

    fun split(body: String): List<Part> {
        val result = mutableListOf<Part>()
        var cursor = 0
        for (match in pattern.findAll(body)) {
            if (match.range.first > cursor) result += Part(text = body.substring(cursor, match.range.first))
            result += Part(mediaId = match.groupValues[1])
            cursor = match.range.last + 1
        }
        if (cursor < body.length || result.isEmpty()) result += Part(text = body.substring(cursor))
        return result
    }

    sealed interface Block {
        val key: String
        data class Text(override val key: String, val start: Int, val end: Int, val value: String) : Block
        data class Media(override val key: String, val id: String) : Block
    }

    /** Every media has a real editable text slot on both sides, including consecutive media.
     * References are serialized only; they never enter an editable field or the clipboard. */
    fun blocks(body: String): List<Block> {
        val result = mutableListOf<Block>()
        var cursor = 0
        var key = "start"
        val occurrences = mutableMapOf<String, Int>()
        for (match in pattern.findAll(body)) {
            result += Block.Text("text:$key", cursor, match.range.first, body.substring(cursor, match.range.first))
            val id = match.groupValues[1]
            val occurrence = occurrences.getOrDefault(id, 0)
            occurrences[id] = occurrence + 1
            key = "$id:$occurrence"
            result += Block.Media("media:$key", id)
            cursor = match.range.last + 1
        }
        result += Block.Text("text:$key", cursor, body.length, body.substring(cursor))
        return result
    }

    fun replaceText(body: String, key: String, text: String): String {
        val block = blocks(body).filterIsInstance<Block.Text>().firstOrNull { it.key == key } ?: return body
        return body.replaceRange(block.start, block.end, text)
    }

    /** Insert as its own body block at the editor's saved caret/selection offset. */
    fun insert(body: String, offset: Int, id: String): String {
        val at = offset.coerceIn(0, body.length)
        val before = body.substring(0, at)
        val after = body.substring(at)
        val left = if (before.isEmpty() || before.endsWith("\n\n")) "" else if (before.endsWith('\n')) "\n" else "\n\n"
        val right = if (after.isEmpty() || after.startsWith("\n\n")) "" else if (after.startsWith('\n')) "\n" else "\n\n"
        return before + left + reference(id) + right + after
    }

    fun remove(body: String, id: String): String = pattern.replace(body) { match ->
        if (match.groupValues[1] == id) "" else match.value
    }

    fun appendLegacy(body: String, id: String): String = insert(body, body.length, id)
}
