package com.example.privatenotes.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = DesignTokens.Light.accent,
    onPrimary = DesignTokens.Light.onAccent,
    primaryContainer = DesignTokens.Light.accentContainer,
    onPrimaryContainer = DesignTokens.Light.onAccentContainer,
    secondary = DesignTokens.Light.textSecondary,
    onSecondary = DesignTokens.Light.onAccent,
    secondaryContainer = DesignTokens.Light.surfaceMuted,
    onSecondaryContainer = DesignTokens.Light.textPrimary,
    tertiary = DesignTokens.Light.accent,
    onTertiary = DesignTokens.Light.onAccent,
    background = DesignTokens.Light.background,
    onBackground = DesignTokens.Light.textPrimary,
    surface = DesignTokens.Light.surface,
    onSurface = DesignTokens.Light.textPrimary,
    surfaceVariant = DesignTokens.Light.surfaceMuted,
    onSurfaceVariant = DesignTokens.Light.textSecondary,
    outline = DesignTokens.Light.divider,
    outlineVariant = DesignTokens.Light.divider,
    error = DesignTokens.Light.error,
)

private val DarkColors = darkColorScheme(
    primary = DesignTokens.Dark.accent,
    onPrimary = DesignTokens.Dark.onAccent,
    primaryContainer = DesignTokens.Dark.accentContainer,
    onPrimaryContainer = DesignTokens.Dark.onAccentContainer,
    secondary = DesignTokens.Dark.textSecondary,
    onSecondary = DesignTokens.Dark.onAccent,
    secondaryContainer = DesignTokens.Dark.surfaceMuted,
    onSecondaryContainer = DesignTokens.Dark.textPrimary,
    tertiary = DesignTokens.Dark.accent,
    onTertiary = DesignTokens.Dark.onAccent,
    background = DesignTokens.Dark.background,
    onBackground = DesignTokens.Dark.textPrimary,
    surface = DesignTokens.Dark.surface,
    onSurface = DesignTokens.Dark.textPrimary,
    surfaceVariant = DesignTokens.Dark.surfaceMuted,
    onSurfaceVariant = DesignTokens.Dark.textSecondary,
    outline = DesignTokens.Dark.divider,
    outlineVariant = DesignTokens.Dark.divider,
    error = DesignTokens.Dark.error,
)

/**
 * App theme.
 *
 * Dynamic colour is not used: the warm-paper palette is a product requirement (§4.1), not
 * something to inherit from the user's wallpaper.
 */
@Composable
fun PrivateNotesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = PrivateNotesTypography,
        shapes = PrivateNotesShapes,
        content = content,
    )
}
