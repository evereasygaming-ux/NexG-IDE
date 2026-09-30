package com.nexg.ide.ui.screens

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nexg.ide.R
import com.nexg.ide.ui.editor.EditorTabRow
import com.nexg.ide.ui.editor.EditorUiState
import com.nexg.ide.ui.editor.EditorViewModel
import com.nexg.ide.ui.editor.statusPosition
import com.nexg.ide.ui.editor.webview.CodeEditorHost
import com.nexg.ide.ui.editor.webview.EditorCommand
import com.nexg.ide.ui.editor.webview.EditorLanguageMapping
import com.nexg.ide.ui.theme.Dimens
import com.nexg.ide.ui.theme.EmptyStateBodyStyle
import com.nexg.ide.ui.theme.NexGTheme
import kotlinx.coroutines.launch

/**
 * The editor destination.
 *
 * The editable surface is a WebView running the bundled CodeMirror 6 editor.
 * Kotlin still owns opening, saving, tabs and the read-only rule; CodeMirror
 * owns typing, selection, copy and find. The screen is the seam between the two:
 * it renders [EditorUiState], hosts [CodeEditorHost], and pushes the
 * authoritative document into the WebView exactly when [EditorUiState.pushVersion]
 * or [EditorUiState.readyPulse] moves — never on a keystroke, which would echo
 * the user's own typing back at them.
 *
 * Full-bleed, as the nav host configures it: the bottom bar is hidden here so
 * the code area gets the whole height of a short phone display.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    onBack: () -> Unit,
    viewModel: EditorViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Read once, here, because the push lambdas run from a LaunchedEffect and
    // are therefore not @Composable.
    val dark = isSystemInDarkTheme()

    // A new host is created each time the composition is recreated (rotation),
    // so a fresh WebView boots, posts editorReady, and the readyPulse bump tells
    // the effect below to push the current document into it again.
    val host = remember(context.applicationContext, viewModel) {
        CodeEditorHost(context.applicationContext, viewModel::handleBridgeEvent)
    }
    DisposableEffect(host) {
        onDispose { host.release() }
    }

    LaunchedEffect(viewModel.requestedUri, viewModel.requestedName) {
        viewModel.open(viewModel.requestedUri, viewModel.requestedName)
    }

    // A system theme change leaves the page holding the palette it was last
    // given: the push effect below is keyed on the document and on readiness,
    // neither of which moves, so nothing would otherwise re-apply it and the
    // editor would keep a light surface in dark mode. The bridge gate coalesces
    // this against the initial push, so it cannot double-send, and this is the
    // only place theme is re-applied outside the push itself.
    LaunchedEffect(dark) {
        host.setTheme(dark)
    }

    EditorContent(
        state = state,
        onBack = onBack,
        onSave = { scope.launch { viewModel.save() } },
        onRevert = { scope.launch { viewModel.revert() } },
        onUndo = { host.runCommand(EditorCommand.UNDO) },
        onRedo = { host.runCommand(EditorCommand.REDO) },
        onFind = { host.runCommand(EditorCommand.FIND) },
        onSelectTab = viewModel::selectTab,
        onCloseTab = { uri -> scope.launch { viewModel.closeTab(uri) } },
        onShowGotoLine = viewModel::showGotoLine,
        onGotoLineChange = viewModel::setGotoLineInput,
        onGotoLineConfirm = { line -> scope.launch { viewModel.goToLine(line) } },
        onGotoLineDismiss = viewModel::closeGotoLine,
        buildEditor = { editorModifier ->
            AndroidView(
                factory = { host.view },
                modifier = editorModifier,
            )
        },
        pushInto = { target ->
            target.buffer?.let { buffer ->
                host.setTheme(dark)
                host.setDocument(buffer.text)
                host.setLanguage(EditorLanguageMapping.toJsId(buffer.languageId))
                host.setReadOnly(buffer.readOnly)
                host.setSelection(buffer.selectionStart, buffer.selectionEnd)
                if (!buffer.readOnly) host.requestFocus()
            }
        },
        pushSelection = { target ->
            target.buffer?.let { buffer ->
                host.setSelection(buffer.selectionStart, buffer.selectionEnd)
            }
        },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorContent(
    state: EditorUiState,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onRevert: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onFind: () -> Unit,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onShowGotoLine: () -> Unit,
    onGotoLineChange: (String) -> Unit,
    onGotoLineConfirm: (String) -> Unit,
    onGotoLineDismiss: () -> Unit,
    buildEditor: @Composable (Modifier) -> Unit,
    pushInto: (EditorUiState) -> Unit,
    pushSelection: (EditorUiState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val buffer = state.buffer
    val tabs = state.tabs

    // Push the authoritative document into a ready WebView. Keyed on the two
    // counters that only move for externally driven changes; a keystroke that
    // arrived from the WebView changes neither, so it is never re-pushed.
    LaunchedEffect(state.pushVersion, state.readyPulse) {
        if (state.editorReady && state.buffer != null) pushInto(state)
    }

    // Kotlin-initiated caret moves (goto line, tab switch) re-assert the
    // selection in the WebView without touching the document.
    LaunchedEffect(state.selectionRequest) {
        if (state.editorReady && state.buffer != null) pushSelection(state)
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = buffer?.name ?: stringResource(R.string.editor_no_open_file),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (buffer != null) {
                        val position = buffer.statusPosition()
                        Text(
                            text = stringResource(R.string.editor_status, position.first, position.second),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack, modifier = Modifier.size(Dimens.minTouchTarget)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back),
                    )
                }
            },
            actions = {
                IconButton(onClick = onUndo, enabled = !state.isReadOnly && buffer != null) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Undo,
                        contentDescription = stringResource(R.string.editor_undo),
                    )
                }
                IconButton(onClick = onRedo, enabled = !state.isReadOnly && buffer != null) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Redo,
                        contentDescription = stringResource(R.string.editor_redo),
                    )
                }
                IconButton(onClick = onFind, enabled = buffer != null) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = stringResource(R.string.editor_find),
                    )
                }
                IconButton(onClick = onShowGotoLine, enabled = buffer != null) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.editor_goto_line),
                    )
                }
                IconButton(onClick = onSave, enabled = state.isDirty && !state.isReadOnly) {
                    Icon(
                        imageVector = Icons.Outlined.Save,
                        contentDescription = stringResource(R.string.editor_save),
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
                navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                actionIconContentColor = MaterialTheme.colorScheme.onBackground,
            ),
        )

        if (buffer == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = state.errorMessage
                        ?: state.infoMessage
                        ?: stringResource(R.string.editor_no_open_file),
                    style = EmptyStateBodyStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        if (buffer.readOnly) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(
                        R.string.editor_read_only,
                        buffer.readOnlyReason.orEmpty(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(Dimens.spaceSm),
                )
            }
        }

        EditorTabRow(
            tabs = tabs,
            activeUri = buffer.uri,
            onSelect = onSelectTab,
            onClose = onCloseTab,
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            buildEditor(Modifier.fillMaxSize())
        }
    }

    if (state.showGotoLine) {
        AlertDialog(
            onDismissRequest = onGotoLineDismiss,
            title = { Text(stringResource(R.string.editor_goto_line)) },
            text = {
                OutlinedTextField(
                    value = state.gotoLineInput,
                    onValueChange = onGotoLineChange,
                    label = { Text(stringResource(R.string.editor_goto_line_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onGotoLineConfirm(state.gotoLineInput) },
                ) { Text(stringResource(R.string.dialog_ok)) }
            },
            dismissButton = {
                TextButton(onClick = onGotoLineDismiss) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }

    val message = state.errorMessage ?: state.infoMessage
    if (message != null && buffer != null) {
        Surface(
            color = if (state.errorMessage != null) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.errorMessage != null) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(Dimens.spaceSm),
            )
        }
    }
}

/** Mirrors the app theme into the CodeMirror palette. */
@Preview(name = "Editor - dark", showBackground = true)
@Composable
private fun EditorScreenPreview() {
    NexGTheme(darkTheme = true) {
        EditorContent(
            state = EditorUiState(),
            onBack = {},
            onSave = {},
            onRevert = {},
            onUndo = {},
            onRedo = {},
            onFind = {},
            onSelectTab = {},
            onCloseTab = {},
            onShowGotoLine = {},
            onGotoLineChange = {},
            onGotoLineConfirm = {},
            onGotoLineDismiss = {},
            buildEditor = {},
            pushInto = {},
            pushSelection = {},
        )
    }
}