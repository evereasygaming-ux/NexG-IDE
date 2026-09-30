package com.nexg.ide.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexg.ide.R
import com.nexg.ide.application.ProjectManager
import com.nexg.ide.core.result.AppResult
import com.nexg.ide.core.result.errorOrNull
import com.nexg.ide.core.result.getOrNull
import com.nexg.ide.domain.model.Project
import com.nexg.ide.domain.model.ProjectLayout
import com.nexg.ide.ui.components.AuroraBackground
import com.nexg.ide.ui.components.GlassCard
import com.nexg.ide.ui.components.NameInputDialog
import com.nexg.ide.ui.theme.Dimens
import com.nexg.ide.ui.theme.EmptyStateTitleStyle
import com.nexg.ide.ui.theme.NexGTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * The real Projects screen (PLAN.MD Phase 2).
 *
 * Reads and writes through [ProjectManager] only, and keeps no project list of
 * its own: the list is the Room-backed flow from `observeProjects()`, so
 * anything registered or created anywhere in the app re-renders this screen
 * without a manual refresh.
 *
 * Storage access happens in exactly one place. Android's SAF has no callback
 * for directory access, so picking a folder always needs the system picker —
 * that is the only `ActivityResultContracts` use here, and the URI it returns
 * goes straight to the manager.
 *
 * The two entry points are deliberately asymmetric, and the difference is what
 * [ProjectsFlow] encodes. Open Project asks for a folder straight away, because
 * the folder *is* the project. New Project asks for a name first and only then
 * for a destination folder, because a project needs a name before it can be
 * created and the folder is needed only as the parent to create it under. Both
 * eventually use the same picker launcher, and the intent that arrives back
 * with its result is what tells the two apart — so that intent is state, held
 * in [ProjectsFlow] and unit-tested, not a local variable read after the fact.
 *
 * Why no ViewModel: every decision this screen would hold — what a legal name
 * is, whether a tree is an Android+Gradle layout, whether a project is a
 * duplicate — belongs to the manager, which is unit-tested. A ViewModel holding
 * a parallel copy of that logic would be the untested layer, and the plan puts
 * protection rules outside the UI for exactly this reason. The state here is
 * only "which dialog is open" and "is a write in flight".
 */
@Composable
fun ProjectsScreen(
    projectManager: ProjectManager,
    onBack: () -> Unit,
    onOpenFiles: (projectId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ProjectsContent(
        projects = projectManager.observeProjects(),
        onOpenExisting = { treeUri -> projectManager.openExistingProject(treeUri) },
        onCreateProject = { parentUri, name -> projectManager.createProject(parentUri, name) },
        onBack = onBack,
        onOpenFiles = onOpenFiles,
        modifier = modifier,
    )
}

/**
 * The screen's behaviour with its two effects injected.
 *
 * Split out from [ProjectsScreen] so `@Preview` and any future screenshot test
 * can render the real list, the real empty state and the real error wiring
 * without standing up Room or SAF. The preview below drives this with a static
 * flow, which is a real render, not a mock of one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectsContent(
    projects: Flow<List<Project>>,
    onOpenExisting: suspend (treeUri: String) -> AppResult<Project>,
    onCreateProject: suspend (parentTreeUri: String, name: String) -> AppResult<Project>,
    onBack: () -> Unit,
    onOpenFiles: (projectId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val loaded by projects.collectAsStateWithLifecycle(initialValue = null)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var busy by remember { mutableStateOf(false) }

    // Every routing decision lives in [ProjectsFlow]: which button armed the
    // picker, and whether a name is waiting for a destination. The screen only
    // holds the instance and renders what it says.
    var flow by remember { mutableStateOf(ProjectsFlow()) }

    val treePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val transition = flow.pickerReturned(uri?.toString())
        flow = transition.flow

        when (val effect = transition.effect) {
            is ProjectsEffect.OpenExisting -> scope.launch {
                busy = true
                val result = onOpenExisting(effect.treeUri)
                busy = false
                val project = result.getOrNull()
                if (project != null) {
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.projects_opened, project.name),
                    )
                    onOpenFiles(project.id)
                } else {
                    snackbarHostState.showSnackbar(result.describeToUser())
                }
            }

            is ProjectsEffect.CreateProject -> scope.launch {
                busy = true
                val result = onCreateProject(effect.parentTreeUri, effect.name)
                busy = false
                val project = result.getOrNull()
                if (project != null) {
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.projects_created, project.name),
                    )
                    // A created project opens immediately, exactly like an
                    // opened one. Without this the user was returned to a list
                    // they had to tap again, unsure whether anything happened.
                    onOpenFiles(project.id)
                } else {
                    snackbarHostState.showSnackbar(result.describeToUser())
                }
            }

            ProjectsEffect.None -> Unit
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.nav_projects),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(Dimens.minTouchTarget),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { innerPadding ->
        AuroraBackground(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = Dimens.spaceLg),
                verticalArrangement = Arrangement.spacedBy(Dimens.spaceLg),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
                ) {
                    Button(
                        onClick = {
                            flow = flow.openProjectPressed()
                            treePicker.launch(null)
                        },
                        enabled = !busy,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = Dimens.minTouchTarget),
                        shape = RoundedCornerShape(Dimens.cardCorner),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(R.string.action_open_project),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(start = Dimens.spaceSm),
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            // Name first, then destination. The picker is
                            // launched from the name prompt's confirm, not here.
                            flow = flow.createProjectPressed()
                        },
                        enabled = !busy,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = Dimens.minTouchTarget),
                        shape = RoundedCornerShape(Dimens.cardCorner),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(R.string.action_new_project),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(start = Dimens.spaceSm),
                        )
                    }
                }

                if (busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                // `null` means the first database read has not landed yet.
                // Rendering the empty state in that window would assert "you
                // have no projects" before the query has had a chance to run.
                when (val list = loaded) {
                    null -> Unit

                    else -> if (list.isEmpty()) {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.FolderOpen,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(32.dp),
                                )
                                Text(
                                    text = stringResource(R.string.empty_recent_projects),
                                    style = EmptyStateTitleStyle,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
                        ) {
                            items(list, key = { it.id }) { project ->
                                ProjectRow(
                                    project = project,
                                    enabled = !busy,
                                    onClick = { onOpenFiles(project.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // The name prompt is the create flow's first screen. Confirming arms the
    // destination picker; the create itself happens when the picker returns, in
    // the effect branch above, so that a cancelled picker produces no project.
    if (flow.nameDialogOpen) {
        NameInputDialog(
            title = stringResource(R.string.action_new_project),
            label = stringResource(R.string.dialog_project_name),
            initialValue = flow.pendingCreateName.orEmpty(),
            confirmActionLabel = stringResource(R.string.dialog_create),
            onConfirm = { name ->
                flow = flow.nameSubmitted(name)
                treePicker.launch(null)
            },
            onDismiss = { flow = flow.nameDialogDismissed() },
        )
    }
}

/**
 * Turns a failed result into something safe to show.
 *
 * [AppError.describe] is the phrase written for users; the error's own `message`
 * can embed a tree URI, so it is deliberately not rendered here. A failure with
 * no error object is not something the managers produce, but the fallback keeps
 * the UI from ever rendering null.
 *
 * `describeWithStep()` rather than `describe()`: the create path can fail at
 * several different IO steps, and rendering only the bare sentence is what made
 * a failed project creation undiagnosable from the screen. The appended step is
 * a fixed literal the code chose, so it stays URI-free and safe to show.
 */
private fun AppResult<*>.describeToUser(): String =
    errorOrNull()?.describeWithStep() ?: "Something went wrong"

@Composable
private fun ProjectRow(
    project: Project,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.minTouchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Dimens.spaceMd),
                verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
            ) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = project.layout.label(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProjectLayout.label(): String = stringResource(
    when (this) {
        ProjectLayout.ANDROID_GRADLE -> R.string.layout_android_gradle
        ProjectLayout.GRADLE_ONLY -> R.string.layout_gradle_only
        ProjectLayout.UNKNOWN -> R.string.layout_unknown
    },
)

@Preview(name = "Projects - with projects", showBackground = true)
@Composable
private fun ProjectsScreenPreview() {
    NexGTheme(darkTheme = true) {
        ProjectsContent(
            projects = flowOf(
                listOf(
                    Project(
                        id = "1",
                        name = "Aurora",
                        rootUri = "content://tree/primary%3AAurora",
                        createdAt = 0L,
                        lastOpenedAt = 0L,
                        layout = ProjectLayout.ANDROID_GRADLE,
                    ),
                    Project(
                        id = "2",
                        name = "scratch",
                        rootUri = "content://tree/primary%3Ascratch",
                        createdAt = 0L,
                        lastOpenedAt = 0L,
                        layout = ProjectLayout.UNKNOWN,
                    ),
                ),
            ),
            onOpenExisting = { AppResult.Loading },
            onCreateProject = { _, _ -> AppResult.Loading },
            onBack = {},
            onOpenFiles = {},
        )
    }
}

@Preview(name = "Projects - empty", showBackground = true)
@Composable
private fun ProjectsScreenEmptyPreview() {
    NexGTheme(darkTheme = true) {
        ProjectsContent(
            projects = flowOf(emptyList()),
            onOpenExisting = { AppResult.Loading },
            onCreateProject = { _, _ -> AppResult.Loading },
            onBack = {},
            onOpenFiles = {},
        )
    }
}
