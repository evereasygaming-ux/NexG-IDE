package com.nexg.ide.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexg.ide.R
import com.nexg.ide.ui.components.AuroraBackground
import com.nexg.ide.ui.components.GlassCard
import com.nexg.ide.ui.theme.Dimens
import com.nexg.ide.ui.theme.NexGTheme

/**
 * The Phase 1 landing screen.
 *
 * Scope note: Open Project, New Project and the AI prompt are presentational
 * only. They navigate to placeholders; they do not read storage, create a
 * project, or contact a model. The labels say what the plan will do, and the
 * screens they land on say plainly that they are scaffolds.
 */
@Composable
fun HomeScreen(
    onOpenProject: () -> Unit,
    onNewProject: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AuroraBackground(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Dimens.spaceLg),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceLg),
        ) {
            HomeHeader(onOpenSettings = onOpenSettings)

            ProjectActions(
                onOpenProject = onOpenProject,
                onNewProject = onNewProject,
            )

            RecentProjectsSection()

            AiAssistantSection()
        }
    }
}

@Composable
private fun HomeHeader(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = stringResource(R.string.home_title),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.home_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(
            onClick = onOpenSettings,
            // 48dp is already Material3's default here; stated explicitly
            // because the plan makes the touch-target floor a requirement
            // rather than something to rely on by convention. The label lives
            // on the Icon, which is what makes the button announce itself.
            modifier = Modifier.size(Dimens.minTouchTarget),
        ) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = stringResource(R.string.cd_settings),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProjectActions(
    onOpenProject: () -> Unit,
    onNewProject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
    ) {
        Button(
            onClick = onOpenProject,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = Dimens.minTouchTarget),
            shape = RoundedCornerShape(Dimens.cardCorner),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(
                imageVector = Icons.Outlined.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(Dimens.spaceSm))
            Text(
                text = stringResource(R.string.action_open_project),
                style = MaterialTheme.typography.labelLarge,
            )
        }

        OutlinedButton(
            onClick = onNewProject,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = Dimens.minTouchTarget),
            shape = RoundedCornerShape(Dimens.cardCorner),
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(Dimens.spaceSm))
            Text(
                text = stringResource(R.string.action_new_project),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun RecentProjectsSection(modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
            Text(
                text = stringResource(R.string.section_recent_projects),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // Honest empty state. Showing a fabricated project list here would
            // imply a working ProjectManager that does not exist in Phase 1.
            Text(
                text = stringResource(R.string.empty_recent_projects),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AiAssistantSection(modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
            Text(
                text = stringResource(R.string.section_ai_assistant),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.ai_prompt_placeholder),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.phase1_placeholder),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(name = "Home - dark", showBackground = true)
@Composable
private fun HomeScreenPreviewDark() {
    NexGTheme(darkTheme = true) {
        HomeScreen(onOpenProject = {}, onNewProject = {}, onOpenSettings = {})
    }
}

@Preview(name = "Home - light", showBackground = true)
@Composable
private fun HomeScreenPreviewLight() {
    NexGTheme(darkTheme = false) {
        HomeScreen(onOpenProject = {}, onNewProject = {}, onOpenSettings = {})
    }
}
