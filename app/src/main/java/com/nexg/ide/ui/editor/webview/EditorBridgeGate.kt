package com.nexg.ide.ui.editor.webview

/**
 * The Android-free half of [CodeEditorHost]'s lifecycle.
 *
 * Everything here was previously decided inline in a class that constructs a
 * `WebView`, which made it the one part of the editor bridge that could not be
 * tested on the JVM — and that is exactly where the bug that motivated this file
 * lived. See [markReady] and the class comment on `CodeEditorHost`.
 *
 * Two responsibilities, both pure:
 *
 *  1. **Readiness.** The page signals itself with an `editorReady` bridge
 *     message, posted by the page's own script while the page is still loading.
 *     `WebViewClient.onPageFinished` fires *after* that script has run, so it is
 *     strictly too late to be the gate: a document pushed in response to
 *     `editorReady` was being discarded for want of a flag that only got set
 *     afterwards, leaving a blank editor. Readiness is therefore taken from the
 *     message and from nothing else.
 *
 *  2. **Coalescing.** Calls that arrive before readiness are *retained*, not
 *     dropped. Only the latest value of each piece of state is kept, so opening
 *     a large file still costs one pending string rather than a queue of them.
 *
 * This is deliberately not a second editor state machine. It holds no document
 * semantics, no undo, no language rules: it remembers the latest value of each
 * bridge argument and decides *when* and in *what order* to send it. The bridge
 * contract in [EditorCodec] and the command whitelist in [EditorCommand] are
 * unchanged, and every argument is still a validated literal built by
 * [EditorCodec.jsString].
 */
class EditorBridgeGate {

    /** The closed set of page methods this gate may emit. */
    enum class Method(val jsName: String) {
        SET_THEME("setTheme"),
        SET_LANGUAGE("setLanguage"),
        SET_READ_ONLY("setReadOnly"),
        SET_DOCUMENT("setDocument"),
        SET_SELECTION("setSelection"),
        REQUEST_FOCUS("requestFocus"),
        RUN_COMMAND("runCommand"),
    }

    /**
     * One call to the page's frozen `window.__NexGEditor` surface.
     *
     * [argument] is already a JavaScript literal, quoted by [EditorCodec.jsString]
     * where it is a string, so nothing that reaches this class can escape into
     * executable source.
     */
    data class Call(val method: Method, val argument: String)

    var isReady: Boolean = false
        private set

    var isReleased: Boolean = false
        private set

    /** True once a WebView has actually been constructed for this host. */
    var isWebViewCreated: Boolean = false
        private set

    // ------------------------------------------------- retained state (pre-ready)
    private var theme: String? = null
    private var language: String? = null
    private var readOnly: Boolean? = null
    private var document: String? = null
    private var selection: Pair<Int, Int>? = null
    private var focusRequested = false

    // ------------------------------------------------- watermarks (post-ready)
    private var sentTheme: String? = null
    private var sentLanguage: String? = null
    private var sentReadOnly: Boolean? = null
    private var sentDocument: String? = null
    private var sentSelection: Pair<Int, Int>? = null

    /** True when something is waiting for readiness to be signalled. */
    val hasPending: Boolean
        get() = theme != null || language != null || readOnly != null ||
            document != null || selection != null || focusRequested

    // ------------------------------------------------------------------- submits

    fun submitTheme(mode: String): List<Call> {
        if (isReleased) return emptyList()
        theme = mode
        if (!isReady) return emptyList()
        if (sentTheme == mode) return emptyList()
        sentTheme = mode
        return listOf(Call(Method.SET_THEME, EditorCodec.jsString(mode)))
    }

    fun submitLanguage(id: String): List<Call> {
        if (isReleased) return emptyList()
        language = id
        if (!isReady) return emptyList()
        if (sentLanguage == id) return emptyList()
        sentLanguage = id
        return listOf(Call(Method.SET_LANGUAGE, EditorCodec.jsString(id)))
    }

    fun submitReadOnly(flag: Boolean): List<Call> {
        if (isReleased) return emptyList()
        readOnly = flag
        if (!isReady) return emptyList()
        if (sentReadOnly == flag) return emptyList()
        sentReadOnly = flag
        return listOf(Call(Method.SET_READ_ONLY, flag.toString()))
    }

    fun submitDocument(text: String): List<Call> {
        if (isReleased) return emptyList()
        document = text
        if (!isReady) return emptyList()
        if (sentDocument == text) return emptyList()
        sentDocument = text
        return listOf(Call(Method.SET_DOCUMENT, EditorCodec.jsString(text)))
    }

    fun submitSelection(start: Int, end: Int): List<Call> {
        if (isReleased) return emptyList()
        val pair = start.coerceAtLeast(0) to end.coerceAtLeast(0)
        selection = pair
        if (!isReady) return emptyList()
        if (sentSelection == pair) return emptyList()
        sentSelection = pair
        return listOf(Call(Method.SET_SELECTION, "${pair.first},${pair.second}"))
    }

    /** Retained pre-ready, so a file that opened before the page was live is still focused. */
    fun submitFocus(): List<Call> {
        if (isReleased) return emptyList()
        focusRequested = true
        if (!isReady) return emptyList()
        return listOf(Call(Method.REQUEST_FOCUS, ""))
    }

    /**
     * A user command (undo/redo/find).
     *
     * Unlike state, a command is **not** replayed if the page was not ready:
     * undo is a gesture about something the user could see, and firing a retained
     * undo immediately after a fresh document lands would act on state they never
     * saw. Dropping it is both safer and what the user expects from a control
     * that has nothing to undo yet.
     */
    fun submitCommand(command: EditorCommand): List<Call> {
        if (isReleased || !isReady) return emptyList()
        return listOf(Call(Method.RUN_COMMAND, EditorCodec.jsString(command.jsName)))
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * The page has signalled readiness. Returns the full flush.
     *
     * Order is deliberate and fixed, because it is a correctness property, not a
     * preference:
     *  1. theme, so the first painted frame is the right palette;
     *  2. language, so the parser exists before content arrives (no flash of
     *     unstyled text, and no re-parse of an already-inserted document);
     *  3. read-only, so the document is **never** briefly editable;
     *  4. the document;
     *  5. the selection, which indexes into the document and so must follow it;
     *  6. focus, last, so the IME attaches to a settled document.
     *
     * The previous inline order was theme → document → language → read-only →
     * selection → focus, which inserted content into a still-writable,
     * still-unparsed view.
     *
     * Every call re-pushes in full, even if this gate has already seen an
     * `editorReady`. That is deliberate: `editorReady` is posted by the page's
     * own bootstrap, so it arrives again whenever the page is loaded afresh —
     * a `WebView.reload()`, a renderer restart, or the script re-running after
     * the process is restored. A fresh page holds none of the previous page's
     * document, palette or selection, so treating a second signal as "nothing
     * to do" would leave a blank editor after a reload. Re-sending is cheap and
     * idempotent: `setDocument` does not enter undo history.
     */
    fun markReady(): List<Call> {
        if (isReleased) return emptyList()
        isReady = true
        sentTheme = null
        sentLanguage = null
        sentReadOnly = null
        sentDocument = null
        sentSelection = null
        return flush()
    }

    /** Everything still outstanding, in the fixed order. */
    private fun flush(): List<Call> {
        val out = ArrayList<Call>(6)
        theme?.let {
            if (sentTheme != it) {
                sentTheme = it
                out += Call(Method.SET_THEME, EditorCodec.jsString(it))
            }
        }
        language?.let {
            if (sentLanguage != it) {
                sentLanguage = it
                out += Call(Method.SET_LANGUAGE, EditorCodec.jsString(it))
            }
        }
        readOnly?.let {
            if (sentReadOnly != it) {
                sentReadOnly = it
                out += Call(Method.SET_READ_ONLY, it.toString())
            }
        }
        document?.let {
            if (sentDocument != it) {
                sentDocument = it
                out += Call(Method.SET_DOCUMENT, EditorCodec.jsString(it))
            }
        }
        selection?.let {
            if (sentSelection != it) {
                sentSelection = it
                out += Call(Method.SET_SELECTION, "${it.first},${it.second}")
            }
        }
        if (focusRequested) out += Call(Method.REQUEST_FOCUS, "")
        return out
    }

    fun markWebViewCreated() {
        isWebViewCreated = true
    }

    /**
     * Releases the host. Idempotent.
     *
     * Returns true when there is a WebView to destroy, so the caller can tear it
     * down without *constructing* one: a host that was never composed has nothing
     * to release, and creating a WebView in order to destroy it would load the
     * page for nothing.
     */
    fun release(): Boolean {
        if (isReleased) return false
        isReleased = true
        isReady = false
        // Drop retained state: it can hold a whole document, and a released host
        // must not keep one alive.
        theme = null
        language = null
        readOnly = null
        document = null
        selection = null
        focusRequested = false
        return isWebViewCreated
    }
}
