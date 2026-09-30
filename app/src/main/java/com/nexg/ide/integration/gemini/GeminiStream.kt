package com.nexg.ide.integration.gemini

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Streaming answer of one *streamed* Gemini response, parsed from a single
 * `data:` SSE line.
 */
sealed interface GeminiStreamEvent {
    data class Delta(val text: String) : GeminiStreamEvent

    data class Finished(val finishReason: String?) : GeminiStreamEvent

    data class Failure(val code: Int, val status: String, val message: String) : GeminiStreamEvent
}

/**
 * Server-sent-event + JSON parsing for the Gemini `streamGenerateContent`
 * endpoint (PLAN.MD 4.9, `integration/gemini/GeminiStream`).
 *
 * Pure Kotlin on purpose: the whole contract is "one `data:` line in, zero or
 * more events out", so the parsing that is easiest to get wrong — merging the
 * parts of one candidate, noticing `finishReason`, surfacing an API `error`
 * object — is unit-tested on the JVM with no network and no server.
 *
 * The API sends:
 *
 * ```
 * data: {"candidates":[{"content":{"parts":[{"text":"..."}],"role":"model"},
 *        "finishReason":"STOP","index":0,...}], "usageMetadata": {...}, ...}
 * ```
 *
 * A line is always one full JSON object (the API never wraps a `data:` line),
 * which is what makes a line-at-a-time feeder correct.
 */
object GeminiStream {

    private val json = Json { ignoreUnknownKeys = true }

    private const val DATA_PREFIX = "data:"

    /**
     * Feeds one SSE line, e.g. `data: {"candidates":[...]}`, and returns the
     * events it describes. A line can describe two: a text delta followed by
     * the finish reason, since the API may put both in its final chunk.
     *
     * Returns an empty list for lines that describe nothing — blank, `[DONE]`,
     * not a `data:` line, or JSON that cannot be parsed (a single malformed
     * line must not tear down a stream that is otherwise fine).
     */
    fun eventsFor(line: String): List<GeminiStreamEvent> {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed == "[DONE]" || !trimmed.startsWith(DATA_PREFIX)) {
            return emptyList()
        }
        val jsonText = trimmed.removePrefix(DATA_PREFIX).trim()
        if (jsonText.isEmpty()) return emptyList()

        val payload = runCatching { json.decodeFromString<StreamPayload>(jsonText) }.getOrNull()
            ?: return emptyList()

        payload.error?.let { err ->
            return listOf(
                GeminiStreamEvent.Failure(
                    code = err.code,
                    status = err.status,
                    message = err.message ?: "error ${err.code}",
                ),
            )
        }

        val candidate = payload.candidates?.firstOrNull()
            ?: return emptyList()

        val events = mutableListOf<GeminiStreamEvent>()

        val parts = candidate.content?.parts?.mapNotNull { it.text } ?: emptyList()
        if (parts.isNotEmpty()) {
            events += GeminiStreamEvent.Delta(parts.joinToString(""))
        }

        val finishReason = candidate.finishReason?.takeIf(String::isNotEmpty)
        if (finishReason != null) {
            events += GeminiStreamEvent.Finished(finishReason)
        }

        return events
    }
}

@Serializable
internal data class StreamPayload(
    val candidates: List<CandidateDto>? = null,
    val error: ErrorDto? = null,
)

@Serializable
internal data class CandidateDto(
    val content: ContentDto? = null,
    @SerialName("finishReason") val finishReason: String? = null,
)

@Serializable
internal data class ContentDto(
    val parts: List<PartDto>? = null,
    val role: String? = null,
)

@Serializable
internal data class PartDto(val text: String? = null)

@Serializable
internal data class ErrorDto(
    val code: Int = -1,
    val message: String? = null,
    val status: String = "UNKNOWN",
)