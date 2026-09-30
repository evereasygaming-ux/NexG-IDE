package com.nexg.ide.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexg.ide.R
import com.nexg.ide.core.result.AppResult
import com.nexg.ide.domain.model.AiEvent
import com.nexg.ide.domain.model.AiRequest
import com.nexg.ide.domain.model.BackendHealth
import com.nexg.ide.domain.port.AiBackend
import com.nexg.ide.domain.port.CredentialKey
import com.nexg.ide.domain.port.CredentialStore
import com.nexg.ide.domain.port.DeveloperToolHealth
import com.nexg.ide.domain.port.DeveloperToolPort
import com.nexg.ide.ui.ai.AiViewModel
import com.nexg.ide.ui.components.GlassCard
import com.nexg.ide.ui.theme.Dimens
import com.nexg.ide.ui.theme.NexGTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Settings destination (Phase 4: BYOK credential entry lands here).
 *
 * The bring-your-own-key screen stores the Gemini key through [CredentialStore]
 * (Keystore-backed on device) and offers a live connection test through
 * [AiViewModel]. Developer tools render their true availability — at this
 * phase the tool runner is not shipped, so the row says exactly that instead of
 * pretending a CLI exists.
 */
@Composable
fun SettingsScreen(
    viewModel: AiViewModel,
    credentials: CredentialStore,
    developerTools: DeveloperToolPort,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Deliberately `remember`, not `rememberSaveable`. A saveable holder
    // serialises into the Activity's saved-instance-state bundle, which the
    // system may persist to disk, so an unsaved plaintext key typed here would
    // outlive the process outside the Keystore-backed CredentialStore. The
    // draft is cleared on save, so losing it across a config change costs the
    // user nothing. Never change this back to `rememberSaveable`.
    var draftKey by remember { mutableStateOf("") }
    var toolHealth by remember { mutableStateOf<DeveloperToolHealth?>(null) }

    LaunchedEffect(developerTools) {
        toolHealth = developerTools.health()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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
                    title = stringResource(R.string.settings_appearance),
                    subtitle = stringResource(R.string.settings_appearance_detail),
                )
                SettingRow(
                    icon = Icons.Outlined.Security,
                    title = stringResource(R.string.settings_ai_credentials),
                    subtitle = aiCredentialSubtitle(state),
                )
                SettingRow(
                    icon = Icons.Outlined.Settings,
                    title = stringResource(R.string.settings_dev_tools),
                    subtitle = developerToolsSubtitle(toolHealth),
                )
                SettingRow(
                    icon = Icons.Outlined.BugReport,
                    title = stringResource(R.string.settings_logs),
                    subtitle = stringResource(R.string.settings_logs_detail),
                )
            }
        }

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd)) {
                Text(
                    text = stringResource(R.string.settings_ai_credentials),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                if (state.keyConfigured) {
                    Text(
                        text = stringResource(R.string.settings_ai_key_set),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    healthLine(state)

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
                    ) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    credentials.clear(CredentialKey.GEMINI_API_KEY)
                                    viewModel.refreshConfigurationOnly()
                                }
                            },
                        ) {
                            Text(stringResource(R.string.settings_ai_clear))
                        }
                        Button(onClick = viewModel::refresh) {
                            Text(stringResource(R.string.settings_ai_test))
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = draftKey,
                        onValueChange = { draftKey = it },
                        label = { Text(stringResource(R.string.settings_ai_key_label)) },
                        placeholder = { Text(stringResource(R.string.settings_ai_key_hint)) },
                        singleLine = true,
                        // Masks the field so the key is not left on screen, in a
                        // screenshot or in a screen recording. This is purely a
                        // visual transform: `value`/`onValueChange` are untouched,
                        // so typing, pasting and selection behave as before.
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.settings_ai_key_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            val value = draftKey.trim()
                            if (value.isNotEmpty()) {
                                scope.launch {
                                    credentials.put(CredentialKey.GEMINI_API_KEY, value)
                                }
                                draftKey = ""
                                viewModel.refreshConfigurationOnly()
                            }
                        },
                        enabled = draftKey.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.settings_ai_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun aiCredentialSubtitle(state: com.nexg.ide.ui.ai.AiUiState): String {
    if (!state.keyConfigured) return stringResource(R.string.settings_ai_key_missing)
    return stringResource(R.string.settings_ai_key_set)
}

@Composable
private fun developerToolsSubtitle(health: DeveloperToolHealth?): String {
    if (health == null) return stringResource(R.string.settings_ai_checking)
    return if (health.available) {
        "Atlassian CLI ready: ${health.detail}"
    } else {
        stringResource(R.string.settings_dev_tools_unavailable)
    }
}

@Composable
private fun healthLine(state: com.nexg.ide.ui.ai.AiUiState) {
    when (val health = state.health) {
        is AppResult.Success -> Text(
            text = healthTestResult(health.data),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        AppResult.Loading -> Text(
            text = stringResource(R.string.settings_ai_checking),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is AppResult.Failure -> Text(
            text = stringResource(R.string.settings_ai_unreachable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun healthTestResult(health: BackendHealth): String = when {
    health.reachable && health.authorized ->
        stringResource(R.string.settings_ai_ok) + " · " + health.model
    health.reachable ->
        stringResource(R.string.settings_ai_bad_key) + " · " + health.model
    else -> stringResource(R.string.settings_ai_unreachable)
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

// --------------------------------------------------------------------- preview

/**
 * Preview-only stand-ins, reachable solely from the `@Preview` beneath and never
 * from application code. Both show the "key set, connection OK" state so the
 * Settings layout renders with content; the AI screen preview uses its own
 * unconfigured store.
 */
private class SettingsPreviewCredentials : CredentialStore {
    // Non-null so the preview renders the "key set" state without a network.
    private var value: String? = "preview-key"

    override suspend fun put(key: CredentialKey, value: String) {
        this.value = value
    }

    override suspend fun get(key: CredentialKey): String? = value

    override suspend fun clear(key: CredentialKey) {
        value = null
    }
}

private object SettingsPreviewBackend : AiBackend {
    override val id: String = "gemini"
    override val requiresNetwork: Boolean = true
    override suspend fun health(): BackendHealth =
        BackendHealth(reachable = true, authorized = true, model = "gemini-2.0-flash", detail = "ok")
    override suspend fun complete(request: AiRequest): Flow<AiEvent> = flowOf()
}

private object SettingsPreviewDeveloperTools : DeveloperToolPort {
    override val id: String = "atlassian-cli"
    override suspend fun health(): DeveloperToolHealth = DeveloperToolHealth(false, "unavailable")
    override fun run(request: com.nexg.ide.domain.port.DeveloperToolRequest): Flow<com.nexg.ide.domain.port.DeveloperToolEvent> =
        flowOf(com.nexg.ide.domain.port.DeveloperToolEvent.Unavailable)
}

@Composable
private fun settingsPreviewViewModel(): AiViewModel =
    remember { AiViewModel(SettingsPreviewBackend, SettingsPreviewCredentials()) }

@Preview(name = "Settings - dark", showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    NexGTheme(darkTheme = true) {
        SettingsScreen(
            viewModel = settingsPreviewViewModel(),
            credentials = SettingsPreviewCredentials(),
            developerTools = SettingsPreviewDeveloperTools,
        )
    }
}