package com.nexg.ide.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.nexg.ide.ui.theme.NexGCyan
import com.nexg.ide.ui.theme.NexGViolet
import com.nexg.ide.ui.theme.NexGMint
import com.nexg.ide.ui.theme.isDarkTheme

/**
 * The ambient gradient wash behind the shell (PLAN.MD 6.5 "AuroraBackground").
 *
 * Phase 1 ships this as a static gradient. Two reasons, both deliberate:
 *
 *  1. Animating it means a continuously running recomposition and redraw for
 *     the whole app's lifetime, which is the single most expensive thing a
 *     low-end device can be asked to do while also compiling and running a
 *     Gradle build through Termux.
 *  2. `prefers-reduced-motion` is an accessibility requirement, and an
 *     ambient always-animating background is a common trigger for motion
 *     sickness. A static version has nothing to disable.
 *
 * The animation is meant to arrive with the voice orb in the phase that owns
 * motion, where it can be gated on the reduced-motion setting from the start.
 *
 * The gradient alpha is kept low on purpose: it sits *behind* text, so raising
 * it would quietly reduce the contrast ratios recorded in Color.kt.
 */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val dark = isDarkTheme()
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            val violet = if (dark) NexGViolet else NexGViolet.copy(alpha = 0.35f)
            val cyan = if (dark) NexGCyan else NexGCyan.copy(alpha = 0.28f)
            val mint = if (dark) NexGMint else NexGMint.copy(alpha = 0.22f)

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(violet.copy(alpha = 0.28f), Color.Transparent),
                    center = Offset(w * 0.15f, h * 0.12f),
                    radius = w * 0.85f,
                ),
                radius = w * 0.85f,
                center = Offset(w * 0.15f, h * 0.12f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(cyan.copy(alpha = 0.20f), Color.Transparent),
                    center = Offset(w * 0.92f, h * 0.32f),
                    radius = w * 0.70f,
                ),
                radius = w * 0.70f,
                center = Offset(w * 0.92f, h * 0.32f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(mint.copy(alpha = 0.12f), Color.Transparent),
                    center = Offset(w * 0.45f, h * 0.95f),
                    radius = w * 0.75f,
                ),
                radius = w * 0.75f,
                center = Offset(w * 0.45f, h * 0.95f),
            )
        }
        content()
    }
}
