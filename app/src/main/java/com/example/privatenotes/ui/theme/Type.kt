package com.example.privatenotes.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.example.privatenotes.R

/**
 * Two families, one rule: content is serif, chrome is sans (design.md §2).
 *
 * The serif is the bundled LXGW WenKai subset. §4.2 prefers the system serif and asks for a
 * bundled font only when it is clearly needed; on ColorOS the system serif falls back to a
 * sans-serif for Chinese, so the reading face has to be shipped to exist at all.
 *
 * Only the Regular cut is bundled. `Medium` headings are synthesised by the platform from it:
 * a second 3.3 MB file for one weight is not worth it on a notes app.
 */
val PrivateNotesSerif = FontFamily(
    Font(R.font.lxgw_wenkai_regular, weight = FontWeight.Normal),
)

/** System sans for buttons, chips, timestamps and status. */
val PrivateNotesSans = FontFamily.Default

private val contentLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

/**
 * Every style Material 3 can ask for is defined here, including the ones no screen uses yet.
 *
 * Leaving a style undefined means a component silently falls back to Roboto at a size that
 * matches nothing else on the page - which is exactly how `labelMedium` used to render the tag
 * chips in a different typeface from the rest of the screen.
 */
val PrivateNotesTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = PrivateNotesSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 40.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp,
        lineHeightStyle = contentLineHeight,
    ),
    displayMedium = TextStyle(
        fontFamily = PrivateNotesSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 32.sp,
        lineHeight = 44.sp,
        lineHeightStyle = contentLineHeight,
    ),
    displaySmall = TextStyle(
        fontFamily = PrivateNotesSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 28.sp,
        lineHeight = 38.sp,
        lineHeightStyle = contentLineHeight,
    ),
    headlineLarge = TextStyle(
        fontFamily = PrivateNotesSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 26.sp,
        lineHeight = 36.sp,
        lineHeightStyle = contentLineHeight,
    ),
    headlineMedium = TextStyle(
        fontFamily = PrivateNotesSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 24.sp,
        lineHeight = 34.sp,
        lineHeightStyle = contentLineHeight,
    ),
    headlineSmall = TextStyle(
        fontFamily = PrivateNotesSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 32.sp,
        lineHeightStyle = contentLineHeight,
    ),
    titleLarge = TextStyle(
        fontFamily = PrivateNotesSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 30.sp,
        lineHeightStyle = contentLineHeight,
    ),
    titleMedium = TextStyle(
        fontFamily = PrivateNotesSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 26.sp,
        lineHeightStyle = contentLineHeight,
    ),
    titleSmall = TextStyle(
        fontFamily = PrivateNotesSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        lineHeightStyle = contentLineHeight,
    ),
    /** Reading text: 17sp on 30sp is the 1.76 multiple Chinese body copy needs. */
    bodyLarge = TextStyle(
        fontFamily = PrivateNotesSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.sp,
        lineHeightStyle = contentLineHeight,
    ),
    bodyMedium = TextStyle(
        fontFamily = PrivateNotesSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp,
        lineHeightStyle = contentLineHeight,
    ),
    bodySmall = TextStyle(
        fontFamily = PrivateNotesSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 21.sp,
        lineHeightStyle = contentLineHeight,
    ),
    labelLarge = TextStyle(
        fontFamily = PrivateNotesSans,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = PrivateNotesSans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = PrivateNotesSans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.3.sp,
    ),
)
