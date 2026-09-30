package com.nexg.ide.domain.model

import com.nexg.ide.core.result.AppError

/**
 * The pure-Kotlin data types of the AI assistant (PLAN.MD 4.9).
 *
 * These live in `domain/` because the assistant's contract is backend-agnostic:
 * a request is "these messages, this project context", an event is "this much
 * text arrived / it finished / it failed", and neither mentions OkHttp, SSE or
 * Gemini. `AiBackend` implementations translate both directions.
 */

enum class AiRole { SYSTEM, USER, MODEL }

data class AiMessage(
    val role: AiRole,
    val content: String,
)

/** The current file, when the user asked about a specific file. */
data class CurrentFile(
    val path: String,
    val content: String,
    val language: String? = null,
)

/**
 * Everything the assistant is allowed to know about the user's project.
 *
 * PLAN.MD 4.9 is explicit about the boundary: project tree (depth-limited),
 * the current file, the recent conversation, and build errors — nothing else,
 * and no secrets. The secret the backend itself uses (the Gemini key) never
 * travels inside a request; it is attached to the HTTP request by the adapter.
 */
data class AiRequest(
    /** The recent conversation, oldest first. */
    val conversation: List<AiMessage> = emptyList(),
    /** Depth-limited project tree, one human-readable line per entry. */
    val projectTree: List<String> = emptyList(),
    val currentFile: CurrentFile? = null,
    val buildErrors: List<String> = emptyList(),
    /** Budget that [PromptBuilder] must respect, in characters, not tokens. */
    val maxContextChars: Int = 24_000,
    val temperature: Double = 0.2,
    val maxOutputTokens: Int = 4096,
)

/**
 * A chunk of the streaming answer (PLAN.MD 4.9: `Flow<AiEvent>`).
 */
sealed interface AiEvent {
    data class Delta(val text: String) : AiEvent

    /** The model said it was done. `finishReason` is raw, e.g. `STOP`. */
    data class Done(val finishReason: String?) : AiEvent

    data class Error(val error: AppError) : AiEvent
}

/**
 * Connectivity answer for the health surface (PLAN.MD 4.9 `health()`).
 *
 * [reachable] and [authorized] are deliberately separate. The Phase 1 decision
 * log records that a bare 403 from the Gemini API means "the network works but
 * the key is missing or wrong" — which is a *successful connectivity check*
 * and a *failed authorization check* at the same time, and a UI that reports
 * "offline" for it is lying to the user.
 */
data class BackendHealth(
    val reachable: Boolean,
    val authorized: Boolean,
    val model: String,
    val detail: String,
)