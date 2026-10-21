package com.example.privatenotes.ui.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.MaterialTheme
import io.noties.markwon.Markwon
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.core.MarkwonTheme

/**
 * Renders Markdown for the preview pane.
 *
 * Markwon's default text view is used rather than a web view: §7.2 asks for exactly the usual
 * syntax (headings, lists, quotes, code, links, rules) and explicitly rules out pulling in a full
 * HTML rendering environment.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val textColor = MaterialTheme.colorScheme.onBackground
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant

    val markwon = remember(textColor, linkColor, codeBackground) {
        Markwon.builder(context)
            .usePlugin(
                object : io.noties.markwon.AbstractMarkwonPlugin() {
                    override fun configureTheme(builder: MarkwonTheme.Builder) {
                        builder
                            .linkColor(linkColor.toArgb())
                            .codeBackgroundColor(codeBackground.toArgb())
                            .codeTextColor(textColor.toArgb())
                            .blockQuoteColor(linkColor.toArgb())
                    }
                },
            )
            .build()
    }

    val rendered = remember(markdown) { MarkdownLineBreaks.apply(markdown) }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            android.widget.TextView(viewContext).apply {
                setTextIsSelectable(true)
                setTextColor(textColor.toArgb())
                textSize = 16f
                setLineSpacing(0f, 1.25f)
            }
        },
        update = { textView ->
            markwon.setMarkdown(textView, rendered)
        },
    )
}
