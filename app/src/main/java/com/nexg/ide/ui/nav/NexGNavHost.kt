package com.nexg.ide.ui.nav

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nexg.ide.ui.screens.AiScreen
import com.nexg.ide.ui.screens.EditorScreen
import com.nexg.ide.ui.screens.HomeScreen
import com.nexg.ide.ui.screens.PlaceholderScreen
import com.nexg.ide.ui.screens.ProjectsScreen
import com.nexg.ide.ui.screens.SettingsScreen

/**
 * The app's navigation host.
 *
 * Insets: the [Scaffold] here is what consumes the system window insets and
 * passes the resolved values to its slots. Content therefore never adds its own
 * status-bar padding, and no screen contains a hard-coded status-bar offset.
 *
 * Bottom-bar visibility: hidden on the destinations that are full-bleed by
 * design (Editor, Terminal) and shown elsewhere. In Phase 1 those two are
 * placeholders, so the bar is effectively always visible; the condition exists
 * now so adding the real Editor later is a one-line change.
 */
@Composable
fun NexGNavHost(
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val showBottomBar = currentRoute !in FULL_BLEED_ROUTES

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NexGBottomBar(
                    currentRoute = currentRoute,
                    onNavigate = { route ->
                        navController.navigate(route.route) {
                            // Single top-level tab behaviour: switching tabs
                            // replaces the stack instead of growing it, so Back
                            // never walks a history of tab switches.
                            popUpTo(navController.graph.startDestinationId) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            NavHost(
                navController = navController,
                startDestination = NavRoute.startDestination,
                enterTransition = {
                    slideInHorizontally(
                        animationSpec = tween(TRANSITION_MS),
                        initialOffsetX = { it / 8 },
                    ) + fadeIn(tween(TRANSITION_MS))
                },
                exitTransition = { fadeOut(tween(TRANSITION_MS / 2)) },
                popEnterTransition = { fadeIn(tween(TRANSITION_MS)) },
                popExitTransition = {
                    slideOutHorizontally(
                        animationSpec = tween(TRANSITION_MS),
                        targetOffsetX = { it / 8 },
                    ) + fadeOut(tween(TRANSITION_MS / 2))
                },
            ) {
                composable(NavRoute.Home.route) {
                    HomeScreen(
                        onOpenProject = { navController.navigate(NavRoute.Projects.route) },
                        onNewProject = { navController.navigate(NavRoute.Projects.route) },
                        onOpenSettings = { navController.navigate(NavRoute.Settings.route) },
                    )
                }

                composable(NavRoute.Projects.route) {
                    ProjectsScreen(onBack = { navController.popBackStack() })
                }

                composable(NavRoute.Ai.route) {
                    AiScreen()
                }

                composable(NavRoute.Settings.route) {
                    SettingsScreen()
                }

                composable(NavRoute.Editor.route) {
                    EditorScreen(onBack = { navController.popBackStack() })
                }

                composable(NavRoute.Build.route) {
                    PlaceholderScreen(
                        title = "Build",
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(NavRoute.Logs.route) {
                    PlaceholderScreen(
                        title = "Logs",
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(NavRoute.Terminal.route) {
                    PlaceholderScreen(
                        title = "Terminal",
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}

/**
 * Destinations that own the whole screen and hide the bottom bar.
 *
 * Build deliberately is not here: once Build exists it needs to stay reachable
 * while a project is open, so it keeps the bar.
 */
private val FULL_BLEED_ROUTES = setOf(
    NavRoute.Editor.route,
    NavRoute.Terminal.route,
)

private const val TRANSITION_MS = 220
