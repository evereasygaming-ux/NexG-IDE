package com.nexg.ide.ui.editor.webview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * The WebView hosting the bundled CodeMirror 6 editor, and both directions of
 * its bridge.
 *
 * Security posture (all of it enforcement, none of it a logo):
 *  - the page is loaded only from `file:///android_asset/editor/index.html` —
 *    the offline bundle checked into the APK at build time; there is no
 *    network, no remote code and no external content;
 *  - `allowFileAccess(false)` / `allowContentAccess(false)` deny the page any
 *    access to other files or content providers;
 *  - `blockNetworkLoads(true)` plus a [WebViewClient] that consumes every
 *    navigation makes the page unable to reach the network or leave itself;
 *  - the page reaches Kotlin through a single [Bridge] interface that only
 *    passes decoded, validated messages ([EditorCodec.decode]) for events;
 *  - Kotlin reaches the page through a fixed method surface
 *    (`window.__NexGEditor`) that takes only validated strings, numbers and the
 *    closed command enum. No JS is ever constructed from untrusted input.
 *
 * Android plumbing (WebView, Looper, evaluateJavascript) lives here so
 * [EditorCodec] and the bridge contract stay testable on the JVM. The device
 * behaviour of the editor itself — caret, selection handles, copy/paste,
 * scrolling — is covered by the manual verification checklist, not by JVM
 * tests.
 */
class CodeEditorHost(
    private val context: Context,
    onBridgeEvent: (EditorEvent) -> Unit,
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Readiness, coalescing and the release decision, all off the Android side of
     * the class so they are testable on the JVM.
     */
    private val gate = EditorBridgeGate()

    /**
     * Readiness is claimed by the page's `editorReady` message and by nothing
     * else, so the flush has to happen here, in the one place that sees it.
     *
     * The flush is evaluated *before* the event is forwarded: forwarding bumps
     * the ViewModel's ready counter, which re-runs the screen's push effect, and
     * that effect must find the pending state already delivered so the gate
     * coalesces it away instead of sending a second copy.
     */
    private val onEvent: (EditorEvent) -> Unit = { event ->
        if (event is EditorEvent.Ready) submit(gate.markReady())
        onBridgeEvent(event)
    }

    /**
     * Null until [view] is first read.
     *
     * A `by lazy` delegate would have been tidier and wrong: it cannot be asked
     * whether it has been initialised, so [release] on a host that was created
     * and immediately discarded would construct a WebView — loading the whole
     * editor page — purely to destroy it.
     */
    private var webViewOrNull: WebView? = null

    /**
     * The widget to embed in the Compose hierarchy.
     *
     * The only place a WebView is created. Readiness is deliberately *not*
     * inferred here: the page posts its own `editorReady` while it is still
     * loading, which is earlier than any [android.webkit.WebViewClient]
     * callback, and calls that arrive before that signal are retained by
     * [EditorBridgeGate] rather than dropped.
     */
    val view: WebView
        get() = webViewOrNull ?: createWebView().also { webViewOrNull = it }

    // ------------------------------------------------------------------ hits JS

    fun setDocument(text: String) = submit(gate.submitDocument(text))

    fun setLanguage(id: String) = submit(gate.submitLanguage(id))

    fun setReadOnly(readOnly: Boolean) = submit(gate.submitReadOnly(readOnly))

    fun setTheme(dark: Boolean) = submit(gate.submitTheme(if (dark) "dark" else "light"))

    fun requestFocus() = submit(gate.submitFocus())

    fun setSelection(start: Int, end: Int) = submit(gate.submitSelection(start, end))

    fun runCommand(command: EditorCommand) = submit(gate.submitCommand(command))

    /**
     * Hands the calls the gate decided are sendable to the page.
     *
     * `method` and `argument` are both fixed: the method comes from
     * [EditorBridgeGate.Method] and the argument is a literal already quoted by
     * [EditorCodec.jsString], so no path through here builds script from
     * untrusted input.
     */
    private fun submit(calls: List<EditorBridgeGate.Call>) {
        if (calls.isEmpty()) return
        val existing = webViewOrNull ?: return
        mainHandler.post {
            if (gate.isReleased) return@post
            for (call in calls) {
                val args = if (call.argument.isEmpty()) "" else ",${call.argument}"
                val script = "window.__NexGEditor.${call.method.jsName}($args)"
                // A call queued before dispose can land on a destroyed WebView.
                runCatching { existing.evaluateJavascript(script, null) }
            }
        }
    }

    // ------------------------------------------------------------------ create

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView = WebView(context).apply {
        settings.apply {
            javaScriptEnabled = true
            // The editor page is a sandbox: no other files, no providers, no
            // network, no storage, no real navigation.
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            blockNetworkLoads = true
            domStorageEnabled = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            mediaPlaybackRequiresUserGesture = true
        }
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(Color.parseColor(BACKGROUND_HEX))
        webViewClient = object : WebViewClient() {
            // Deliberately does NOT set readiness. The page posts `editorReady`
            // from its own bootstrap while it is still loading, so
            // onPageFinished always runs after the first push the editor cares
            // about had already been asked for. Treating it as the gate is what
            // made the first document vanish.

            // Consume every navigation: nothing the page does opens a browser,
            // a new WebView or a different URL.
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true

            @Suppress("OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true

            // No error page, no interstitial: failures are surfaced through the
            // bridge as editor errors, not as rendered HTML.
            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse,
            ) = Unit

            /**
             * A main-frame failure means there is no editor to talk to, so this
             * is the same user-visible condition as the page reporting its own
             * error, and it takes the same path. The failure's own description
             * and status are never forwarded — see [EditorLoadFailure].
             */
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                EditorLoadFailure.report(request?.isForMainFrame == true)?.let(onEvent)
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?,
            ) {
                EditorLoadFailure.report(isMainFrame = true)?.let(onEvent)
            }

            /**
             * The renderer is gone, so this WebView is finished with whether or
             * not anyone goes on to dispose it. Release the gate to stop pushes
             * into a dead view, and return true so the app is not killed for it
             * — the user gets a deterministic error, not a crash.
             */
            override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                release()
                onEvent(EditorLoadFailure.reportRendererGone())
                return true
            }
        }
        addJavascriptInterface(Bridge(), "android")
        gate.markWebViewCreated()
        loadUrl(EDITOR_INDEX_URL)
    }

    /**
     * The single door from the page into Kotlin. Runs on a WebView thread, so
     * the decoded event is forwarded onto the main looper before anything that
     * touches the ViewModel.
     */
    private inner class Bridge {
        @JavascriptInterface
        fun postMessage(message: String?) {
            mainHandler.post {
                EditorCodec.decode(message)?.let(onEvent)
            }
        }
    }

    /**
     * Detaches and destroys the WebView; safe to call more than once.
     *
     * A host that was never composed has no WebView and needs none, so
     * [EditorBridgeGate.release] reports whether there is anything to destroy
     * rather than this method reaching through `by lazy` and forcing creation.
     */
    fun release() {
        if (!gate.release()) return
        val existing = webViewOrNull ?: return
        mainHandler.post {
            (existing.parent as? ViewGroup)?.removeView(existing)
            runCatching { existing.destroy() }
        }
    }

    private companion object {
        const val EDITOR_INDEX_URL = "file:///android_asset/editor/index.html"

        /** NexGBlack; kept in step with the editor's own dark surface so the
         * first frame of the page is not a white flash. */
        const val BACKGROUND_HEX = "#05060A"
    }
}