package com.nexg.ide.ui.nav

import android.net.Uri
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
import com.nexg.ide.domain.uri.SafUri

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

    /**
     * Phase 3: the editor, carrying the file it should open.
     *
     * Both arguments are URL-encoded. A SAF document URI contains `/`, `:` and
     * `%3A`, and any of those arriving raw would be read as route syntax — a
     * document id like `primary%3ADocuments` would arrive as `primary`, and the
     * editor would open the wrong folder.
     *
     * Encoding here is the *only* encode/decode pair in the trip: the framework
     * decodes once when matching the route, and [editorArgsOf] takes the result
     * as it stands. The buffer is therefore opened from byte-for-byte the same
     * string the Explorer listed, which is the property the Phase 3 device bug
     * was about.
     */
    data object Editor : NavRoute("editor?uri={uri}&name={name}") {
        /**
         * Encoded with [SafUri.percentEncode] rather than `Uri.encode` for two
         * reasons, and the second is the one that matters.
         *
         * First, so encoding lives beside [SafUri.percentDecode] — the pair that
         * has to agree, in the same file, tested against each other.
         *
         * Second, so the whole round trip is pure Kotlin and therefore testable
         * without `android.os.Bundle`. The Phase 3 defect was a *third* encode or
         * decode on a path that no JVM test could reach, and it shipped anyway;
         * a round trip that cannot be exercised from a unit test is a round trip
         * that will be broken again without anyone noticing.
         */
        fun path(uri: String, name: String): String =
            "editor?uri=${SafUri.percentEncode(uri)}&name=${SafUri.percentEncode(name)}"
    }
    data object Build : NavRoute("build")
    data object Logs : NavRoute("logs")
    data object Terminal : NavRoute("terminal")

    /**
     * Phase 2 wires this route up.
     *
     * It carries the project id and nothing else: the name, root URI and layout
     * already live in the `projects` table, so passing them as arguments too
     * would mean two sources of truth for the same project, one of which the
     * Explorer would have to trust over the database. The Explorer loads the row
     * by id instead. Ids are UUIDs, so no URL encoding is needed here.
     */
    data object Files : NavRoute("files?projectId={projectId}") {
        fun path(projectId: String): String = "files?projectId=$projectId"
    }

    companion object {
        val startDestination: String = Home.route

        fun isKnown(route: String?): Boolean = all.any { it.route == route }

        val all: List<NavRoute> = listOf(
            Home, Projects, Ai, Settings, Files, Editor, Build, Logs, Terminal,
        )

        const val ARG_PROJECT_ID: String = "projectId"
        const val ARG_URI: String = "uri"
        const val ARG_NAME: String = "name"

        fun projectIdOf(arguments: android.os.Bundle?): String? =
            arguments?.getString(ARG_PROJECT_ID)

        /**
         * The file to open, or `null` when the route was reached without one.
         *
         * Both values are taken **verbatim**. Navigation Compose percent-decodes
         * string arguments itself when it matches a route, so these are already
         * decoded — exactly once, by the framework. See
         * [SafUri.fromRouteArgument] for why a second decode is not a harmless
         * belt-and-braces measure: it is the bug the Phase 3 device run found.
         */
        fun editorArgsOf(arguments: android.os.Bundle?): Pair<String, String>? =
            editorFileFromArguments(
                uri = arguments?.getString(ARG_URI),
                name = arguments?.getString(ARG_NAME),
            )

        /**
         * The pure half of [editorArgsOf], separated only so the URI handoff can
         * be tested without a `Bundle` — which is the test that was missing when
         * this shipped broken.
         *
         * Both values pass through [SafUri.fromRouteArgument] untouched. There is
         * exactly one decode on this trip and the framework already performed it;
         * this function is where a second one must never appear.
         */
        fun editorFileFromArguments(uri: String?, name: String?): Pair<String, String>? {
            if (uri == null || name == null) return null
            return SafUri.fromRouteArgument(uri) to SafUri.fromRouteArgument(name)
        }
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
