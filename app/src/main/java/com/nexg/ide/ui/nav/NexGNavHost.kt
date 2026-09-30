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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.navArgument
import com.nexg.ide.NexGApp
import com.nexg.ide.core.result.AppError
import com.nexg.ide.core.result.AppResult
import com.nexg.ide.domain.model.Project
import com.nexg.ide.ui.screens.AiScreen
import com.nexg.ide.ui.ai.AiViewModel
import com.nexg.ide.ui.editor.EditorViewModel
import com.nexg.ide.ui.screens.EditorScreen
import com.nexg.ide.ui.screens.ExplorerScreen
import com.nexg.ide.ui.screens.HomeScreen
import com.nexg.ide.ui.screens.MissingProjectScreen
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
                    val container = remember { NexGApp.container() }
                    ProjectsScreen(
                        projectManager = container.projectManager,
                        onBack = { navController.popBackStack() },
                        onOpenFiles = { projectId ->
                            navController.navigate(NavRoute.Files.path(projectId))
                        },
                    )
                }

                // Phase 2. The project row is resolved from the database by id
                // rather than passed through the back stack, so a stale URI can
                // never be shown as if it were current.
                composable(
                    route = NavRoute.Files.route,
                    arguments = listOf(
                        navArgument(NavRoute.ARG_PROJECT_ID) { type = NavType.StringType },
                    ),
                ) { entry ->
                    val container = remember { NexGApp.container() }
                    val projectId = NavRoute.projectIdOf(entry.arguments)
                    val loaded = remember(projectId) {
                        mutableStateOf<AppResult<Project>?>(null)
                    }

                    LaunchedEffect(projectId) {
                        loaded.value = if (projectId == null) {
                            AppResult.Failure(
                                AppError(AppError.Kind.IO, "No project was selected"),
                            )
                        } else {
                            container.projectManager.project(projectId)
                        }
                    }

                    when (val state = loaded.value) {
                        // Still reading the row. Rendering nothing beats flashing
                        // an error that would disappear a frame later.
                        null -> Unit

                        is AppResult.Success -> ExplorerScreen(
                            project = state.data,
                            onBack = { navController.popBackStack() },
                            listDirectory = { uri -> container.fileManager.listDirectory(uri) },
                            createFile = { parent, name ->
                                container.fileManager.createFile(parent, name)
                            },
                            createDirectory = { parent, name ->
                                container.fileManager.createDirectory(parent, name) },
                            rename = { node, newName ->
                                container.fileManager.rename(node, newName)
                            },
                            recordOpen = { id, node ->
                                container.fileManager.recordOpen(id, node)
                            },
                            recentFiles = { id -> container.fileManager.recentFiles(id) },
                            // Phase 3: the tapped file's own URI travels to
                            // the editor, so the document that opens is the one
                            // the row showed. The Explorer stays a browser —
                            // no system picker and no external editor.
                            onOpenInEditor = { node ->
                                navController.navigate(NavRoute.Editor.path(node.uri, node.name))
                            },
                        )

                        is AppResult.Failure -> MissingProjectScreen(
                            message = state.error.describeWithStep(),
                            onBack = { navController.popBackStack() },
                        )

                        AppResult.Loading -> Unit
                    }
                }

                composable(NavRoute.Ai.route) {
                    val container = remember { NexGApp.container() }
                    val viewModel: AiViewModel = viewModel(
                        key = "ai-status",
                        factory = AiViewModel.factory(container.aiBackend, container.credentials),
                    )
                    AiScreen(viewModel = viewModel)
                }

                composable(NavRoute.Settings.route) {
                    val container = remember { NexGApp.container() }
                    val viewModel: AiViewModel = viewModel(
                        key = "settings-ai-status",
                        factory = AiViewModel.factory(container.aiBackend, container.credentials),
                    )
                    SettingsScreen(
                        viewModel = viewModel,
                        credentials = container.credentials,
                        developerTools = container.developerTools,
                    )
                }

                composable(
                    route = NavRoute.Editor.route,
                    arguments = listOf(
                        navArgument(NavRoute.ARG_URI) { type = NavType.StringType },
                        navArgument(NavRoute.ARG_NAME) { type = NavType.StringType },
                    ),
                ) { entry ->
                    val args = NavRoute.editorArgsOf(entry.arguments)
                    if (args == null) {
                        // Reached without a file: say so rather than opening an
                        // empty editor that looks like a failed load.
                        MissingProjectScreen(
                            message = "No file was selected to open",
                            onBack = { navController.popBackStack() },
                        )
                    } else {
                        val (uri, name) = args
                        val key = "editor:$uri"
                        val viewModel: EditorViewModel = viewModel(
                            key = key,
                            factory = EditorViewModel.factory(uri, name),
                        )
                        EditorScreen(
                            onBack = { navController.popBackStack() },
                            viewModel = viewModel,
                        )
                    }
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
    // The editor route carries arguments, so its registered route is the
    // pattern "editor?uri={uri}&name={name}". Comparing the bare "editor"
    // string would never match, the bottom bar would stay visible over the
    // code area, and the screen would lose the vertical space it needs.
    NavRoute.Editor.route,
    NavRoute.Terminal.route,
)

private const val TRANSITION_MS = 220
