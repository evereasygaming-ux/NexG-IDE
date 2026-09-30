package com.nexg.ide.ui.editor.webview

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Regression tests for surface C: a WebView that cannot load the editor used to
 * fail silently, leaving an empty box indistinguishable from an empty file.
 */
class EditorLoadFailureTest {

    @Test
    fun `a main-frame load failure becomes a user-visible editor error`() {
        val event = EditorLoadFailure.report(isMainFrame = true)

        assertThat(event).isNotNull()
        assertThat(event).isInstanceOf(EditorEvent.BridgeError::class.java)
        assertThat((event as EditorEvent.BridgeError).message)
            .isEqualTo(EditorLoadFailure.PAGE_LOAD_FAILED)
    }

    @Test
    fun `a subresource failure stays quiet so a missing font is not a broken editor`() {
        // A font or chunk that fails to load must not present as a dead editor.
        assertThat(EditorLoadFailure.report(isMainFrame = false)).isNull()
    }

    @Test
    fun `a dead renderer is reported and is distinct from a load failure`() {
        val event = EditorLoadFailure.reportRendererGone()

        assertThat((event as EditorEvent.BridgeError).message)
            .isEqualTo(EditorLoadFailure.RENDERER_GONE)
        assertThat(event.message).isNotEqualTo(EditorLoadFailure.PAGE_LOAD_FAILED)
    }

    /**
     * The security argument, as a test.
     *
     * `onReceivedError` hands the app a `description` and a `failingUrl` that are
     * whatever the failing load reported. Rendering either into a Text would put
     * a remote-controlled string into the UI. The API is shaped so that is not
     * possible: every parameter is a boolean, so there is no channel to forward
     * one. If someone later adds a `description: String` parameter, this fails.
     */
    @Test
    fun `no failure-reporting entry point accepts untrusted text`() {
        val offenders = EditorLoadFailure::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) }
            .filter { m -> m.parameterTypes.any { p -> p != Boolean::class.javaPrimitiveType } }
            .map { it.name }

        assertThat(offenders).isEmpty()
    }

    @Test
    fun `the messages are constants with nothing interpolated`() {
        // No format specifiers means no caller can smuggle content in via a
        // formatted message built from a load failure.
        assertThat(EditorLoadFailure.PAGE_LOAD_FAILED).doesNotContain("%")
        assertThat(EditorLoadFailure.RENDERER_GONE).doesNotContain("%")
    }
}
