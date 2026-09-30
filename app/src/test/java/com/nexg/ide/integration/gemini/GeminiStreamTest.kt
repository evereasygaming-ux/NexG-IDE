package com.nexg.ide.integration.gemini

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure SSE/JSON parser tests — no network, no server (PLAN.MD 4.9 gate). */
class GeminiStreamTest {

    @Test
    fun `one data line with one part produces one delta`() {
        val line = """data: {"candidates":[{"content":{"parts":[{"text":"Hello"}],"role":"model"},"index":0}]}"""

        val events = GeminiStream.eventsFor(line)

        assertThat(events).containsExactly(GeminiStreamEvent.Delta("Hello"))
    }

    @Test
    fun `multiple parts merge into one delta`() {
        val line = """data: {"candidates":[{"content":{"parts":[{"text":"He"},{"text":"llo"}],"role":"model"}}]}"""

        val events = GeminiStream.eventsFor(line)

        assertThat(events).containsExactly(GeminiStreamEvent.Delta("Hello"))
    }

    @Test
    fun `finish reason shares the chunk with its text`() {
        val line = """data: {"candidates":[{"content":{"parts":[{"text":"bye"}],"role":"model"},"finishReason":"STOP","index":0}]}"""

        val events = GeminiStream.eventsFor(line)

        assertThat(events).containsExactly(
            GeminiStreamEvent.Delta("bye"),
            GeminiStreamEvent.Finished("STOP"),
        )
    }

    @Test
    fun `finish reason without text is still a finish`() {
        val line = """data: {"candidates":[{"finishReason":"MAX_TOKENS","index":0}]}"""

        val events = GeminiStream.eventsFor(line)

        assertThat(events).containsExactly(GeminiStreamEvent.Finished("MAX_TOKENS"))
    }

    @Test
    fun `api error payload becomes a failure`() {
        val line = """data: {"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}"""

        val events = GeminiStream.eventsFor(line)

        assertThat(events).containsExactly(
            GeminiStreamEvent.Failure(
                code = 400,
                status = "INVALID_ARGUMENT",
                message = "API key not valid. Please pass a valid API key.",
            ),
        )
    }

    @Test
    fun `done marker yields nothing`() {
        assertThat(GeminiStream.eventsFor("data: [DONE]")).isEmpty()
    }

    @Test
    fun `blank and non-data lines yield nothing`() {
        assertThat(GeminiStream.eventsFor("")).isEmpty()
        assertThat(GeminiStream.eventsFor("   ")).isEmpty()
        assertThat(GeminiStream.eventsFor("ping")).isEmpty()
        assertThat(GeminiStream.eventsFor("event: foo")).isEmpty()
    }

    @Test
    fun `malformed json is skipped not fatal`() {
        assertThat(GeminiStream.eventsFor("data: {{{")).isEmpty()
        assertThat(GeminiStream.eventsFor("data: ")).isEmpty()
    }

    @Test
    fun `candidate with no parts and no finish yields nothing`() {
        val line = """data: {"candidates":[{"content":null,"index":0}]}"""
        assertThat(GeminiStream.eventsFor(line)).isEmpty()
    }

    @Test
    fun `candidate index is ignored first is used`() {
        val line = """data: {"candidates":[{"index":1,"content":{"parts":[{"text":"first"}]}}]}"""
        assertThat(GeminiStream.eventsFor(line))
            .containsExactly(GeminiStreamEvent.Delta("first"))
    }
}