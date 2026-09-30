package com.nexg.ide.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.nexg.ide.ui.ai.AiUiState
import com.nexg.ide.ui.ai.AiViewModel
import com.nexg.ide.ui.components.GlassCard
import com.nexg.ide.ui.components.VoiceOrbScaffold
import com.nexg.ide.ui.theme.Dimens
import com.nexg.ide.ui.theme.NexGTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * AI destination (Phase 4: the boundary is real, the chat is next).
 *
 * What is real here: the backend identity, connectivity, whether a key is
 * stored, and the "add your key in Settings" path. The prompt input stays an
 * unchanged, disabled scaffold because streaming chat is Phase 5 — an enabled
 * input that did nothing would be a fake assistant, and this screen is
 * documented never to ship one.
 */
@Composable
fun AiScreen(
    viewModel: AiViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Dimens.spaceLg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceLg),
    ) {
        Text(
            text = stringResource(R.string.section_ai_assistant),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxWidth(),
        )

        VoiceOrbScaffold(diameter = 96.dp)

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            StatusContent(state = state, onRefresh = viewModel::refresh)
        }

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd)) {
                OutlinedTextField(
                    value = "",
                    onValueChange = {},
                    readOnly = true,
                    enabled = false,
                    label = { Text(stringResource(R.string.ai_prompt_placeholder)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.SmartToy,
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Dimens.minTouchTarget)
                        .semantics {
                            contentDescription = "AI prompt input, active when streaming chat ships"
                        },
                )
                Text(
                    text = stringResource(R.string.ai_phase5_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private enum class AiStatusKind { NO_KEY, OK, KEY_REJECTED, UNREACHABLE, CHECKING, ERROR }

@Composable
private fun StatusContent(
    state: AiUiState,
    onRefresh: () -> Unit,
) {
    val kind = statusKind(state)
    val detail = statusDetail(state)

    val (icon, statusRes) = when (kind) {
        AiStatusKind.NO_KEY -> Icons.Outlined.Key to R.string.ai_no_key
        AiStatusKind.OK -> Icons.Outlined.CheckCircle to R.string.settings_ai_ok
        AiStatusKind.KEY_REJECTED -> Icons.Outlined.Warning to R.string.settings_ai_bad_key
        AiStatusKind.UNREACHABLE -> Icons.Outlined.CloudOff to R.string.settings_ai_unreachable
        AiStatusKind.CHECKING -> Icons.Outlined.CloudOff to R.string.settings_ai_checking
        AiStatusKind.ERROR -> Icons.Outlined.CloudOff to R.string.settings_ai_unreachable
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
                Text(
                    text = stringResource(statusRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        IconButton(
            onClick = onRefresh,
            modifier = Modifier.semantics { contentDescription = "Check connection" },
        ) {
            Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = null,
            )
        }
    }
}

private fun statusKind(state: AiUiState): AiStatusKind = if (!state.isKeyConfigured) {
    AiStatusKind.NO_KEY
} else {
    when (val health = state.health) {
        is AppResult.Success -> when {
            health.data.reachable && health.data.authorized -> AiStatusKind.OK
            health.data.reachable -> AiStatusKind.KEY_REJECTED
            else -> AiStatusKind.UNREACHABLE
        }
        AppResult.Loading -> AiStatusKind.CHECKING
        is AppResult.Failure -> AiStatusKind.ERROR
    }
}

private fun statusDetail(state: AiUiState): String = when (val health = state.health) {
    is AppResult.Success -> when {
        state.isKeyConfigured && health.data.reachable && health.data.authorized ->
            health.data.model
        state.isKeyConfigured && !health.data.reachable -> health.data.detail
        state.isKeyConfigured -> health.data.model
        else -> ""
    }
    is AppResult.Failure -> health.error.describeWithStep()
    AppResult.Loading -> ""
}

private val AiUiState.isKeyConfigured: Boolean get() = keyConfigured

// --------------------------------------------------------------------- preview

/**
 * Preview-only stand-ins, reachable solely from the `@Preview` beneath and never
 * from application code. The status content needs a backend that answers without
 * a network, so the preview supplies the smallest honest answer ("no key").
 */
private class AiPreviewCredentials : CredentialStore {
    override suspend fun put(key: CredentialKey, value: String) {}
    override suspend fun get(key: CredentialKey): String? = null
    override suspend fun clear(key: CredentialKey) {}
}

private object AiPreviewBackend : AiBackend {
    override val id: String = "gemini"
    override val requiresNetwork: Boolean = true
    override suspend fun health(): BackendHealth =
        BackendHealth(reachable = false, authorized = false, model = "preview", detail = "preview")
    override suspend fun complete(request: AiRequest): Flow<AiEvent> = flowOf()
}

private fun aiPreviewViewModel(): AiViewModel = AiViewModel(AiPreviewBackend, AiPreviewCredentials())

@Preview(name = "AI - dark", showBackground = true)
@Composable
private fun AiScreenPreview() {
    NexGTheme(darkTheme = true) {
        AiScreen(viewModel = aiPreviewViewModel())
    }
}