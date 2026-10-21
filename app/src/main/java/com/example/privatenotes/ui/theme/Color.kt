package com.example.privatenotes.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Design tokens (§4.1).
 *
 * Every colour in the app comes from here. Pages never hardcode a hex value, so the warm-paper
 * identity can be tuned in one file instead of hunting through screens.
 */
object DesignTokens {

    /** 浅色：暖纸主背景，砖橙强调。 */
    object Light {
        val background = Color(0xFFF1E7DA)
        val surface = Color(0xFFF8F1E7)
        val surfaceMuted = Color(0xFFEADCCC)
        val accent = Color(0xFFA13F04)
        val onAccent = Color(0xFFFDF6EE)
        val accentContainer = Color(0xFFF3D9C4)
        val onAccentContainer = Color(0xFF3E1A02)
        val textPrimary = Color(0xFF3A2A1E)
        val textSecondary = Color(0xFF7B6A5B)
        val divider = Color(0xFFD8C8B4)
        val error = Color(0xFF8C2F1F)
    }

    /** 深色：深暖棕，不用纯黑；强调色提亮为暖橙棕以保持对比。 */
    object Dark {
        val background = Color(0xFF1C1410)
        val surface = Color(0xFF251A13)
        val surfaceMuted = Color(0xFF30231A)
        val accent = Color(0xFFE2894A)
        val onAccent = Color(0xFF2B1607)
        val accentContainer = Color(0xFF4A2A12)
        val onAccentContainer = Color(0xFFF3D9C4)
        val textPrimary = Color(0xFFEDE0D2)
        val textSecondary = Color(0xFFB09A87)
        val divider = Color(0xFF3C2C21)
        val error = Color(0xFFE59A88)
    }
}
