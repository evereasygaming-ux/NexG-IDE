package com.nexg.ide.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nexg.ide.NexGApp
import com.nexg.ide.application.EditorManager
import com.nexg.ide.core.result.AppResult
import com.nexg.ide.domain.editor.EditorBuffer
import com.nexg.ide.domain.editor.EditorLogic
import com.nexg.ide.ui.editor.webview.EditorEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The editor screen's state and commands, behind the CodeMirror WebView.
 *
 * Ownership is split with the WebView so each side keeps what it is good at:
 *
 *  - CodeMirror owns the text surface: typing, caret, selection, native
 *    long-press/copy handles, undo/redo history, bracket handling and its own
 *    find/replace panel. It reports the whole document on every change via the
 *    bridge.
 *  - Kotlin owns everything that must survive the editor: the [EditorManager]
 *    buffers (dirty tracking, save/load, SAF), the tab strip, the 2 MB
 *    read-only rule, goto-line and the status line.
 *
 * The ViewModel therefore stores no edit rules of its own — there is no
 * auto-indent or bracket logic left to run per keystroke, because CodeMirror's
 * parser does that. What it must keep correct is the seam: apply bridge
 * documents to the buffer without echoing them back, keep the dirty dot honest,
 * and make sure the screen pushes a freshly opened or reverted document exactly
 * once (see [EditorUiState.pushVersion]).
 *
 * A [ViewModel] because open buffers must outlive rotation, exactly as before.
 */
class EditorViewModel(
    private val manager: EditorManager,
    val requestedUri: String,
    val requestedName: String,
) : ViewModel() {

    private val _state = MutableStateFlow(EditorUiState())

    /** The single source of truth the screen renders. */
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    val uri: String get() = requestedUri
    val name: String get() = requestedName

    // ----------------------------------------------------------------- lifecycle

    /** Opens the requested file. Safe to call again with the same file. */
    fun open(uri: String = requestedUri, name: String = requestedName) {
        if (uri.isBlank()) {
            _state.update { it.copy(errorMessage = null, buffer = null) }
            return
        }
        if (_state.value.buffer?.uri == uri && !_state.value.loading) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, errorMessage = null) }
            when (val result = manager.open(uri, name)) {
                is AppResult.Success -> publish(result.data, tabsFrom(manager.openBuffers()), push = true)
                is AppResult.Failure -> _state.update {
                    it.copy(loading = false, errorMessage = result.error.describeWithStep())
                }
                AppResult.Loading -> Unit
            }
        }
    }

    // ------------------------------------------------------------- bridge events

    /** The single entry point for every JS->Kotlin bridge message. */
    fun handleBridgeEvent(event: EditorEvent) {
        when (event) {
            is EditorEvent.Ready -> _state.update { it.copy(readyPulse = it.readyPulse + 1) }

            is EditorEvent.DocumentChanged -> applyWebText(event.text)

            is EditorEvent.SelectionChanged -> applyWebSelection(event.start, event.end)

            is EditorEvent.SaveRequested -> save()

            is EditorEvent.BridgeError -> _state.update {
                it.copy(errorMessage = event.message)
            }
        }
    }

    /**
     * Adopts the document CodeMirror reports as the buffer content.
     *
     * The whole text is authoritative: CodeMirror applies user edits itself, so
     * there is nothing to diff and nothing to correct. [EditorTextDiff]-style
     * range location is gone with the old field, which is precisely why the
     * misplaced-paste bug class disappeared. This never bumps [EditorUiState.pushVersion],
     * so a keystroke is never pushed back into the WebView that produced it.
     */
    private fun applyWebText(text: String) {
        val buffer = _state.value.buffer ?: return
        if (buffer.readOnly) return
        val length = editorTextLimit(buffer)
        if (text.length > length) {
            _state.update {
                it.copy(errorMessage = "Document is larger than the editable limit")
            }
            return
        }
        if (text == buffer.text) return
        val caret = buffer.caret.coerceIn(0, text.length)
        val updated = buffer.copy(
            text = text,
            selectionStart = caret,
            selectionEnd = caret,
            version = buffer.version + 1,
        )
        manager.update(updated)
        publish(updated, tabsFrom(manager.openBuffers()))
    }

    /**
     * Mirrors the WebView's caret/selection for the status line and goto-line.
     *
     * Selection is CodeMirror's to own day-to-day; Kotlin keeps a recent mirror
     * so the Ln/Col banner and synthetic caret moves have a base to work from.
     * The pair is normalised because CodeMirror may report either order.
     */
    private fun applyWebSelection(start: Int, end: Int) {
        val buffer = _state.value.buffer ?: return
        val updated = buffer.select(start, end)
        if (updated === buffer) return
        manager.update(updated)
        publish(updated, tabsFrom(manager.openBuffers()))
    }

    // -------------------------------------------------------------------- save

    fun save() {
        val uri = _state.value.buffer?.uri ?: return
        viewModelScope.launch {
            when (val result = manager.save(uri)) {
                is AppResult.Success -> publish(
                    result.data,
                    tabsFrom(manager.openBuffers()),
                    info = SAVED,
                )
                is AppResult.Failure -> _state.update {
                    // The buffer stays dirty: the edit is not on disk.
                    it.copy(errorMessage = result.error.describeWithStep())
                }
                AppResult.Loading -> Unit
            }
        }
    }

    fun revert() {
        val uri = _state.value.buffer?.uri ?: return
        viewModelScope.launch {
            when (val result = manager.revert(uri)) {
                is AppResult.Success -> publish(
                    result.data,
                    tabsFrom(manager.openBuffers()),
                    push = true,
                )
                is AppResult.Failure -> _state.update {
                    it.copy(errorMessage = result.error.describeWithStep())
                }
                AppResult.Loading -> Unit
            }
        }
    }

    // -------------------------------------------------------------------- tabs

    fun selectTab(uri: String) {
        manager.activate(uri)?.let { publish(it, tabsFrom(manager.openBuffers()), push = true) }
    }

    fun closeTab(uri: String) {
        viewModelScope.launch {
            when (val result = manager.close(uri)) {
                is AppResult.Success -> {
                    val next = manager.activeBuffer()
                    if (next == null) {
                        _state.update {
                            EditorUiState(readyPulse = it.readyPulse, pushVersion = 0)
                        }
                    } else {
                        publish(next, tabsFrom(manager.openBuffers()), push = true)
                    }
                }
                is AppResult.Failure -> _state.update {
                    it.copy(errorMessage = result.error.describeWithStep())
                }
                AppResult.Loading -> Unit
            }
        }
    }

    // ---------------------------------------------------------------- goto line

    fun showGotoLine() = _state.update { it.copy(showGotoLine = true, gotoLineInput = "") }

    fun setGotoLineInput(value: String) = _state.update {
        it.copy(gotoLineInput = value.filter { c -> c.isDigit() })
    }

    fun closeGotoLine() = _state.update { it.copy(showGotoLine = false, gotoLineInput = "") }

    fun goToLine(input: String) {
        val buffer = _state.value.buffer ?: return
        val line = input.toIntOrNull() ?: return
        val offset = EditorLogic.offsetAt(buffer.text, line, 0)
        val updated = buffer.moveCaret(offset)
        manager.update(updated)
        _state.update { current ->
            current.copy(
                buffer = updated,
                showGotoLine = false,
                gotoLineInput = "",
                selectionRequest = current.selectionRequest + 1,
            )
        }
    }

    // ------------------------------------------------------------------ publish

    private fun publish(
        buffer: EditorBuffer?,
        tabs: List<TabItem>,
        info: String? = null,
        push: Boolean = false,
    ) {
        _state.update { current ->
            if (buffer == null) {
                current.copy(
                    loading = false,
                    buffer = null,
                    tabs = tabs,
                    infoMessage = info,
                    errorMessage = null,
                )
            } else {
                current.copy(
                    loading = false,
                    buffer = buffer,
                    tabs = tabs,
                    infoMessage = info,
                    errorMessage = null,
                    pushVersion = current.pushVersion + if (push) 1 else 0,
                )
            }
        }
    }

    private fun tabsFrom(buffers: List<EditorBuffer>): List<TabItem> = buffers.map {
        TabItem(uri = it.uri, name = it.name, dirty = it.isDirty, readOnly = it.readOnly)
    }

    /** The buffer's editable ceiling: the same 2 MB rule that opened it. */
    private fun editorTextLimit(buffer: EditorBuffer): Int =
        EditorManager.LARGE_FILE_LIMIT_BYTES.toInt()

    companion object {
        const val SAVED = "Saved"

        /**
         * Builds the view model for a file.
         *
         * The navigation arguments are passed in rather than read from a
         * repository, so the screen and the buffer agree on which document is
         * open without a second lookup that could return a different file.
         */
        fun factory(uri: String, name: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    EditorViewModel(
                        manager = NexGApp.container().editorManager,
                        requestedUri = uri,
                        requestedName = name,
                    ) as T
            }
    }
}