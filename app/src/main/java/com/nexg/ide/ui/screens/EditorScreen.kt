package com.nexg.ide.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Code
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexg.ide.R
import com.nexg.ide.ui.components.GlassCard
import com.nexg.ide.ui.theme.Dimens
import com.nexg.ide.ui.theme.EmptyStateBodyStyle
import com.nexg.ide.ui.theme.NexGTheme

/**
 * Editor destination (Phase 1 scaffold).
 *
 * Full-bleed: it hides the bottom navigation bar, because the real editor will
 * own the whole screen and a persistent tab bar would steal vertical space from
 * the code area on an already-short phone display.
 *
 * There is no editor, no syntax highlighting and no file backing here. The
 * real implementation is a later phase.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.nav_editor),
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
                        imageVector = Icons.Outlined.Code,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(32.dp),
                    )
                    Text(
                        text = stringResource(R.string.nav_editor),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.phase1_placeholder),
                        style = EmptyStateBodyStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Preview(name = "Editor - dark", showBackground = true)
@Composable
private fun EditorScreenPreview() {
    NexGTheme(darkTheme = true) {
        EditorScreen(onBack = {})
    }
}
