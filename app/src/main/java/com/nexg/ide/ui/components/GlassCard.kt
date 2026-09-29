package com.nexg.ide.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nexg.ide.ui.theme.Dimens
import com.nexg.ide.ui.theme.isDarkTheme

/**
 * The frosted card used across the shell (PLAN.MD 6.5).
 *
 * "Glass" here is a translucent fill plus a 1dp border, not a blur. A real
 * blur needs an offscreen layer per card, which costs GPU memory on exactly the
 * low-end devices this app targets. The gradient gives the depth for free, and
 * the border is what keeps the card edge visible when the fill sits close to the
 * colour of the surface behind it.
 *
 * The border is not decorative: in dark mode a violet-tinted card on a
 * near-black surface has no perceivable edge without it.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = Dimens.cardCorner,
    borderWidth: Dp = 1.dp,
    content: @Composable () -> Unit,
) {
    val dark = isDarkTheme()
    val scheme = MaterialTheme.colorScheme

    val fill = if (dark) {
        Brush.verticalGradient(
            listOf(
                scheme.surface.copy(alpha = 0.92f),
                scheme.surfaceVariant.copy(alpha = 0.72f),
            ),
        )
    } else {
        Brush.verticalGradient(
            listOf(
                scheme.surface.copy(alpha = 0.96f),
                scheme.surfaceVariant.copy(alpha = 0.80f),
            ),
        )
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(cornerRadius),
        color = Color.Transparent,
        border = BorderStroke(borderWidth, scheme.outline),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(cornerRadius))
                .background(fill)
                .padding(Dimens.spaceLg),
        ) {
            content()
        }
    }
}
