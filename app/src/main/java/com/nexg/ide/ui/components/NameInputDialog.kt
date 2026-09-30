package com.nexg.ide.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.nexg.ide.R
import com.nexg.ide.ui.theme.Dimens

/**
 * A one-field prompt for entering a new name.
 *
 * Shared by create-project, create-file, create-folder and rename, because all
 * four are the same interaction and the plan requires a single 48dp touch-target
 * floor and one place where validation feedback is rendered. Four near-copies of
 * this dialog would drift.
 *
 * [confirmActionLabel] is a string resource rather than a raw string so the
 * dialog reads "Create" when creating and "Save" when renaming.
 *
 * The confirm button stays enabled with a blank or malformed name and lets the
 * manager reject it: [com.nexg.ide.domain.model.NameValidator] is the single
 * source of truth for what a legal name is, and duplicating its rules in a
 * `isError` expression here would create a second, drifting definition. The
 * manager's message comes back through the caller's error channel.
 */
@Composable
fun NameInputDialog(
    title: String,
    label: String,
    initialValue: String = "",
    confirmActionLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(initialValue) { mutableStateOf(initialValue) }

    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(label) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                modifier = Modifier.heightIn(min = Dimens.minTouchTarget),
            ) {
                Text(text = confirmActionLabel)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = Dimens.minTouchTarget),
            ) {
                Text(text = stringResource(R.string.dialog_cancel))
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}
