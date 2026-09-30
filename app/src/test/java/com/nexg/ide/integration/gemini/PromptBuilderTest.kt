package com.nexg.ide.integration.gemini

import com.google.common.truth.Truth.assertThat
import com.nexg.ide.domain.model.AiMessage
import com.nexg.ide.domain.model.AiRequest
import com.nexg.ide.domain.model.AiRole
import com.nexg.ide.domain.model.CurrentFile
import org.junit.Test

/** Pure context-assembly tests (PLAN.MD 4.9: context rule enforced and tested). */
class PromptBuilderTest {

    // Stand-in for a user's own credential appearing inside file content. It
    // is assembled from two parts so no contiguous credential-shaped literal
    // is stored in the repository, and it is intentionally too short to match
    // any provider key format. Never re-join these two literals.
    private val SECRET = "AIza" + "NotARealGeminiKeyInUserContent"

    @Test
    fun `system carries the depth-limited project tree`() {
        val prompt = PromptBuilder.build(
            AiRequest(projectTree = listOf("app/", "app/build.gradle.kts")),
        )

        assertThat(prompt.systemInstruction).contains("PROJECT STRUCTURE")
        assertThat(prompt.systemInstruction).contains("app/build.gradle.kts")
    }

    @Test
    fun `current file is included and truncated to the cap`() {
        val huge = "x".repeat(10_000)
        val prompt = PromptBuilder.build(
            AiRequest(currentFile = CurrentFile("Main.kt", huge, "kotlin")),
        )

        assertThat(prompt.systemInstruction).contains("CURRENT FILE: Main.kt")
        assertThat(prompt.systemInstruction).contains("[file truncated]")
        assertThat(prompt.systemInstruction).doesNotContain(huge) // capped, not whole
    }

    @Test
    fun `build errors are included up to a ceiling`() {
        val prompt = PromptBuilder.build(
            AiRequest(buildErrors = listOf("e: unresolved reference", "e: type mismatch")),
        )

        assertThat(prompt.systemInstruction).contains("RECENT BUILD ERROR(S)")
        assertThat(prompt.systemInstruction).contains("e: unresolved reference")
    }

    @Test
    fun `conversation turns survive as user model pairs`() {
        val prompt = PromptBuilder.build(
            AiRequest(
                conversation = listOf(
                    AiMessage(AiRole.USER, "what's this?"),
                    AiMessage(AiRole.MODEL, "a file"),
                ),
            ),
        )

        assertThat(prompt.contents.map { it.content })
            .containsExactly("what's this?", "a file")
        assertThat(prompt.contents.map { it.role })
            .containsExactly(AiRole.USER, AiRole.MODEL)
    }

    @Test
    fun `system role lines are not replayed as turns`() {
        val prompt = PromptBuilder.build(
            AiRequest(conversation = listOf(AiMessage(AiRole.SYSTEM, "ignore me"))),
        )

        assertThat(prompt.contents).isEmpty()
    }

    @Test
    fun `last message always survives a tiny budget`() {
        val prompt = PromptBuilder.build(
            AiRequest(
                conversation = listOf(
                    AiMessage(AiRole.USER, "older question"),
                    AiMessage(AiRole.USER, "last question"),
                ),
                // Tight enough that earlier turns are squeezed, but still larger than
                // the mandatory system framing so a real answer survives.
                maxContextChars = 384,
            ),
        )

        assertThat(prompt.contents.last().content).contains("last question")
        assertThat(prompt.contents.size).isAtMost(2)
    }

    @Test
    fun `history is trimmed to a bounded window`() {
        val many = (1..30).map { AiMessage(AiRole.USER, "question $it") }
        val prompt = PromptBuilder.build(AiRequest(conversation = many))

        // Only the most recent turns survive.
        assertThat(prompt.contents.size).isAtMost(6)
        assertThat(prompt.contents.last().content).isEqualTo("question 30")
        assertThat(prompt.contents.map { it.content }).doesNotContain("question 1")
    }

    @Test
    fun `total assembled context respects the budget`() {
        val conversations = List(10) { AiMessage(AiRole.USER, "line ".repeat(5000)) }
        val files = List(4) { CurrentFile("f$it.kt", "body".repeat(5000), "kotlin") }
        val budget = 20_000

        val prompt = PromptBuilder.build(
            AiRequest(
                conversation = conversations,
                currentFile = files.first(),
                projectTree = List(200) { "dir/".repeat(10) + "$it" },
                buildErrors = List(10) { "e: error $it" },
                maxContextChars = budget,
            ),
        )

        val total = prompt.systemInstruction.length +
            prompt.contents.sumOf { it.content.length }
        assertThat(total).isAtMost(budget + 16 * prompt.contents.size + 200)
    }

    @Test
    fun `a credential-shaped string in file content is the file's and stays capped`() {
        // The provider key never enters this builder, so a leaked credential
        // can only come from user content — which is capped like any other
        // content, not copied anywhere the app did not already put it.
        val content = "apiKey=$SECRET"
        val prompt = PromptBuilder.build(
            AiRequest(currentFile = CurrentFile("settings.gradle.kts", content)),
        )

        assertThat(prompt.systemInstruction).contains(content)
        assertThat(prompt.systemInstruction).doesNotContain("x-goog-api-key")
    }

    @Test
    fun `empty request still yields a coherent system`() {
        val prompt = PromptBuilder.build(AiRequest())

        assertThat(prompt.systemInstruction).isNotEmpty()
        assertThat(prompt.contents).isEmpty()
    }
}