package com.nexg.ide.ui.editor.webview

/**
 * Which WebView load failures are worth showing the user, and what they are
 * allowed to say.
 *
 * A load failure used to be silent: nothing listened to `onReceivedError`, so a
 * WebView that could not load the editor left an empty box with no explanation
 * and no way to tell that from an empty file. Routing it through
 * [EditorEvent.BridgeError] — the event the editor state layer already has a
 * deterministic, user-visible path for — fixes that without adding a second
 * error channel.
 *
 * The signature is the security argument. Every parameter is a **boolean**, so
 * there is no channel through which a WebView-supplied URL, error code, or
 * human-readable description could be carried into the UI. `onReceivedError`'s
 * `description` is attacker-influenced on any page a WebView can be made to
 * load, and rendering it would put a remote string into a text field. The
 * messages below are constants, chosen here, with no interpolation.
 */
object EditorLoadFailure {

    /** The editor page itself failed to load. */
    const val PAGE_LOAD_FAILED = "Editor could not be loaded"

    /** The WebView's renderer process died; the view cannot be used again. */
    const val RENDERER_GONE = "Editor stopped responding"

    /**
     * @param isMainFrame true when the failure was the editor document itself.
     *   Subresource failures (a font, a missing chunk) are not surfaced: the
     *   page may still be perfectly usable, and a page that cannot load its
     *   fonts must not present as a broken editor.
     * @return the error to surface, or null to stay quiet.
     */
    fun report(isMainFrame: Boolean): EditorEvent.BridgeError? =
        if (isMainFrame) EditorEvent.BridgeError(PAGE_LOAD_FAILED) else null

    /** The renderer died, so the host is dead; unlike a load error this is terminal. */
    fun reportRendererGone(): EditorEvent.BridgeError = EditorEvent.BridgeError(RENDERER_GONE)
}
