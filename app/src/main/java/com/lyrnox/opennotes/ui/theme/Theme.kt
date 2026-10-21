package com.lyrnox.opennotes.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Sage40,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = Sage90,
    onPrimaryContainer = Sage10,
    secondary = Moss40,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    secondaryContainer = Moss90,
    onSecondaryContainer = Sage10,
    tertiary = Clay40,
    onTertiary = androidx.compose.ui.graphics.Color.White,
    tertiaryContainer = Clay90,
    onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFF2B1700),
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = PaperVariant,
    onSurfaceVariant = InkMuted,
    outline = InkMuted,
)

private val DarkColors = darkColorScheme(
    primary = Sage80,
    onPrimary = Sage30,
    primaryContainer = Sage20,
    onPrimaryContainer = Sage90,
    secondary = Moss80,
    onSecondary = androidx.compose.ui.graphics.Color(0xFF1E372F),
    secondaryContainer = Moss20,
    onSecondaryContainer = Moss90,
    tertiary = Clay80,
    onTertiary = androidx.compose.ui.graphics.Color(0xFF452A0C),
    tertiaryContainer = Clay20,
    onTertiaryContainer = Clay90,
    background = NightPaper,
    onBackground = NightInk,
    surface = NightPaper,
    onSurface = NightInk,
    surfaceVariant = NightVariant,
    onSurfaceVariant = NightInkMuted,
    outline = NightInkMuted,
)

/**
 * App theme.
 *
 * Dynamic colour is opt-in: a notes app is read for long stretches, so its palette is
 * part of the product identity rather than something to inherit from the wallpaper.
 */
@Composable
fun OpenNotesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = OpenNotesTypography,
        shapes = OpenNotesShapes,
        content = content,
    )
}
