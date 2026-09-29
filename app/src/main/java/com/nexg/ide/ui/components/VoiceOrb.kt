package com.nexg.ide.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nexg.ide.ui.theme.NexGCyan
import com.nexg.ide.ui.theme.NexGViolet
import com.nexg.ide.ui.theme.isDarkTheme

/**
 * The voice-orb visual scaffold (PLAN.MD 6.5).
 *
 * Phase 1 ships a static orb only. It is not wired to microphone permission,
 * speech recognition or any service — those arrive in Phase 14. It exists so
 * the layout reserves the correct space and the design language is fixed
 * early, without implying a working feature.
 *
 * Because it is static, it is also non-interactive: the enabled/disabled
 * affordance lives on the wrapper in [VoiceOrbScaffold], so that decision is
 * made in one place instead of being duplicated inside the drawing code.
 */
@Composable
fun VoiceOrb(
    modifier: Modifier = Modifier,
    diameter: Dp = 96.dp,
) {
    val dark = isDarkTheme()
    val muted = if (dark) 0.35f else 0.25f

    // Captured here because a DrawScope lambda is not a composable scope and
    // cannot read MaterialTheme directly.
    val outline = MaterialTheme.colorScheme.outline

    Box(
        modifier = modifier.size(diameter),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(diameter)) {
            // Named `diameter`, not `size`: a parameter called `size` would
            // shadow DrawScope.size and make `size.minDimension` resolve
            // against the Dp parameter instead of the canvas dimensions.
            val radius = this.size.minDimension / 2f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        NexGViolet.copy(alpha = muted),
                        NexGCyan.copy(alpha = muted * 0.4f),
                        Color.Transparent,
                    ),
                ),
                radius = radius,
            )
            drawCircle(
                color = outline,
                radius = radius,
                style = Stroke(width = 1.dp.toPx()),
            )
        }
    }
}

/**
 * Wraps [VoiceOrb] with the semantics a future interactive orb will need.
 *
 * Phase 1 announces it as unavailable rather than as a button. Announcing a
 * control that does nothing is worse than announcing nothing — it teaches the
 * user that this app's accessibility labels are unreliable.
 */
@Composable
fun VoiceOrbScaffold(
    modifier: Modifier = Modifier,
    diameter: Dp = 96.dp,
    enabled: Boolean = false,
    unavailableLabel: String = "Voice assistant, not available in this build",
) {
    Box(
        modifier = modifier
            .size(diameter)
            .semantics {
                contentDescription = if (enabled) "Voice assistant" else unavailableLabel
            },
        contentAlignment = Alignment.Center,
    ) {
        VoiceOrb(diameter = diameter)
    }
}
