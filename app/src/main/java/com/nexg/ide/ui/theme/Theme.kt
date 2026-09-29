package com.nexg.ide.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Dark-first theme (PLAN.MD 6.1: "dark by default, light mode available").
 *
 * The default is deliberately *not* `isSystemInDarkTheme()`. The plan makes
 * dark the product default and light an explicitly supported alternative, so
 * the caller decides. `MainActivity` currently passes the system preference,
 * which respects a user who has chosen dark — but a future in-app theme
 * setting can override this without touching the call site.
 */
@Composable
fun NexGTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) NexGDarkColors else NexGLightColors

    // Exposed so status chips and the future build/logs screens can branch on
    // the scheme in force without re-deriving it from isSystemInDarkTheme()
    // (which can disagree during a configuration change).
    CompositionLocalProvider(
        LocalIsDarkTheme provides darkTheme,
        LocalSpacing provides Spacing(),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = NexGTypography,
            content = content,
        )
    }
}

val LocalIsDarkTheme = staticCompositionLocalOf { true }

@Composable
@ReadOnlyComposable
fun isDarkTheme(): Boolean = LocalIsDarkTheme.current

/**
 * The spacing scale as a value object, so screens read `NexGTheme.spacing.md`
 * instead of importing a global. Purely cosmetic convenience over [Dimens];
 * it exists so spacing can later vary by density or window size class without
 * touching call sites.
 */
data class Spacing(
    val xs: androidx.compose.ui.unit.Dp = Dimens.spaceXs,
    val sm: androidx.compose.ui.unit.Dp = Dimens.spaceSm,
    val md: androidx.compose.ui.unit.Dp = Dimens.spaceMd,
    val lg: androidx.compose.ui.unit.Dp = Dimens.spaceLg,
    val xl: androidx.compose.ui.unit.Dp = Dimens.spaceXl,
)

val LocalSpacing = staticCompositionLocalOf { Spacing() }
