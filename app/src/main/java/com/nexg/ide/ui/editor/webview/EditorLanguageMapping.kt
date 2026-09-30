package com.nexg.ide.ui.editor.webview

/**
 * Maps a JVM-side language id to the id CodeMirror understands.
 *
 * Mirrors `LANGUAGE_IDS` in `editor-assets/src/editor-core.mjs`. The JVM side
 * only ever stores ids produced by [com.nexg.ide.domain.editor.Languages], so
 * every real id passes through unchanged; an unrecognised id degrades to plain
 * text instead of being forwarded, which keeps the WebView's parser-cache
 * surface closed.
 */
object EditorLanguageMapping {

    const val PLAIN = "plain"

    private const val KOTLIN = "kotlin"

    private val KNOWN_IDS: Set<String> =
        setOf("kotlin", "java", "xml", "gradle", "json", "markdown", "shell", PLAIN)

    fun toJsId(languageId: String): String = if (languageId in KNOWN_IDS) languageId else PLAIN

    fun isKnown(languageId: String): Boolean = languageId in KNOWN_IDS

    /** The default plain-text id, used until a document is open. */
    fun defaultId(): String = PLAIN

    /** Kept here so a test can assert the JS default parser name shares this. */
    val defaultJsLanguage: String = KOTLIN
}