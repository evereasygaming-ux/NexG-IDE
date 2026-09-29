package com.nexg.ide.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.nexg.ide.ui.components.VoiceOrbScaffold
import com.nexg.ide.ui.theme.Dimens
import com.nexg.ide.ui.theme.NexGTheme

/**
 * AI destination (Phase 1 scaffold).
 *
 * There is no Gemini client, no key storage and no network call here. The text
 * field is rendered with a real label and correct semantics purely so the layout
 * is honest and accessible, but it holds no state that is sent anywhere.
 *
 * The voice orb is included as a non-interactive scaffold — see
 * [VoiceOrbScaffold] for why it announces itself as unavailable.
 */
@Composable
fun AiScreen(
    modifier: Modifier = Modifier,
) {
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
                            contentDescription = "AI prompt input, not available in this build"
                        },
                )
                Text(
                    text = stringResource(R.string.phase1_placeholder),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview(name = "AI - dark", showBackground = true)
@Composable
private fun AiScreenPreview() {
    NexGTheme(darkTheme = true) {
        AiScreen()
    }
}
