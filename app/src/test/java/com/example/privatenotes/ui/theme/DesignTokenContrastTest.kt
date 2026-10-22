package com.example.privatenotes.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Enforces the contrast targets from `docs/design.md` in CI.
 *
 * `scripts/contrast.py` prints the same numbers for a human to read; this test is the part that
 * fails a build. Both read [DesignTokens], so a palette tweak that quietly drops secondary text
 * below 4.5:1 cannot reach a release.
 */
class DesignTokenContrastTest {

    private data class Pair(
        val name: String,
        val foreground: Color,
        val background: Color,
        val required: Double,
    )

    private fun pairs(scheme: String, t: DesignTokens.DesignScheme): List<Pair> {
        return listOf(
            Pair("$scheme body/page", t.textPrimary, t.background, BODY),
            Pair("$scheme body/card", t.textPrimary, t.surface, BODY),
            Pair("$scheme body/muted", t.textPrimary, t.surfaceMuted, BODY),
            Pair("$scheme secondary/page", t.textSecondary, t.background, TEXT),
            Pair("$scheme secondary/card", t.textSecondary, t.surface, TEXT),
            Pair("$scheme accent/page", t.accent, t.background, TEXT),
            Pair("$scheme accent/card", t.accent, t.surface, TEXT),
            Pair("$scheme onAccent/accent", t.onAccent, t.accent, TEXT),
            Pair("$scheme onAccentContainer", t.onAccentContainer, t.accentContainer, TEXT),
            Pair("$scheme error/page", t.error, t.background, TEXT),
            Pair("$scheme error/card", t.error, t.surface, TEXT),
            // Decorative hairline: it only has to be visible, not to identify a control.
            Pair("$scheme divider", t.divider, t.background, DECORATION),
            Pair("$scheme dividerStrong", t.dividerStrong, t.background, BORDER),
        )
    }

    @Test
    fun everyPairMeetsItsTarget() {
        val failures = mutableListOf<String>()
        val schemes = listOf(
            "light" to DesignTokens.Light as DesignTokens.DesignScheme,
            "dark" to DesignTokens.Dark as DesignTokens.DesignScheme,
        )
        for ((scheme, tokens) in schemes) {
            for (pair in pairs(scheme, tokens)) {
                val ratio = contrastRatio(pair.foreground, pair.background)
                if (ratio < pair.required) {
                    failures += "${pair.name}: %.2f < %.1f".format(ratio, pair.required)
                }
            }
        }
        assertTrue("contrast below target:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private fun contrastRatio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }

    private companion object {
        const val BODY = 7.0
        const val TEXT = 4.5
        const val DECORATION = 1.3
        const val BORDER = 1.6
    }
}
