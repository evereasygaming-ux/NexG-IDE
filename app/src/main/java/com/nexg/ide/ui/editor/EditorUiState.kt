package com.nexg.ide.ui.editor

import com.nexg.ide.domain.editor.EditorBuffer
import com.nexg.ide.domain.editor.EditorLogic

/**
 * What the editor screen shows.
 *
 * One immutable snapshot rather than a bag of separate `MutableState` values, so
 * a recomposition always sees a consistent set.
 *
 * The text, selection and undo stack themselves now live in the WebView's
 * CodeMirror editor; this state is the Kotlin side's mirror of them (the buffer
 * is updated from bridge events, CodeMirror keeps its own history). The fields
 * here drive only what Kotlin still owns: which file is open, tab strip, dirty
 * dot, read-only banner, save button, goto-line dialog, and the push counters
 * that tell the screen when to push the authoritative document into the
 * WebView without echoing user keystrokes back.
 */
data class EditorUiState(
    val buffer: EditorBuffer? = null,
    val tabs: List<TabItem> = emptyList(),
    val loading: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    /**
     * Bumped whenever a *new* document is made authoritative — open, revert,
     * tab switch, close — so the screen knows to push the whole text into the
     * WebView. User keystrokes come *from* the WebView and must never be echoed
     * back, so document changes from the bridge do not bump this.
     */
    val pushVersion: Int = 0,
    /**
     * Bumped on every `editorReady` the WebView posts. A freshly recreated
     * WebView needs the current document pushed again even when Kotlin's state
     * is unchanged, and this counter is what makes that happen.
     */
    val readyPulse: Int = 0,
    /**
     * Bumped when Kotlin moves the caret itself (goto line, tab switch) so the
     * screen re-asserts the selection in the WebView.
     */
    val selectionRequest: Int = 0,
    val showGotoLine: Boolean = false,
    val gotoLineInput: String = "",
) {
    val hasDocument: Boolean get() = buffer != null
    val isReadOnly: Boolean get() = buffer?.readOnly == true
    val isDirty: Boolean get() = buffer?.isDirty == true

    /** Whether the WebView is ready to receive pushes. */
    val editorReady: Boolean get() = readyPulse > 0
}

/** The caret/selection half of a buffer, as an offset pair. */
data class Caret(val start: Int, val end: Int) {
    val collapsed: Boolean get() = start == end
    val length: Int get() = (end - start).coerceAtLeast(0)
}

/** The 1-based line and column shown in the status bar. */
fun EditorBuffer.statusPosition(): Pair<Int, Int> {
    val position = EditorLogic.positionAt(text, caret.coerceIn(0, text.length))
    return position.line to (position.column + 1)
}