package com.nexg.ide.ui.editor.webview

/**
 * The closed set of editor commands the Kotlin side may hand to the WebView.
 *
 * This mirrors `OK_COMMANDS` in `editor-assets/src/editor-core.mjs`. There is no
 * pass-through: a command that is not in this enum is not callable, so the page
 * can never be asked to run arbitrary JS under our bridge. Undo and redo are
 * owned by CodeMirror's history (not the JVM buffer) and find opens CodeMirror's
 * own search panel.
 */
enum class EditorCommand(val jsName: String) {
    UNDO("undo"),
    REDO("redo"),
    FIND("find"),
    ;

    companion object {
        val JS_NAMES: Set<String> = entries.map { it.jsName }.toSet()

        fun fromJsName(name: String?): EditorCommand? =
            entries.firstOrNull { it.jsName == name }
    }
}