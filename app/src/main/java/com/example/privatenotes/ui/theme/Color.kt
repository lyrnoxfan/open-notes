package com.example.privatenotes.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Design tokens (§4.1).
 *
 * Every colour in the app comes from here. Pages never hardcode a hex value, so the warm-paper
 * identity can be tuned in one file instead of hunting through screens.
 */
object DesignTokens {

    /**
     * The roles a scheme has to fill.
     *
     * Light and dark are the same shape, so anything that inspects a palette - the contrast
     * test, a preview - can take either one without knowing which it has.
     */
    interface DesignScheme {
        val background: Color
        val surface: Color
        val surfaceMuted: Color
        val action: Color
        val onAction: Color
        val attention: Color
        val accent: Color
        val onAccent: Color
        val accentContainer: Color
        val onAccentContainer: Color
        val textPrimary: Color
        val textSecondary: Color
        val divider: Color
        val dividerStrong: Color
        val error: Color
    }

    /** Warm white paper, ink-green text and sage actions. Terracotta is decorative, not small text. */
    object Light : DesignScheme {
        override val action = Color(0xFF668575)
        override val onAction = Color(0xFF07120C)
        override val attention = Color(0xFFC97862)
        override val background = Color(0xFFF7F6F2)
        override val surface = Color(0xFFFFFFFF)
        override val surfaceMuted = Color(0xFFDCE8EA)
        override val accent = Color(0xFF365B49)
        override val onAccent = Color(0xFFFFFFFF)
        override val accentContainer = Color(0xFFDCE8EA)
        override val onAccentContainer = Color(0xFF26332E)
        override val textPrimary = Color(0xFF26332E)
        override val textSecondary = Color(0xFF52645B)
        override val divider = Color(0xFFC9D2CC)
        override val dividerStrong = Color(0xFF7B8C82)
        override val error = Color(0xFF994936)
    }

    /** 深色：深绿纸面和浅鼠尾草强调，保留同样的主次层级。 */
    object Dark : DesignScheme {
        override val action = Color(0xFF91B7A1)
        override val onAction = Color(0xFF11251A)
        override val attention = Color(0xFFE2A18A)
        override val background = Color(0xFF151E19)
        override val surface = Color(0xFF1D2922)
        override val surfaceMuted = Color(0xFF2B3B33)
        override val accent = Color(0xFFA9CBB8)
        override val onAccent = Color(0xFF182C20)
        override val accentContainer = Color(0xFF304840)
        override val onAccentContainer = Color(0xFFE0ECE5)
        override val textPrimary = Color(0xFFE7EEE7)
        override val textSecondary = Color(0xFFBAC9BE)
        override val divider = Color(0xFF41544A)
        override val dividerStrong = Color(0xFF839C8D)
        override val error = Color(0xFFF3B09A)
    }
}
