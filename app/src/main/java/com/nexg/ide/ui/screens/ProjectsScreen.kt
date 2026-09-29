package com.nexg.ide.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexg.ide.R
import com.nexg.ide.ui.components.GlassCard
import com.nexg.ide.ui.theme.Dimens
import com.nexg.ide.ui.theme.EmptyStateBodyStyle
import com.nexg.ide.ui.theme.EmptyStateTitleStyle
import com.nexg.ide.ui.theme.NexGTheme

/**
 * Projects destination (Phase 1 scaffold).
 *
 * Deliberately does not touch storage. The real implementation (Phase 2) picks
 * a project root with SAF and reports it through a `ProjectPort`; neither
 * exists yet, and stubbing an in-memory list here would create a second,
 * temporary data model that the real one would immediately have to replace.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.nav_projects),
                    style = MaterialTheme.typography.titleLarge,
                )
            },
            navigationIcon = {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(Dimens.minTouchTarget),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back),
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
                navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
            ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Dimens.spaceLg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(32.dp),
                    )
                    Text(
                        text = stringResource(R.string.empty_recent_projects),
                        style = EmptyStateTitleStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.phase1_placeholder),
                        style = EmptyStateBodyStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Present but inert-by-honesty: the button navigates nowhere
                    // useful until Phase 2 wires the SAF picker, so it is
                    // rendered as a disabled action rather than a live control
                    // that silently does nothing.
                    Button(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.size(
                            width = Dimens.minTouchTarget * 4,
                            height = Dimens.minTouchTarget,
                        ),
                    ) {
                        Text(
                            text = stringResource(R.string.action_open_project),
                            style = MaterialTheme.typography.labelLarge,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "Projects - dark", showBackground = true)
@Composable
private fun ProjectsScreenPreview() {
    NexGTheme(darkTheme = true) {
        ProjectsScreen(onBack = {})
    }
}
