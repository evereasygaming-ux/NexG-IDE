package com.nexg.ide.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Construction
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
import com.nexg.ide.ui.theme.NexGTheme

/**
 * The shared scaffold for every screen that is planned but not built.
 *
 * The point of this file is honesty. Build, Logs, Terminal and Editor all look
 * like working screens at a glance, so each one says in plain text that it is a
 * scaffold and names nothing as done. A placeholder that quietly looks like a
 * terminal is worse than an empty screen, because the user will try to use it.
 *
 * `TopAppBar` here is the reason Scaffold-inset handling stays in one place:
 * this bar participates in the same inset pipeline as the bottom bar rather
 * than padding itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceholderScreen(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String = stringResource(R.string.phase1_placeholder),
) {
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title, style = MaterialTheme.typography.titleLarge) },
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
                        imageVector = Icons.Outlined.Construction,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(32.dp),
                    )
                    Text(
                        text = "$title is not available yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = detail,
                        style = EmptyStateBodyStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Preview(name = "Placeholder - dark", showBackground = true)
@Composable
private fun PlaceholderScreenPreviewDark() {
    NexGTheme(darkTheme = true) {
        PlaceholderScreen(title = "Build", onBack = {})
    }
}
