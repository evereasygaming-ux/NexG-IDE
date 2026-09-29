package com.nexg.ide.ui.nav

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.ui.graphics.vector.ImageVector
import com.nexg.ide.R

/**
 * The navigation graph's routes, declared in one place.
 *
 * PLAN.MD 6 lists the major targets: Projects, Files, Editor, Build, AI, Logs,
 * Settings. Phase 1 declares all of them so the graph is stable and a later
 * phase adds behaviour rather than renumbering the shell — but only the four
 * Tier 1 destinations appear in the bottom bar. Build, Logs, Terminal and
 * Editor are reachable, and visibly marked as not yet implemented, so the app
 * never looks like it has a working terminal when it does not.
 */
sealed class NavRoute(val route: String) {

    data object Home : NavRoute("home")
    data object Projects : NavRoute("projects")
    data object Ai : NavRoute("ai")
    data object Settings : NavRoute("settings")

    // Declared, not yet in the bottom bar (Tier 2, later phases).
    data object Files : NavRoute("files")
    data object Editor : NavRoute("editor")
    data object Build : NavRoute("build")
    data object Logs : NavRoute("logs")
    data object Terminal : NavRoute("terminal")

    companion object {
        val startDestination: String = Home.route

        fun isKnown(route: String?): Boolean = all.any { it.route == route }

        val all: List<NavRoute> = listOf(
            Home, Projects, Ai, Settings, Files, Editor, Build, Logs, Terminal,
        )
    }
}

/**
 * A bottom-bar destination.
 *
 * Every destination carries both a selected and an unselected icon. The pair
 * is what lets a user who cannot distinguish the two colours still tell which
 * tab is active, which is the point of not relying on colour alone.
 */
data class BottomBarItem(
    val route: NavRoute,
    @StringRes val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

val BottomBarItems: List<BottomBarItem> = listOf(
    BottomBarItem(
        route = NavRoute.Home,
        labelRes = R.string.nav_home,
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home,
    ),
    BottomBarItem(
        route = NavRoute.Projects,
        labelRes = R.string.nav_projects,
        selectedIcon = Icons.Filled.Folder,
        unselectedIcon = Icons.Outlined.Folder,
    ),
    BottomBarItem(
        route = NavRoute.Ai,
        labelRes = R.string.nav_ai,
        selectedIcon = Icons.Filled.SmartToy,
        unselectedIcon = Icons.Outlined.SmartToy,
    ),
    BottomBarItem(
        route = NavRoute.Settings,
        labelRes = R.string.nav_settings,
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
    ),
)
