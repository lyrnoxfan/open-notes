package com.example.privatenotes.ui.markdown

import android.text.method.LinkMovementMethod
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    scrollable: Boolean = true,
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
                    override fun configureConfiguration(builder: io.noties.markwon.MarkwonConfiguration.Builder) {
                        val delegate = io.noties.markwon.LinkResolverDef()
                        builder.linkResolver { view, link ->
                            if (MarkdownLinks.isAllowed(link)) {
                                // Missing/denied handlers must not crash a private preview.
                                try { delegate.resolve(view, link) } catch (_: SecurityException) { }
                            }
                        }
                    }

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

    // Parsing and spanning a large document is milliseconds to seconds of CPU; doing it on the
    // main thread blocked the frame the user was waiting for. It now runs on Default, and the
    // result is applied only if it is still the newest request - a fast edit-save-edit cycle must
    // not paint an older render over a newer one.
    // Per-preview generation counter: an older parse finishing late must not paint over a newer
    // one, and two previews on screen must not cancel each other.
    val generation = remember { java.util.concurrent.atomic.AtomicLong() }
    var parsed by remember(markwon, rendered) { mutableStateOf<android.text.Spanned?>(null) }
    LaunchedEffect(markwon, rendered) {
        val mine = generation.incrementAndGet()
        val spanned = withContext(Dispatchers.Default) {
            markwon.render(markwon.parse(rendered))
        }
        if (generation.get() == mine) parsed = spanned
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            val text = TextView(viewContext).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                movementMethod = LinkMovementMethod.getInstance()
                setTextColor(colors.onBackground.toArgb())
                includeFontPadding = false
                typeface = serif
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, bodyPx)
            }
            if (!scrollable) text else ScrollView(viewContext).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                addView(text)
            }
        },
        update = { host ->
            val textView = if (host is ScrollView) host.getChildAt(0) as TextView else host as TextView
            textView.setTextColor(colors.onBackground.toArgb())
            textView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, bodyPx)
            val metrics = textView.paint.fontMetrics
            val intrinsic = metrics.descent - metrics.ascent + metrics.leading
            val extra = (lineHeightPx - intrinsic).coerceAtLeast(0f)
            textView.setLineSpacing(extra, 1f)
            textView.setPadding(0, (extra / 2f).roundToInt(), 0, 0)
            // Inline blocks use a Compose parent scroller; standalone previews scroll themselves.
            if (host is ScrollView) host.isFillViewport = true
            // Null until the background parse finishes; the view keeps its previous text rather
            // than flashing empty.
            parsed?.let { textView.text = it }
        },
    )
}
