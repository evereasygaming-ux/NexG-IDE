package com.nexg.ide.ui.nav

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.nexg.ide.ui.theme.Dimens

/**
 * Bottom navigation bar.
 *
 * Design notes that are constraints rather than taste:
 *  - Always icon **and** label. An icon-only bar fails for anyone who cannot
 *    learn the icon language, and the plan requires 48dp targets; the label is
 *    what makes the target's purpose readable rather than merely large.
 *  - The bar does not pad for the system navigation bar itself. It sits inside
 *    a Scaffold, so the bottom inset is already applied — adding a second
 *    inset here is the classic double-padding bug.
 */
@Composable
fun NexGBottomBar(
    currentRoute: String?,
    onNavigate: (NavRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(
        modifier = modifier,
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        BottomBarItems.forEach { item ->
            val selected = currentRoute == item.route.route
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(item.route) },
                icon = {
                    Icon(
                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                        // The visible label below already names the tab, so the
                        // icon is decorative here. Marking it null avoids a
                        // TalkBack announcement that repeats the label verbatim.
                        contentDescription = null,
                        modifier = Modifier.size(Dimens.spaceXl),
                    )
                },
                label = { Text(stringResource(item.labelRes)) },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
                    selectedTextColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                    indicatorColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                    unselectedIconColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                // Announce selection as state, not just as a changed label.
                modifier = Modifier.semantics {
                    stateDescription = if (selected) "Selected" else "Not selected"
                },
            )
        }
    }
}
