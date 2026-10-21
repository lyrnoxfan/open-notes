package com.example.privatenotes.data.repository

/**
 * Escapes user input for a `LIKE` pattern (§7.3).
 *
 * The search term is already bound as a parameter, so injection is impossible. What still goes
 * wrong without this is meaning: typing `100%` or `a_b` should search for those literal
 * characters, not silently become a wildcard expression.
 */
object LikePattern {

    const val ESCAPE_CHAR = '\\'

    fun escape(raw: String): String = buildString(raw.length + 8) {
        for (ch in raw) {
            if (ch == ESCAPE_CHAR || ch == '%' || ch == '_') append(ESCAPE_CHAR)
            append(ch)
        }
    }
}
