package com.nexg.ide.ui.editor.webview

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The bridge contract, pinned on the Kotlin side.
 *
 * These names and shapes must stay in lockstep with `editor-assets/src/editor-core.mjs`
 * (`MESSAGE_TYPES`, `OK_COMMANDS`, `LANGUAGE_IDS`). The JS side pins the same
 * contract in its own node tests; this class is the mirror. Drift in either
 * direction fails here first.
 */
class EditorCodecTest {

    // --------------------------------------------------------------- readiness

    @Test
    fun decodesReady() {
        val event = decode("""{"type":"editorReady","payload":{}}""")
        assertThat(event).isEqualTo(EditorEvent.Ready)
    }

    // ------------------------------------------------------------ document

    @Test
    fun decodesDocumentChanged() {
        val event = decode("""{"type":"documentChanged","payload":{"text":"val x = 1"}}""")
        assertThat(event)
            .isEqualTo(EditorEvent.DocumentChanged("val x = 1"))
    }

    @Test
    fun documentChangedWithoutTextIsRejected() {
        assertThat(decode("""{"type":"documentChanged","payload":{}}""")).isNull()
    }

    @Test
    fun documentTextMustBeAString() {
        // A number or boolean in `text` must not be coerced into document text.
        assertThat(decode("""{"type":"documentChanged","payload":{"text":42}}""")).isNull()
        assertThat(decode("""{"type":"documentChanged","payload":{"text":null}}""")).isNull()
        assertThat(decode("""{"type":"documentChanged","payload":{"text":true}}""")).isNull()
        assertThat(decode("""{"type":"documentChanged","payload":{"text":["a"]}}""")).isNull()
    }

    @Test
    fun documentChangedBeyondLimitIsRejected() {
        val huge = "x".repeat(EditorCodec.MAX_DOCUMENT_CHARS + 1)
        assertThat(decode("""{"type":"documentChanged","payload":{"text":"$huge"}}""")).isNull()
    }

    @Test
    fun documentChangedAtLimitIsAccepted() {
        val huge = "x".repeat(EditorCodec.MAX_DOCUMENT_CHARS)
        assertThat(decode("""{"type":"documentChanged","payload":{"text":"$huge"}}"""))
            .isEqualTo(EditorEvent.DocumentChanged(huge))
    }

    // ------------------------------------------------------------- selection

    @Test
    fun decodesSelectionChanged() {
        val event = decode("""{"type":"selectionChanged","payload":{"start":3,"end":7}}""")
        assertThat(event).isEqualTo(EditorEvent.SelectionChanged(3, 7))
    }

    @Test
    fun selectionOffsetsMustBeNumbers() {
        assertThat(decode("""{"type":"selectionChanged","payload":{"start":"3","end":7}}""")).isNull()
        assertThat(decode("""{"type":"selectionChanged","payload":{"start":null,"end":7}}""")).isNull()
        assertThat(decode("""{"type":"selectionChanged","payload":{"start":3}}""")).isNull()
        assertThat(decode("""{"type":"selectionChanged","payload":{"start":3,"end":7.5}}""")).isNull()
    }

    @Test
    fun selectionOffsetsMustBeNonNegativeAndBounded() {
        assertThat(decode("""{"type":"selectionChanged","payload":{"start":-1,"end":0}}""")).isNull()
        assertThat(decode("""{"type":"selectionChanged","payload":{"start":999999999,"end":0}}""")).isNull()
    }

    // ------------------------------------------------------ save / editorError

    @Test
    fun decodesSaveRequested() {
        assertThat(decode("""{"type":"saveRequested","payload":{}}"""))
            .isEqualTo(EditorEvent.SaveRequested)
    }

    @Test
    fun decodesEditorError() {
        assertThat(decode("""{"type":"editorError","payload":{"message":"boom"}}"""))
            .isEqualTo(EditorEvent.BridgeError("boom"))
    }

    @Test
    fun editorErrorWithoutUsableMessageFallsBack() {
        assertThat(decode("""{"type":"editorError","payload":{}}"""))
            .isEqualTo(EditorEvent.BridgeError("Editor error"))
        // A non-string message is not coerced into an error text.
        assertThat(decode("""{"type":"editorError","payload":{"message":7}}"""))
            .isEqualTo(EditorEvent.BridgeError("Editor error"))
    }

    @Test
    fun rejectsTheOldBareErrorTypeName() {
        // The contract is `editorError`; the shorter name is not an alias, so a
        // page cannot smuggle a different type past the whitelist.
        assertThat(decode("""{"type":"error","payload":{"message":"boom"}}""")).isNull()
    }

    // --------------------------------------------------------------- hostile

    @Test
    fun rejectsNonJson() {
        assertThat(decode("")).isNull()
        assertThat(decode("not json")).isNull()
        assertThat(decode(null)).isNull()
    }

    @Test
    fun rejectsNonObject() {
        assertThat(decode("[1,2]")).isNull()
        assertThat(decode(""" "editorReady" """)).isNull()
        assertThat(decode("null")).isNull()
        assertThat(decode("42")).isNull()
    }

    @Test
    fun rejectsUnknownTypes() {
        assertThat(decode("""{"type":"eval","payload":{}}""")).isNull()
        assertThat(decode("""{"type":"editorReady.exploit","payload":{}}""")).isNull()
        assertThat(decode("""{"type":"saveRequested;alert(1)","payload":{}}""")).isNull()
    }

    // ---------------------------------------------------------------- jsString

    @Test
    fun jsStringQuotesSafely() {
        assertThat(EditorCodec.jsString("abc")).isEqualTo("\"abc\"")
        assertThat(EditorCodec.jsString("a\"b\\c\n")).isEqualTo("\"a\\\"b\\\\c\\n\"")
        // A document cannot break out of the string argument. `<` needs no
        // escaping because the call is evaluated as JavaScript, not embedded in
        // HTML; org.json escapes the slash and leaves the bracket alone.
        assertThat(EditorCodec.jsString("</script>")).isEqualTo("\"<\\/script>\"")
        val quoted = EditorCodec.jsString("line1\nline2")
        assertThat(quoted).doesNotContain("\n")
    }

    // --------------------------------------------------------------- commands

    @Test
    fun commandWhitelistMatchesJsContract() {
        assertThat(EditorCommand.JS_NAMES).containsExactly("undo", "redo", "find")
        assertThat(EditorCommand.fromJsName("undo")).isEqualTo(EditorCommand.UNDO)
        assertThat(EditorCommand.fromJsName("redo")).isEqualTo(EditorCommand.REDO)
        assertThat(EditorCommand.FIND.jsName).isEqualTo("find")
        assertThat(EditorCommand.fromJsName("rm -rf /")).isNull()
        assertThat(EditorCommand.fromJsName("selectAll")).isNull()
    }

    private fun decode(raw: String?): EditorEvent? = EditorCodec.decode(raw)
}