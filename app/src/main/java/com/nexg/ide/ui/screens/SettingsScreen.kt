package com.nexg.ide.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexg.ide.R
import com.nexg.ide.ui.components.GlassCard
import com.nexg.ide.ui.theme.Dimens
import com.nexg.ide.ui.theme.NexGTheme

/**
 * Settings destination (Phase 1 scaffold).
 *
 * Lists the settings the plan defines so the information architecture is
 * visible and reviewable early. Nothing here toggles state yet: the theme
 * switch, the BYOK entry and the security section all depend on later phases
 * (secure credential storage is Phase 4), and a switch that does nothing is a
 * bug the user has to discover.
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Dimens.spaceLg),
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
    ) {
        Text(
            text = stringResource(R.string.nav_settings),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceLg)) {
                SettingRow(
                    icon = Icons.Outlined.Palette,
                    title = "Appearance",
                    subtitle = "Dark and light themes defined. Toggle arrives with the in-app theme setting.",
                )
                SettingRow(
                    icon = Icons.Outlined.Security,
                    title = "AI credentials",
                    subtitle = "Bring-your-own-key storage planned. No key is requested or stored in this build.",
                )
                SettingRow(
                    icon = Icons.Outlined.BugReport,
                    title = "Logs",
                    subtitle = "Structured logging is active. The in-app log viewer arrives in a later phase.",
                )
            }
        }

        Text(
            text = stringResource(R.string.phase1_placeholder),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                // Merged so a screen reader reads one coherent row instead of
                // three disconnected fragments.
                contentDescription = "$title. $subtitle"
            },
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(name = "Settings - dark", showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    NexGTheme(darkTheme = true) {
        SettingsScreen()
    }
}
