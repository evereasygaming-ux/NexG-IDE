package com.nexg.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nexg.ide.ui.theme.Dimens
import com.nexg.ide.ui.theme.StatusTone
import com.nexg.ide.ui.theme.isDarkTheme
import com.nexg.ide.ui.theme.statusColor

/**
 * A small status pill for build results, log levels and later phase signals.
 *
 * Accessibility: the tone colour is never the only carrier of meaning. Every
 * chip pairs its colour with an icon and a text label, and exposes a single
 * merged content description. A screen-reader user gets "Build failed, error"
 * rather than an unlabelled coloured shape, and a user with colour-vision
 * deficiency still sees the difference.
 */
@Composable
fun StatusChip(
    label: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accent: Color? = null,
) {
    val dark = isDarkTheme()
    val resolved = accent ?: statusColor(tone, dark)
    val shape = RoundedCornerShape(Dimens.chipCorner)

    Row(
        modifier = modifier
            .background(resolved.copy(alpha = 0.14f), shape)
            .border(Dp(1f), resolved.copy(alpha = 0.45f), shape)
            .padding(horizontal = Dimens.spaceSm, vertical = Dimens.spaceXs)
            .clearAndSetSemantics {
                contentDescription = "$label, ${tone.name.lowercase()}"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                // Decorative: the merged description above already carries
                // both the label and the state.
                contentDescription = null,
                tint = resolved,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (dark) resolved else MaterialTheme.colorScheme.onSurface,
        )
    }
}
