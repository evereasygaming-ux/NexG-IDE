package com.nexg.ide.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.nexg.ide.R
import com.nexg.ide.core.result.AppResult
import com.nexg.ide.core.result.getOrNull
import com.nexg.ide.domain.model.FileNode
import com.nexg.ide.domain.model.Project
import com.nexg.ide.domain.model.RecentFile
import com.nexg.ide.ui.components.AuroraBackground
import com.nexg.ide.ui.components.GlassCard
import com.nexg.ide.ui.components.NameInputDialog
import com.nexg.ide.ui.theme.Dimens
import com.nexg.ide.ui.theme.EmptyStateTitleStyle
import com.nexg.ide.ui.theme.NexGTheme
import kotlinx.coroutines.launch

/**
 * The real File Explorer (PLAN.MD Phase 2, 4.2).
 *
 * Browses a project through `FileManager`, which reaches storage only via
 * `FileSystemPort`. This screen never touches a `ContentResolver`.
 *
 * Implemented: directory navigation with breadcrumbs, create file, create
 * folder, rename, recent files.
 *
 * Not implemented, deliberately: **delete**. Removing a document destroys user
 * data, and PLAN.MD assigns that to Phase 7 together with `OperationClassifier`,
 * approval and the protected-path rules. A delete reachable from this screen
 * would be a delete the app has no way to protect. Copy and move already exist
 * in `FileManager` but have no affordance here for the same reason — they belong
 * with that approval flow.
 *
 * The project is passed in rather than looked up by id, so the screen has no
 * loading state for it; the caller resolves the row and shows an error if it
 * cannot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorerScreen(
    project: Project,
    onBack: () -> Unit,
    listDirectory: suspend (uri: String) -> AppResult<List<FileNode>>,
    createFile: suspend (parentUri: String, name: String) -> AppResult<FileNode>,
    createDirectory: suspend (parentUri: String, name: String) -> AppResult<FileNode>,
    rename: suspend (node: FileNode, newName: String) -> AppResult<FileNode>,
    recordOpen: suspend (projectId: String, node: FileNode) -> AppResult<FileNode>,
    recentFiles: suspend (projectId: String) -> AppResult<List<RecentFile>>,
    onOpenInEditor: (node: FileNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // `null` while a directory read is in flight, so "still loading" stays
    // distinguishable from "this folder is genuinely empty".
    var entries by remember { mutableStateOf<List<FileNode>?>(null) }
    var recents by remember { mutableStateOf<List<RecentFile>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    // The trail of directories descended into. The project root is not a crumb,
    // so the parent of crumbs[i] is crumbs[i - 1], and an empty trail means the
    // current directory is the project root.
    val crumbs = remember { mutableStateListOf<FileNode>() }
    var currentDirUri by remember(project.rootUri) { mutableStateOf(project.rootUri) }

    var pendingDialog by remember { mutableStateOf<ExplorerDialog?>(null) }

    // `describeWithStep()` so a listing failure names the step. Bare `describe()`
    // rendered every Kind.IO failure — lost grant, refused create, failed
    // rename — as one identical sentence.
    fun describe(result: AppResult<*>): String =
        (result as? AppResult.Failure)?.error?.describeWithStep() ?: "Something went wrong"

    suspend fun load(uri: String) {
        busy = true
        val result = listDirectory(uri)
        busy = false
        when (result) {
            is AppResult.Success -> {
                entries = result.data
                currentDirUri = uri
            }
            // Cleared rather than left as-is: keeping the previous directory's
            // contents on screen would imply the new directory holds them too.
            is AppResult.Failure -> {
                entries = emptyList()
                snackbarHostState.showSnackbar(describe(result))
            }
            AppResult.Loading -> entries = emptyList()
        }
    }

    suspend fun reloadRecents() {
        recentFiles(project.id).getOrNull()?.let { recents = it }
    }

    // Descend: push the folder and show it.
    fun descend(node: FileNode) {
        crumbs.add(node)
        scope.launch { load(node.uri) }
    }

    // Ascend one level. The root is the end of the trail, so popping the last
    // crumb and landing on an empty trail means the root.
    fun ascend() {
        if (crumbs.isEmpty()) return
        crumbs.removeAt(crumbs.lastIndex)
        val parent = crumbs.lastOrNull()?.uri ?: project.rootUri
        scope.launch { load(parent) }
    }

    LaunchedEffect(project.rootUri) {
        load(project.rootUri)
        reloadRecents()
    }

    // While inside a subfolder, system back means "up one level" rather than
    // "leave the project" — otherwise the gesture skips a whole tree the user
    // can see on screen.
    BackHandler(enabled = crumbs.isNotEmpty(), onBack = ::ascend)

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = project.name,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        if (crumbs.isNotEmpty()) {
                            Text(
                                text = crumbs.last().name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = { if (crumbs.isNotEmpty()) ascend() else onBack() },
                        modifier = Modifier.size(Dimens.minTouchTarget),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { pendingDialog = ExplorerDialog.NewFile },
                        modifier = Modifier.size(Dimens.minTouchTarget),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.NoteAdd,
                            contentDescription = stringResource(R.string.action_new_file),
                        )
                    }
                    IconButton(
                        onClick = { pendingDialog = ExplorerDialog.NewFolder },
                        modifier = Modifier.size(Dimens.minTouchTarget),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CreateNewFolder,
                            contentDescription = stringResource(R.string.action_new_folder),
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
                verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
            ) {
                Breadcrumbs(
                    projectName = project.name,
                    crumbs = crumbs.toList(),
                    onNavigate = { index ->
                        // Keep the crumbs above the tapped one, then show it.
                        while (crumbs.size > index + 1) crumbs.removeAt(crumbs.lastIndex)
                        scope.launch { load(crumbs[index].uri) }
                    },
                )

                if (busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                // A `when` *with a subject* (`when (val x = entries)`) would read
                // `list.isEmpty()` as an equality test against that subject
                // instead of a condition, so this `when` has no subject: still
                // loading, then empty, then the listing.
                val list = entries
                when {
                    list == null -> Unit

                    list.isEmpty() -> GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.explorer_empty_directory),
                            style = EmptyStateTitleStyle,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    else -> LazyColumn(
                        // Weighted so the recent-files card below stays visible
                        // instead of being pushed off-screen by a long listing.
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
                    ) {
                        items(list, key = { it.uri }) { node ->
                            EntryRow(
                                node = node,
                                enabled = !busy,
                                onOpen = {
                                    if (node.isDirectory) {
                                        descend(node)
                                    } else {
                                        scope.launch {
                                            // History is not worth blocking the
                                            // user for: a failed write must not
                                            // stop the file from opening, so this
                                            // only reports, never blocks.
                                            recordOpen(project.id, node)
                                            reloadRecents()
                                            onOpenInEditor(node)
                                        }
                                    }
                                },
                                onRename = { pendingDialog = ExplorerDialog.Rename(node) },
                            )
                        }
                    }
                }

                if (recents.isNotEmpty()) {
                    RecentFilesSection(recents = recents)
                }
            }
        }
    }

    when (val dialog = pendingDialog) {
        null -> Unit

        is ExplorerDialog.NewFile -> NameInputDialog(
            title = stringResource(R.string.action_new_file),
            label = stringResource(R.string.dialog_file_name),
            confirmActionLabel = stringResource(R.string.dialog_create),
            onConfirm = { name ->
                pendingDialog = null
                scope.launch {
                    val result = createFile(currentDirUri, name)
                    if (result is AppResult.Success) {
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.explorer_created_file, result.data.name),
                        )
                        load(currentDirUri)
                    } else {
                        snackbarHostState.showSnackbar(describe(result))
                    }
                }
            },
            onDismiss = { pendingDialog = null },
        )

        is ExplorerDialog.NewFolder -> NameInputDialog(
            title = stringResource(R.string.action_new_folder),
            label = stringResource(R.string.dialog_folder_name),
            confirmActionLabel = stringResource(R.string.dialog_create),
            onConfirm = { name ->
                pendingDialog = null
                scope.launch {
                    val result = createDirectory(currentDirUri, name)
                    if (result is AppResult.Success) {
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.explorer_created_folder, result.data.name),
                        )
                        load(currentDirUri)
                    } else {
                        snackbarHostState.showSnackbar(describe(result))
                    }
                }
            },
            onDismiss = { pendingDialog = null },
        )

        is ExplorerDialog.Rename -> NameInputDialog(
            title = stringResource(R.string.action_rename),
            label = stringResource(R.string.dialog_rename),
            initialValue = dialog.node.name,
            confirmActionLabel = stringResource(R.string.dialog_save),
            onConfirm = { newName ->
                val target = dialog.node
                pendingDialog = null
                scope.launch {
                    val result = rename(target, newName)
                    if (result is AppResult.Success) {
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.explorer_renamed, result.data.name),
                        )
                        // A crumb pointing at the renamed folder keeps its stale
                        // name until it is replaced, so it is updated in place.
                        val index = crumbs.indexOfFirst { it.uri == target.uri }
                        if (index >= 0) {
                            while (crumbs.size > index + 1) crumbs.removeAt(crumbs.lastIndex)
                            crumbs[index] = result.data
                        }
                        load(currentDirUri)
                    } else {
                        snackbarHostState.showSnackbar(describe(result))
                    }
                }
            },
            onDismiss = { pendingDialog = null },
        )
    }
}

private sealed interface ExplorerDialog {
    data object NewFile : ExplorerDialog
    data object NewFolder : ExplorerDialog
    data class Rename(val node: FileNode) : ExplorerDialog
}

@Composable
private fun Breadcrumbs(
    projectName: String,
    crumbs: List<FileNode>,
    onNavigate: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
    ) {
        Text(
            text = projectName,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        crumbs.forEachIndexed { index, crumb ->
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = crumb.name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clickable { onNavigate(index) },
            )
        }
    }
}

@Composable
private fun EntryRow(
    node: FileNode,
    enabled: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onOpen),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.minTouchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (node.isDirectory) Icons.Outlined.Folder else Icons.Outlined.Description,
                contentDescription = null,
                tint = if (node.isDirectory) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(24.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Dimens.spaceMd),
                verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
            ) {
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!node.isDirectory && node.sizeBytes > 0L) {
                    Text(
                        text = humanSize(node.sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.size(Dimens.minTouchTarget),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.action_rename),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    // Rename only. Delete is absent on purpose — see the class
                    // comment: it arrives with Phase 7's approval flow.
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_rename)) },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                        modifier = Modifier.heightIn(min = Dimens.minTouchTarget),
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentFilesSection(
    recents: List<RecentFile>,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
            Text(
                text = stringResource(R.string.section_recent_files),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            recents.take(RECENTS_SHOWN).forEach { recent ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (recent.isDirectory) {
                            Icons.Outlined.Folder
                        } else {
                            Icons.Outlined.Description
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = recent.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = Dimens.spaceSm),
                    )
                }
            }
        }
    }
}

@Composable
private fun humanSize(bytes: Long): String = when {
    bytes >= 1_048_576L -> stringResource(R.string.size_megabytes, bytes / 1_048_576.0)
    bytes >= 1_024L -> stringResource(R.string.size_kilobytes, bytes / 1_024.0)
    else -> stringResource(R.string.size_bytes, bytes)
}

private const val RECENTS_SHOWN = 5

@Preview(name = "Explorer - dark", showBackground = true)
@Composable
private fun ExplorerScreenPreview() {
    val sampleProject = Project(
        id = "p1",
        name = "Aurora",
        rootUri = "content://tree/primary%3AAurora",
        createdAt = 0L,
        lastOpenedAt = 0L,
    )
    NexGTheme(darkTheme = true) {
        ExplorerScreen(
            project = sampleProject,
            onBack = {},
            listDirectory = { AppResult.Success(sampleEntries) },
            createFile = { _, _ -> AppResult.Loading },
            createDirectory = { _, _ -> AppResult.Loading },
            rename = { _, _ -> AppResult.Loading },
            recordOpen = { _, _ -> AppResult.Loading },
            recentFiles = { AppResult.Success(emptyList()) },
            onOpenInEditor = {},
        )
    }
}

private val sampleEntries = listOf(
    FileNode(
        uri = "content://tree/primary%3AAurora/document/app",
        name = "app",
        isDirectory = true,
    ),
    FileNode(
        uri = "content://tree/primary%3AAurora/document/gradlew",
        name = "gradlew",
        isDirectory = false,
        sizeBytes = 8_676L,
    ),
    FileNode(
        uri = "content://tree/primary%3AAurora/document/settings.gradle.kts",
        name = "settings.gradle.kts",
        isDirectory = false,
        sizeBytes = 312L,
    ),
)
