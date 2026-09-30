package com.nexg.ide.ui.editor.webview

/**
 * The JS->Kotlin side of the editor bridge: one message per event.
 *
 * These mirror the fixed message set in `editor-assets/src/editor-core.mjs`
 * (`MESSAGE_TYPES`): `editorReady`, `documentChanged`, `selectionChanged`,
 * `saveRequested`, `editorError`. The two lists are kept in lockstep by tests on
 * both sides that pin the exact names.
 */
sealed interface EditorEvent {
    /** The WebView's CodeMirror editor is constructed and ready for pushes. */
    data object Ready : EditorEvent

    /** The user changed the document; [text] is the whole new content. */
    data class DocumentChanged(val text: String) : EditorEvent

    /** The caret or selection moved; offsets are 0-based, into [DocumentChanged]. */
    data class SelectionChanged(val start: Int, val end: Int) : EditorEvent

    /** The user pressed Save inside the WebView (Mod-S). */
    data object SaveRequested : EditorEvent

    /** The WebView hit a problem it could not recover from. */
    data class BridgeError(val message: String) : EditorEvent
}