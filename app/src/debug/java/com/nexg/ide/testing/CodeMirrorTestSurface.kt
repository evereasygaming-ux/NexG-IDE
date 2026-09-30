package com.nexg.ide.testing

import com.nexg.ide.ui.editor.webview.EditorCommand
import com.nexg.ide.ui.editor.webview.EditorEvent
import com.nexg.ide.ui.editor.webview.EditorLanguageMapping

/**
 * A read/observe view of the live editor, as seen by the debug harness.
 *
 * This interface is the *only* way the harness touches the editor, and every
 * method maps onto something the production app already does through the
 * existing CodeMirror bridge. In particular there is no "evaluate this script"
 * method, because that would turn the harness into the unrestricted JavaScript
 * executor the spec forbids, and would also bypass the production push
 * discipline the editor's correctness depends on.
 */
interface CodeMirrorTestSurface {
    /** True once the WebView has reported `editorReady`. */
    fun isEditorReady(): Boolean

    /** Current buffer text as Kotlin holds it. */
    fun documentText(): String?

    /** Current selection as Kotlin last observed it. */
    fun selection(): Pair<Int, Int>?

    /** Whether the open buffer is dirty. */
    fun isDirty(): Boolean

    /** Whether the open buffer is read-only (the >2 MB guard). */
    fun isReadOnly(): Boolean

    /** The active file identity: its URI and display name. */
    fun activeFile(): Pair<String, String>?

    /** Open tab identities, in tab order. */
    fun openTabs(): List<Pair<String, String>>

    /** The language id CodeMirror is currently set to. */
    fun currentJsLanguage(): String

    /** Bridge errors the WebView has reported, oldest first. */
    fun bridgeErrors(): List<String>
}

/**
 * Test-side operations that *act* on the editor.
 *
 * Each one is the production call, so a harness pass means the real path worked:
 * [requestFocus] is the same call the screen makes, and [runCommand] is the same
 * whitelisted [EditorCommand] enum the toolbar uses. Nothing here can express a
 * new capability, only re-use an existing one.
 */
interface CodeMirrorTestActions {
    fun requestFocus()
    fun setSelection(start: Int, end: Int)
    fun runCommand(command: EditorCommand)
    fun setReadOnly(readOnly: Boolean)
    fun save()
    fun closeFile(uri: String)
    fun openFile(uri: String, name: String)
}

/**
 * Pure decision logic for the editor checks.
 *
 * Kept free of Android and of the ViewModel so the interesting part — "given
 * what the bridge reported, is this a pass, a fail, or simply not observable
 * yet?" — is unit-testable on the JVM. [evaluate] never invents a pass: an
 * unobservable precondition is UNAVAILABLE, and only a positively wrong
 * observation is a FAIL.
 */
object EditorChecks {

    /** One precondition/observation pair to judge. */
    data class Check(
        val specId: String,
        val name: String,
        val outcome: TestOutcome,
        val detail: String,
        val evidence: Map<String, String> = emptyMap(),
    )

    private fun ok(id: String, spec: TestSequences.Spec, detail: String, vararg evidence: Pair<String, String>) =
        Check(id, spec.name, TestOutcome.PASS, detail, evidence.toMap())

    private fun bad(id: String, spec: TestSequences.Spec, detail: String) =
        Check(id, spec.name, TestOutcome.FAIL, detail)

    /** ED-01/ED-02: the page loaded and CodeMirror announced itself. */
    fun checkEditorReady(id: String, spec: TestSequences.Spec, ready: Boolean): Check =
        if (ready) ok(id, spec, "CodeMirror reported editorReady")
        else Check(id, spec.name, TestOutcome.UNAVAILABLE, "editorReady has not been received")

    /** ED-03: the document Kotlin holds equals what we asked the harness to open. */
    fun checkInitialDocument(id: String, spec: TestSequences.Spec, actual: String?, expected: String): Check = when {
        actual == null -> Check(id, spec.name, TestOutcome.UNAVAILABLE, "no document is open")
        actual == expected -> ok(id, spec, "document matches", "chars" to actual.length.toString())
        else -> bad(id, spec, "document differs: expected ${expected.length} chars, got ${actual.length}")
    }

    /** ED-05/ED-08: an edit must actually change the text. */
    fun checkEditChangedDocument(id: String, spec: TestSequences.Spec, before: String?, after: String?): Check = when {
        before == null || after == null -> Check(id, spec.name, TestOutcome.UNAVAILABLE, "document not observable")
        after != before -> ok(id, spec, "document changed", "delta" to (after.length - before.length).toString())
        else -> bad(id, spec, "document did not change after an edit")
    }

    /** ED-07: a setSelection must round-trip to the same offsets. */
    fun checkSelectionRoundTrip(id: String, spec: TestSequences.Spec, requested: Pair<Int, Int>, observed: Pair<Int, Int>?): Check = when {
        observed == null -> Check(id, spec.name, TestOutcome.UNAVAILABLE, "no selection event observed")
        observed == requested -> ok(id, spec, "selection round-tripped", "start" to observed.first.toString(), "end" to observed.second.toString())
        else -> bad(id, spec, "selection mismatch: asked $requested, got $observed")
    }

    /** ED-09/ED-10: undo returns to the prior text, redo re-applies it. */
    fun checkUndoRedo(id: String, spec: TestSequences.Spec, before: String, afterUndo: String?, afterRedo: String?): Check = when {
        afterUndo == null || afterRedo == null -> Check(id, spec.name, TestOutcome.UNAVAILABLE, "no text observed around undo/redo")
        afterUndo == before && afterRedo == afterUndo -> bad(id, spec, "undo and redo produced no observable change")
        afterUndo == before -> ok(id, spec, "undo restored prior text", "redo" to (afterRedo != before).toString())
        else -> bad(id, spec, "undo did not restore the prior text")
    }

    /** ED-15: dirty rises after an edit and clears after a save. */
    fun checkDirtyCycle(id: String, spec: TestSequences.Spec, dirtyAfterEdit: Boolean, dirtyAfterSave: Boolean): Check = when {
        !dirtyAfterEdit -> bad(id, spec, "buffer was not dirty after an edit")
        dirtyAfterSave -> bad(id, spec, "buffer was still dirty after save")
        else -> ok(id, spec, "dirty rose on edit and cleared on save")
    }

    /** ED-16: a read-only buffer must refuse edits. */
    fun checkReadOnlyEnforced(id: String, spec: TestSequences.Spec, readOnly: Boolean, textUnchanged: Boolean): Check = when {
        !readOnly -> Check(id, spec.name, TestOutcome.UNAVAILABLE, "buffer is not read-only; open an oversized file")
        textUnchanged -> ok(id, spec, "read-only buffer refused the edit")
        else -> bad(id, spec, "read-only buffer accepted an edit")
    }

    /** ED-18: switching tabs must not lose which file is which. */
    fun checkTabIdentity(id: String, spec: TestSequences.Spec, before: Pair<String, String>?, after: Pair<String, String>?): Check = when {
        before == null || after == null -> Check(id, spec.name, TestOutcome.UNAVAILABLE, "no tab identity observable")
        after.first == before.first -> ok(id, spec, "tab identity preserved", "uri" to after.first)
        else -> bad(id, spec, "tab identity changed: $before -> $after")
    }

    /**
     * ED-12/A4-07: the bridge must never carry a credential.
     *
     * Structural check rather than a behavioural one: the only free-form text the
     * bridge accepts is a test document, so a key can only appear if someone
     * pasted one into a document. This is asserted on the command schema, which
     * is why the schema lives in one place.
     */
    fun checkNoCredentialInBridge(id: String, spec: TestSequences.Spec, schema: Map<TestCommand, List<Arg>>): Check {
        // Structural check: a command that accepts a *free-form* TextArg could
        // carry a key a human pasted into a payload. A RelativeName is screened
        // (no paths, no URLs, no shell), so it cannot be used that way — which is
        // why the two are different types rather than the same one.
        val leaky = schema.filter { (command, args) ->
            command != TestCommand.SET_TEST_DOCUMENT && args.any { it is Arg.TextArg }
        }
        return if (leaky.isEmpty()) {
            ok(id, spec, "only the test-document command accepts free-form text")
        } else {
            bad(id, spec, "free-form text reachable through: ${leaky.keys.joinToString()}")
        }
    }

    /** The language the harness would set for a given domain id. */
    fun jsLanguageFor(languageId: String): String = EditorLanguageMapping.toJsId(languageId)

    /** Latest bridge error, if the WebView reported one. */
    fun lastBridgeError(surface: CodeMirrorTestSurface): String? = surface.bridgeErrors().lastOrNull()

    /** Ignores an event so the sequence can assert on it without a live loop. */
    fun isReady(event: EditorEvent): Boolean = event is EditorEvent.Ready
}
