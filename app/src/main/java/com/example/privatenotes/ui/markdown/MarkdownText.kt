package com.example.privatenotes.ui.markdown

import android.text.method.LinkMovementMethod
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import com.example.privatenotes.R
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import kotlin.math.roundToInt

/**
 * Renders Markdown for the preview pane.
 *
 * Markwon's default text view is used rather than a web view: §7.2 asks for exactly the usual
 * syntax (headings, lists, quotes, code, links, rules) and explicitly rules out pulling in a full
 * HTML rendering environment.
 *
 * Everything visible comes from the theme: family, size, line height, colour of links, quotes,
 * code and rules. Markwon's own defaults (Roboto, 16sp, indigo links, grey code) are replaced
 * wholesale, otherwise the preview reads as a different app from the editor.
 *
 * The first line has to land on the same pixel as the editor's first line when the user toggles
 * preview, so the view carries the same half-leading above the first baseline that Compose puts
 * there and no other padding at all.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val typography = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme

    val bodySp = typography.bodyLarge.fontSize
    val lineHeightSp = typography.bodyLarge.lineHeight
    val bodyPx = with(density) { bodySp.toPx() }
    val lineHeightPx = with(density) { lineHeightSp.toPx() }
    val codePx = with(density) { typography.bodyMedium.fontSize.toPx() }
    val blockMarginPx = with(density) { 12.dp.toPx() }.roundToInt()
    val quoteWidthPx = with(density) { 3.dp.toPx() }.roundToInt()

    val serif = remember(context) {
        ResourcesCompat.getFont(context, R.font.lxgw_wenkai_regular)
            ?: android.graphics.Typeface.SERIF
    }
    val serifBold = remember(serif) { android.graphics.Typeface.create(serif, android.graphics.Typeface.BOLD) }
    val mono = android.graphics.Typeface.MONOSPACE

    val markwon = remember(colors, serif, serifBold, mono, bodyPx, codePx) {
        Markwon.builder(context)
            .usePlugin(
                object : io.noties.markwon.AbstractMarkwonPlugin() {
                    override fun configureTheme(builder: MarkwonTheme.Builder) {
                        builder
                            .headingTypeface(serifBold)
                            .headingTextSizeMultipliers(
                                floatArrayOf(1.45f, 1.3f, 1.18f, 1.08f, 1f, 0.94f),
                            )
                            // No rule under headings: the size jump is the hierarchy.
                            .headingBreakHeight(0)
                            .headingBreakColor(android.graphics.Color.TRANSPARENT)
                            .linkColor(colors.primary.toArgb())
                            .isLinkUnderlined(true)
                            .blockQuoteColor(colors.primaryContainer.toArgb())
                            .blockQuoteWidth(quoteWidthPx)
                            .blockMargin(blockMarginPx)
                            .listItemColor(colors.onSurfaceVariant.toArgb())
                            .bulletWidth(with(density) { 4.dp.toPx() }.roundToInt())
                            .bulletListItemStrokeWidth(with(density) { 4.dp.toPx() }.roundToInt())
                            .codeTypeface(mono)
                            .codeBlockTypeface(mono)
                            .codeTextSize(codePx.roundToInt())
                            .codeBlockTextSize(codePx.roundToInt())
                            .codeTextColor(colors.onBackground.toArgb())
                            .codeBlockTextColor(colors.onBackground.toArgb())
                            .codeBackgroundColor(colors.surfaceVariant.toArgb())
                            .codeBlockBackgroundColor(colors.surfaceVariant.toArgb())
                            .codeBlockMargin(with(density) { 12.dp.toPx() }.roundToInt())
                            .thematicBreakColor(colors.outlineVariant.toArgb())
                            .thematicBreakHeight(with(density) { 1.dp.toPx() }.roundToInt())
                    }
                },
            )
            .build()
    }

    val rendered = remember(markdown) { MarkdownLineBreaks.apply(markdown) }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            ScrollView(viewContext).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                addView(
                    TextView(viewContext).apply {
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                        // Links have to stay tappable, which rules out setTextIsSelectable: it
                        // swaps in a movement method that swallows link clicks.
                        movementMethod = LinkMovementMethod.getInstance()
                        setTextColor(colors.onBackground.toArgb())
                        includeFontPadding = false
                        typeface = serif
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, bodyPx)
                    },
                )
            }
        },
        update = { scroll ->
            val textView = scroll.getChildAt(0) as TextView
            textView.setTextColor(colors.onBackground.toArgb())
            textView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, bodyPx)
            val metrics = textView.paint.fontMetrics
            val intrinsic = metrics.descent - metrics.ascent + metrics.leading
            val extra = (lineHeightPx - intrinsic).coerceAtLeast(0f)
            textView.setLineSpacing(extra, 1f)
            textView.setPadding(0, (extra / 2f).roundToInt(), 0, 0)
            markwon.setMarkdown(textView, rendered)
        },
    )
}
