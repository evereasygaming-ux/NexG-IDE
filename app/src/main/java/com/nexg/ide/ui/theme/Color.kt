package com.nexg.ide.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Palette from PLAN.MD 6.1. These hex values are the contract; do not
 * substitute "nicer" colours without updating the plan.
 *
 * The two schemes below are not just `darkColorScheme(...)` with
 * `primary = Purple`. Each accent was checked against the surface it sits on
 * and against the text placed on it; see Dimens.kt for the measured ratios
 * and `CONTRAST_NOTES` at the bottom of this file.
 */

// Base surfaces
val NexGBlack = Color(0xFF05060A) // base
val NexGDeepSpace = Color(0xFF0E1119) // surface / card
val NexGSlate = Color(0xFF1E2434) // elevated / border

// Accents
val NexGViolet = Color(0xFF7C5CFF) // primary
val NexGCyan = Color(0xFF00E5FF) // secondary
val NexGMint = Color(0xFF00FF94) // success
val NexGAmber = Color(0xFFFFB020) // warning
val NexGRed = Color(0xFFFF4D6D) // error
val NexGText = Color(0xFFE8ECF5) // on-surface text

/**
 * Secondary text. Not in the plan's list, so it is defined here and its
 * contrast is recorded rather than left implicit — an unreadable grey is the
 * most common way a dark theme fails accessibility.
 */
val NexGTextSecondary = Color(0xFF9AA3B5)

/** Light-mode surface, so light theme is a real theme and not inverted dark. */
val NexGLightSurface = Color(0xFFF7F8FC)
val NexGLightSurfaceElevated = Color(0xFFFFFFFF)
val NexGLightBorder = Color(0xFFD8DCE8)
val NexGLightText = Color(0xFF0B0D14)

/** Dark scheme. This is the app's default. */
val NexGDarkColors = androidx.compose.material3.darkColorScheme(
    primary = NexGViolet,
    onPrimary = NexGBlack,
    primaryContainer = NexGSlate,
    onPrimaryContainer = NexGText,
    secondary = NexGCyan,
    onSecondary = NexGBlack,
    secondaryContainer = NexGSlate,
    onSecondaryContainer = NexGCyan,
    tertiary = NexGMint,
    onTertiary = NexGBlack,
    background = NexGBlack,
    onBackground = NexGText,
    surface = NexGDeepSpace,
    onSurface = NexGText,
    surfaceVariant = NexGSlate,
    onSurfaceVariant = NexGTextSecondary,
    outline = NexGSlate,
    outlineVariant = NexGSlate,
    error = NexGRed,
    onError = NexGBlack,
)

/**
 * Light scheme. Accent hues are kept so brand identity survives, but each is
 * darkened enough to hold 4.5:1 against a light surface — the same violet and
 * cyan that pass on black fail on white.
 */
val NexGLightColors = androidx.compose.material3.lightColorScheme(
    primary = Color(0xFF5B3AE0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E1FF),
    onPrimaryContainer = Color(0xFF1A0A5C),
    secondary = Color(0xFF00707F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFF3F7),
    onSecondaryContainer = Color(0xFF00252B),
    tertiary = Color(0xFF006B45),
    onTertiary = Color.White,
    background = NexGLightSurface,
    onBackground = NexGLightText,
    surface = NexGLightSurfaceElevated,
    onSurface = NexGLightText,
    surfaceVariant = Color(0xFFEDEFF6),
    onSurfaceVariant = Color(0xFF4A5162),
    outline = NexGLightBorder,
    outlineVariant = NexGLightBorder,
    error = Color(0xFFB3261E),
    onError = Color.White,
)

/**
 * Status colours shared by StatusChip and the future Build/Logs screens.
 *
 * Kept out of the ColorScheme because these convey state, not role, and they
 * are always paired with an icon and a text label so colour is never the only
 * carrier of meaning.
 */
enum class StatusTone { SUCCESS, WARNING, ERROR, INFO }

fun statusColor(tone: StatusTone, dark: Boolean): Color = when (tone) {
    StatusTone.SUCCESS -> if (dark) NexGMint else Color(0xFF006B45)
    StatusTone.WARNING -> if (dark) NexGAmber else Color(0xFF8A5A00)
    StatusTone.ERROR -> if (dark) NexGRed else Color(0xFFB3261E)
    StatusTone.INFO -> if (dark) NexGCyan else Color(0xFF00707F)
}

/**
 * Contrast notes — measured, not assumed.
 *
 * Every number below was computed with the WCAG 2.x relative-luminance formula
 * and is asserted by `ColorContrastTest`. That test is what makes this block a
 * record rather than a claim: change a colour and the assertions fail, forcing
 * this table to be re-measured instead of quietly going stale.
 *
 * An earlier draft of this file carried hand-guessed ratios (cyan "9.4", mint
 * "9.9") that the test immediately disproved — the real values are far higher.
 * That is the reason the numbers are now test-backed instead of estimated.
 *
 *   Dark scheme (dark = true)
 *     NexGText          on NexGBlack        17.12:1  AAA
 *     NexGText          on NexGDeepSpace    15.95:1  AAA
 *     NexGTextSecondary on NexGBlack         7.99:1  AAA
 *     NexGTextSecondary on NexGDeepSpace     7.44:1  AAA
 *     NexGViolet        on NexGBlack         4.66:1  AA   (see note below)
 *     NexGCyan          on NexGBlack        13.17:1  AAA
 *     NexGMint          on NexGBlack        15.17:1  AAA
 *     NexGAmber         on NexGBlack        11.07:1  AAA
 *     NexGRed           on NexGBlack         6.30:1  AA
 *     NexGBlack         on NexGViolet        4.66:1  AA   (onPrimary)
 *
 *   Light scheme (dark = false)
 *     NexGLightText     on NexGLightSurface 18.29:1  AAA
 *     primary / secondary / tertiary / error on their light surfaces
 *     all clear 4.5:1, asserted in `light scheme accents meet AA`.
 *
 * Note on NexGViolet: 4.66:1 on the base surface clears AA for normal text but
 * with under 0.2 of headroom, so any future rounding of the hex value could
 * push it under. It is fine for the onPrimary pairing (black on violet) and
 * for large text and icons. Do not use body-sized violet text on the base
 * surface — use NexGText.
 */
object CONTRAST_NOTES
