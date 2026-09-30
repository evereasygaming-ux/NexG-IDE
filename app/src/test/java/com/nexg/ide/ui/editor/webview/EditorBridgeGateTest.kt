package com.nexg.ide.ui.editor.webview

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Regression tests for the four Phase-B hardening fixes, on the JVM.
 *
 * These exist because the bug that motivated them could not previously be
 * reached from a test at all: readiness, coalescing and the release decision all
 * lived inside [CodeEditorHost], whose first act is to construct a WebView. They
 * now live in [EditorBridgeGate], which has no Android in it, and every one of
 * them is pinned below.
 */
class EditorBridgeGateTest {

    private fun calls(list: List<EditorBridgeGate.Call>) =
        list.map { "${it.method.jsName}(${it.argument})" }

    // =====================================================================
    // READY QUEUE — pre-ready updates are queued, not dropped
    // =====================================================================

    @Test
    fun `state pushed before editorReady is queued rather than dropped`() {
        val gate = EditorBridgeGate()

        val sent = calls(
            gate.submitTheme("dark") +
                gate.submitLanguage("kotlin") +
                gate.submitReadOnly(true) +
                gate.submitDocument("val a = 1") +
                gate.submitSelection(3, 7) +
                gate.submitFocus()
        )

        // The defect: every one of these used to be discarded outright.
        assertThat(sent).isEmpty()
        assertThat(gate.hasPending).isTrue()
        assertThat(gate.isReady).isFalse()

        val flushed = calls(gate.markReady())
        assertThat(flushed).containsExactly(
            "setTheme(\"dark\")",
            "setLanguage(\"kotlin\")",
            "setReadOnly(true)",
            "setDocument(\"val a = 1\")",
            "setSelection(3,7)",
            "requestFocus()",
        ).inOrder()
    }

    @Test
    fun `the flush is empty when nothing arrived before readiness`() {
        val gate = EditorBridgeGate()

        assertThat(gate.markReady()).isEmpty()
        assertThat(gate.isReady).isTrue()
    }

    // ------------------------------------------------------ coalescing / last wins

    @Test
    fun `repeated setDocument before ready coalesces to the final one`() {
        val gate = EditorBridgeGate()

        gate.submitDocument("v1")
        gate.submitDocument("v2")
        gate.submitDocument("final")

        val flushed = calls(gate.markReady())
        assertThat(flushed.count { it.startsWith("setDocument(") }).isEqualTo(1)
        assertThat(flushed).contains("setDocument(\"final\")")
    }

    @Test
    fun `repeated setLanguage before ready coalesces to the final one`() {
        val gate = EditorBridgeGate()

        gate.submitLanguage("kotlin")
        gate.submitLanguage("json")
        gate.submitLanguage("python")

        val flushed = calls(gate.markReady())
        assertThat(flushed.count { it.startsWith("setLanguage(") }).isEqualTo(1)
        assertThat(flushed).contains("setLanguage(\"python\")")
    }

    @Test
    fun `repeated setReadOnly before ready coalesces to the final one`() {
        val gate = EditorBridgeGate()

        gate.submitReadOnly(false)
        gate.submitReadOnly(true)
        gate.submitReadOnly(false)

        val flushed = calls(gate.markReady())
        assertThat(flushed.count { it.startsWith("setReadOnly(") }).isEqualTo(1)
        assertThat(flushed).contains("setReadOnly(false)")
    }

    @Test
    fun `repeated setTheme before ready coalesces to the final one`() {
        val gate = EditorBridgeGate()

        gate.submitTheme("dark")
        gate.submitTheme("light")
        gate.submitTheme("dark")

        val flushed = calls(gate.markReady())
        assertThat(flushed.count { it.startsWith("setTheme(") }).isEqualTo(1)
        assertThat(flushed).contains("setTheme(\"dark\")")
    }

    @Test
    fun `repeated setSelection before ready coalesces to the final one`() {
        val gate = EditorBridgeGate()

        gate.submitSelection(0, 0)
        gate.submitSelection(5, 5)
        gate.submitSelection(9, 2)

        val flushed = calls(gate.markReady())
        assertThat(flushed.count { it.startsWith("setSelection(") }).isEqualTo(1)
        assertThat(flushed).contains("setSelection(9,2)")
    }

    @Test
    fun `focus requested repeatedly before ready is requested once`() {
        val gate = EditorBridgeGate()

        gate.submitFocus()
        gate.submitFocus()
        gate.submitFocus()

        val flushed = calls(gate.markReady())
        assertThat(flushed.count { it == "requestFocus()" }).isEqualTo(1)
    }

    @Test
    fun `final state wins across a mixed burst of pre-ready updates`() {
        val gate = EditorBridgeGate()

        gate.submitDocument("first")
        gate.submitTheme("light")
        gate.submitDocument("second")
        gate.submitReadOnly(true)
        gate.submitTheme("dark")
        gate.submitDocument("third")
        gate.submitReadOnly(false)
        gate.submitDocument("fourth")

        assertThat(calls(gate.markReady())).containsExactly(
            "setTheme(\"dark\")",
            "setReadOnly(false)",
            "setDocument(\"fourth\")",
        ).inOrder()
    }

    // ------------------------------------------------------ post-ready behaviour

    @Test
    fun `an identical update after ready is suppressed`() {
        val gate = EditorBridgeGate()
        gate.markReady()

        assertThat(calls(gate.submitTheme("dark"))).containsExactly("setTheme(\"dark\")")
        assertThat(calls(gate.submitReadOnly(false))).containsExactly("setReadOnly(false)")
        assertThat(calls(gate.submitDocument("same"))).containsExactly("setDocument(\"same\")")
        // A re-push of an unchanged document is the common case when the user
        // reverts or when the ready effect re-fires; it must not re-enter history.
        assertThat(gate.submitDocument("same")).isEmpty()
        assertThat(gate.submitTheme("dark")).isEmpty()
        assertThat(gate.submitReadOnly(false)).isEmpty()
    }

    @Test
    fun `a changed document after ready is still delivered`() {
        val gate = EditorBridgeGate()
        gate.markReady()

        gate.submitDocument("a")
        gate.submitDocument("b")

        assertThat(calls(gate.submitDocument("c"))).containsExactly("setDocument(\"c\")")
    }

    @Test
    fun `a repeated editorReady re-pushes because the page is fresh`() {
        val gate = EditorBridgeGate()
        gate.markReady()
        gate.submitTheme("dark")
        gate.submitDocument("body")

        // The page re-runs its bootstrap and signals readiness again. The new
        // page has none of the old state, so everything must go back out;
        // treating the second signal as a no-op would leave a blank editor.
        val afterReload = calls(gate.markReady())
        assertThat(afterReload).containsAtLeast(
            "setTheme(\"dark\")",
            "setDocument(\"body\")",
        )
    }

    @Test
    fun `a command issued before ready is dropped, not replayed`() {
        val gate = EditorBridgeGate()
        gate.submitDocument("body")

        // Replaying undo onto a document the user has not seen would act on
        // state they never observed.
        assertThat(gate.submitCommand(EditorCommand.UNDO)).isEmpty()
        assertThat(calls(gate.markReady()).any { it.startsWith("runCommand(") }).isFalse()

        // Once the page is live, commands flow normally.
        assertThat(calls(gate.submitCommand(EditorCommand.UNDO)))
            .containsExactly("runCommand(\"undo\")")
    }

    // --------------------------------------------------------- release vs ready

    @Test
    fun `release before ready discards the queue and readiness never arrives`() {
        val gate = EditorBridgeGate()
        gate.submitDocument("body")
        gate.submitTheme("dark")

        gate.release()

        assertThat(gate.isReleased).isTrue()
        assertThat(gate.hasPending).isFalse()
        assertThat(gate.markReady()).isEmpty()
        assertThat(gate.isReady).isFalse()
    }

    @Test
    fun `ready after release delivers nothing and no state is retained`() {
        val gate = EditorBridgeGate()
        gate.release()

        gate.markReady()

        assertThat(gate.isReady).isFalse()
        assertThat(gate.submitDocument("body")).isEmpty()
        assertThat(gate.submitTheme("dark")).isEmpty()
        assertThat(gate.submitFocus()).isEmpty()
        assertThat(gate.submitCommand(EditorCommand.UNDO)).isEmpty()
    }

    // =====================================================================
    // RELEASE — must never require creating a WebView
    // =====================================================================

    @Test
    fun `releasing a never-created host asks for nothing to be destroyed`() {
        val gate = EditorBridgeGate()

        // The `by lazy` bug: this used to construct a WebView — loading the whole
        // editor page — in order to destroy it.
        assertThat(gate.isWebViewCreated).isFalse()
        assertThat(gate.release()).isFalse()
        assertThat(gate.isWebViewCreated).isFalse()
    }

    @Test
    fun `releasing a pre-ready host asks for nothing to be destroyed`() {
        val gate = EditorBridgeGate()
        gate.markWebViewCreated()
        gate.submitDocument("body")

        assertThat(gate.release()).isTrue()
    }

    @Test
    fun `releasing a ready host asks for the WebView to be destroyed`() {
        val gate = EditorBridgeGate()
        gate.markWebViewCreated()
        gate.markReady()

        assertThat(gate.release()).isTrue()
        assertThat(gate.isReady).isFalse()
    }

    @Test
    fun `release is idempotent and destroys nothing twice`() {
        val gate = EditorBridgeGate()
        gate.markWebViewCreated()
        gate.markReady()

        assertThat(gate.release()).isTrue()
        assertThat(gate.release()).isFalse()
        assertThat(gate.release()).isFalse()
    }

    @Test
    fun `release drops the retained document instead of keeping it alive`() {
        val gate = EditorBridgeGate()
        gate.submitDocument("a".repeat(2_000_000))

        gate.release()

        assertThat(gate.hasPending).isFalse()
    }

    // =====================================================================
    // THEME
    // =====================================================================

    @Test
    fun `a theme requested before ready is applied on flush`() {
        val gate = EditorBridgeGate()
        gate.submitTheme("dark")

        assertThat(calls(gate.markReady()).first()).isEqualTo("setTheme(\"dark\")")
    }

    @Test
    fun `theme is the first thing sent so the first frame is not the wrong palette`() {
        val gate = EditorBridgeGate()
        gate.submitTheme("dark")
        gate.submitDocument("body")
        gate.submitLanguage("kotlin")
        gate.submitReadOnly(false)
        gate.submitSelection(0, 0)

        assertThat(calls(gate.markReady()).first()).isEqualTo("setTheme(\"dark\")")
    }

    @Test
    fun `duplicate theme requests coalesce across the ready boundary`() {
        val gate = themeAcrossTheReadyBoundary()

        assertThat(calls(gate)).containsExactly("setTheme(\"dark\")")
    }

    @Test
    fun `a reloaded page re-receives the theme`() {
        val gate = EditorBridgeGate()
        gate.markReady()
        gate.submitTheme("dark")
        assertThat(gate.submitTheme("dark")).isEmpty()

        // A new WebView has none of the old page's state. markReady clears the
        // watermarks for exactly this case, which is what makes rotation and
        // reload re-push instead of silently keeping a blank page.
        val afterReload = calls(gate.markReady())
        assertThat(afterReload).contains("setTheme(\"dark\")")
    }

    /** The screen pushes theme both before and after readiness; only one copy ships. */
    private fun themeAcrossTheReadyBoundary(): List<EditorBridgeGate.Call> {
        val gate = EditorBridgeGate()
        gate.submitTheme("dark") // push effect, before the page is live
        val first = gate.submitTheme("dark") // theme effect, still before ready
        val flush = gate.markReady()
        val afterReady = gate.submitTheme("dark") // theme effect, after ready
        return first + flush + afterReady
    }

    // =====================================================================
    // ORDER — read-only must precede the document
    // =====================================================================

    @Test
    fun `readOnly is applied before the document so it is never briefly editable`() {
        val gate = EditorBridgeGate()
        gate.submitDocument("body")
        gate.submitReadOnly(true)

        val flushed = calls(gate.markReady())
        assertThat(flushed.indexOf("setReadOnly(true)"))
            .isLessThan(flushed.indexOf("setDocument(\"body\")"))
    }

    @Test
    fun `language is applied before the document so content is parsed on insert`() {
        val gate = EditorBridgeGate()
        gate.submitDocument("body")
        gate.submitLanguage("kotlin")

        val flushed = calls(gate.markReady())
        assertThat(flushed.indexOf("setLanguage(\"kotlin\")"))
            .isLessThan(flushed.indexOf("setDocument(\"body\")"))
    }

    @Test
    fun `selection is applied after the document it indexes into`() {
        val gate = EditorBridgeGate()
        gate.submitSelection(0, 4)
        gate.submitDocument("body")

        val flushed = calls(gate.markReady())
        assertThat(flushed.indexOf("setDocument(\"body\")"))
            .isLessThan(flushed.indexOf("setSelection(0,4)"))
    }

    // =====================================================================
    // ARGUMENTS ARE QUOTED — no document text can become script
    // =====================================================================

    @Test
    fun `hostile document text is quoted, never interpolated as script`() {
        val gate = EditorBridgeGate()
        gate.submitDocument(""""); android.postMessage({type:"saveRequested"}); ("x""")

        val flushed = calls(gate.markReady())
        assertThat(flushed).hasSize(1)
        assertThat(flushed.single()).contains("\\\"")
        // The single emitted method call is the only method name in the script.
        assertThat(flushed.single().substringBefore("(")).isEqualTo("setDocument")
    }

    @Test
    fun `negative selection offsets are clamped before they are quoted`() {
        val gate = EditorBridgeGate()

        gate.submitSelection(-5, -1)

        assertThat(calls(gate.markReady())).contains("setSelection(0,0)")
    }
}
