package com.example.privatenotes.ui.markdown

import java.net.URI
import java.util.Locale

/** Imported Markdown must never launch local files, content providers or arbitrary intents. */
internal object MarkdownLinks {
    fun isAllowed(link: String): Boolean {
        if (link.any { it.isWhitespace() || it.isISOControl() }) return false
        val uri = runCatching { URI(link) }.getOrNull() ?: return false
        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            "http", "https" -> !uri.rawAuthority.isNullOrBlank() && uri.rawUserInfo == null
            "mailto", "tel" -> !uri.rawSchemeSpecificPart.isNullOrBlank()
            else -> false
        }
    }
}
