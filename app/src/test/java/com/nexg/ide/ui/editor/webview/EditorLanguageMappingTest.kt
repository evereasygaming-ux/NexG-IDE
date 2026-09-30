package com.nexg.ide.ui.editor.webview

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.nexg.ide.domain.editor.Languages
import org.junit.Test

/**
 * The language-id surface the WebView accepts.
 *
 * [EditorLanguageMapping] must agree with the JS `LANGUAGE_IDS` list in
 * `editor-assets/src/editor-core.mjs`. Every id [com.nexg.ide.domain.editor.Languages]
 * can produce must resolve as known, and anything else must degrade to plain
 * text — never be forwarded, which would hand the page an unknown parser to
 * reason about.
 */
class EditorLanguageMappingTest {

    @Test
    fun everyLanguageTheDomainProducesResolvesKnown() {
        for (definition in Languages.all) {
            assertWithMessage(definition.displayName)
                .that(EditorLanguageMapping.isKnown(definition.id))
                .isTrue()
            assertThat(EditorLanguageMapping.toJsId(definition.id))
                .isEqualTo(definition.id)
        }
    }

    @Test
    fun gradleSharesTheKotlinParser() {
        // Mirrors Languages.GRADLE reusing the Kotlin vocabulary.
        assertThat(EditorLanguageMapping.toJsId("gradle")).isEqualTo("gradle")
        assertThat(EditorLanguageMapping.defaultJsLanguage).isEqualTo("kotlin")
    }

    @Test
    fun unknownAndHostileIdsDegradeToPlain() {
        assertThat(EditorLanguageMapping.toJsId("brainfuck")).isEqualTo(EditorLanguageMapping.PLAIN)
        assertThat(EditorLanguageMapping.toJsId("")).isEqualTo(EditorLanguageMapping.PLAIN)
        assertThat(EditorLanguageMapping.toJsId("ko tlin")).isEqualTo(EditorLanguageMapping.PLAIN)
        assertThat(EditorLanguageMapping.toJsId("javascript")).isEqualTo(EditorLanguageMapping.PLAIN)
        assertThat(EditorLanguageMapping.isKnown("brainfuck")).isFalse()
    }

    @Test
    fun plainTextIsKnown() {
        assertThat(EditorLanguageMapping.isKnown(EditorLanguageMapping.PLAIN)).isTrue()
        assertThat(EditorLanguageMapping.toJsId(EditorLanguageMapping.PLAIN))
            .isEqualTo(EditorLanguageMapping.PLAIN)
    }

    @Test
    fun knownIdSetIsClosedAndExact() {
        val known = setOf(EditorLanguageMapping.PLAIN, "kotlin", "java", "xml", "gradle", "json", "markdown", "shell")
        for (id in known) assertThat(EditorLanguageMapping.isKnown(id)).isTrue()
        // Nothing fuzzy: case matters, suffixes do not match.
        assertThat(EditorLanguageMapping.isKnown("Kotlin")).isFalse()
        assertThat(EditorLanguageMapping.isKnown("kts")).isFalse()
    }
}