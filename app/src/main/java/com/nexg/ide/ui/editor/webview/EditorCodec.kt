package com.nexg.ide.ui.editor.webview

import org.json.JSONException
import org.json.JSONObject

/**
 * Strict, whitelisted parsing of the messages the WebView posts to Kotlin.
 *
 * The bridge is a trust boundary, so this is intentionally hostile: a message
 * with an unknown type, a missing payload field, a non-numeric offset or a text
 * longer than the editable limit is *rejected* (returns [null]), never partially
 * accepted. Kotlin never executes JavaScript from inside these messages; the
 * only arguments the page can reach are the fixed set of [EditorCommand]s and
 * the string/id values validated here.
 *
 * Split from [CodeEditorHost] so the whole contract is testable on the JVM
 * without a WebView. The JS side builds these messages in
 * `editor-core.mjs`; the type names live in shared test assertions.
 */
object EditorCodec {

    /** Mirrors `MAX_DOCUMENT_CHARS` budget on the Kotlin side; the editable
     * limit is [com.nexg.ide.application.EditorManager.LARGE_FILE_LIMIT_BYTES]
     * and a healthy page never reports anything near it. */
    const val MAX_OFFSET = 64 * 1024 * 1024

    /**
     * Decodes one posted message, or [null] when it is not a well-formed
     * message of a known type.
     */
    fun decode(raw: String?): EditorEvent? {
        if (raw == null) return null
        val json = try {
            JSONObject(raw)
        } catch (_: JSONException) {
            return null
        }
        val type = json.optString("type")
        val payload = runCatching { json.optJSONObject("payload") }.getOrNull()
            ?: JSONObject()
        return when (type) {
            "editorReady" -> EditorEvent.Ready
            "documentChanged" -> decodeDocumentChanged(payload) ?: return null
            "selectionChanged" -> decodeSelectionChanged(payload) ?: return null
            "saveRequested" -> EditorEvent.SaveRequested
            "editorError" -> EditorEvent.BridgeError(
                string(payload, "message") ?: "Editor error",
            )
            // Unknown types are rejected, not treated as new events.
            else -> null
        }
    }

    private fun decodeDocumentChanged(payload: JSONObject): EditorEvent.DocumentChanged? {
        // `optString` would coerce a JSON number or boolean into document text, so
        // the type is checked rather than assumed.
        val text = string(payload, "text") ?: return null
        // A document beyond the editable limit is refused rather than accepted:
        // shipping it to the JVM buffer would only burn memory, since the editor
        // already refuses to edit a file that big.
        if (text.length > MAX_DOCUMENT_CHARS) return null
        return EditorEvent.DocumentChanged(text)
    }

    /** The raw JSON value at [key] when it is a string, else [null]. */
    private fun string(payload: JSONObject, key: String): String? =
        payload.opt(key) as? String

    private fun decodeSelectionChanged(payload: JSONObject): EditorEvent.SelectionChanged? {
        val start = offset(payload, "start") ?: return null
        val end = offset(payload, "end") ?: return null
        return EditorEvent.SelectionChanged(start, end)
    }

    /**
     * A non-negative integral offset, or [null] when absent or malformed.
     *
     * Strictly typed: `optDouble` would happily coerce the JSON *string* `"3"`
     * into an offset, which would mean a message of the wrong shape was accepted
     * because it happened to look like a number. Offsets are integers, so a
     * fractional one is refused too.
     */
    private fun offset(payload: JSONObject, key: String): Int? {
        if (!payload.has(key)) return null
        val value = payload.opt(key)
        if (value !is Number) return null
        val number = value.toDouble()
        if (!number.isFinite()) return null
        if (number < 0.0 || number > MAX_OFFSET) return null
        if (number != Math.floor(number)) return null
        return number.toInt()
    }

    /**
     * A JS-safe double-quoted string literal for embedding in a call.
     *
     * `JSONObject.quote` escapes quotes, backslashes and control characters, so
     * a document can never break out of the string argument. `<` is *not*
     * escaped, and does not need to be: the call is handed to
     * `WebView.evaluateJavascript`, which parses JavaScript, not HTML, so
     * `</script>` in a document is an ordinary string.
     */
    fun jsString(value: String): String = JSONObject.quote(value)

    /** Maximum length of a document Kotlin accepts from the WebView. */
    const val MAX_DOCUMENT_CHARS = 2 * 1024 * 1024
}