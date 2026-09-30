package com.nexg.ide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nexg.ide.R
import com.nexg.ide.ui.theme.Dimens

/** The tab strip above the code area. */
@Composable
fun EditorTabRow(
    tabs: List<TabItem>,
    activeUri: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(tabs) { tab ->
            EditorTab(
                tab = tab,
                active = tab.uri == activeUri,
                onSelect = { onSelect(tab.uri) },
                onClose = { onClose(tab.uri) },
            )
        }
    }
}

/** One open document in the tab strip. */
data class TabItem(
    val uri: String,
    val name: String,
    val dirty: Boolean,
    val readOnly: Boolean,
)

@Composable
private fun EditorTab(
    tab: TabItem,
    active: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val background = if (active) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.surface
    }
    Row(
        modifier = Modifier
            .padding(end = Dimens.spaceXs)
            .background(background, MaterialTheme.shapes.small)
            .clickable(onClick = onSelect)
            .padding(start = Dimens.spaceSm, end = Dimens.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tab.readOnly) {
            Text(
                text = stringResource(R.string.editor_read_only_badge),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = Dimens.spaceXs),
            )
        }
        Text(
            text = tab.name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        // The unsaved dot is a filled circle rather than an asterisk or a
        // colour change: it is the one marker that does not depend on
        // distinguishing text, and it survives being truncated to a narrow tab.
        if (tab.dirty) {
            Text(
                text = "●",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = Dimens.spaceXs),
            )
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier.size(Dimens.minTouchTarget / 2),
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.editor_close_tab),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}