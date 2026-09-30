package com.nexg.ide.integration.gemini

import com.nexg.ide.domain.model.AiMessage
import com.nexg.ide.domain.model.AiRequest
import com.nexg.ide.domain.model.AiRole

/**
 * The assembled pieces of a Gemini completion: the system instruction plus the
 * message turns (PLAN.MD 4.9's `PromptBuilder`).
 *
 * `GeminiPrompt` holds exactly what the wire request needs — Gemini takes the
 * system text in `systemInstruction` and USER/MODEL turns in `contents` — so
 * the "what is allowed to reach the model" decision has one unit-tested home.
 */
data class GeminiPrompt(
    val systemInstruction: String,
    val contents: List<AiMessage>,
)

/**
 * Turns an [AiRequest] into the exact system instruction and turns that go
 * over the wire (PLAN.MD 4.9 / 5.1).
 *
 * The context rule is the plan's, tested the same way: project tree
 * (depth-limited), current file, recent conversation, build errors — nothing
 * else. The provider's own credential never appears anywhere in this file; it
 * receives no store and no key, so it cannot leak one.
 *
 * Budgets are character caps that are easy to verify on the JVM. Content over
 * budget is clipped deterministically (headers stay whole, the tail is cut),
 * so a huge file or an enormous history never blows the prompt.
 */
object PromptBuilder {

    private val SYSTEM_LEAD =
        "You are the coding assistant inside the NexG IDE app. Answer concisely, " +
            "concretely, and in the same language the user writes in. When you propose " +
            "a code change, describe which files it touches and what changes in each. " +
            "Never claim to have changed a file; you only propose changes."

    private const val MAX_TREE_CHARS = 6000
    private const val MAX_FILE_CHARS = 4000
    private const val MAX_ERROR_CHARS = 2000
    private const val MAX_HISTORY_TURNS = 6

    fun build(request: AiRequest): GeminiPrompt {
        val context = buildContext(request)

        // The system instruction is the framing plus the context, capped as a
        // whole: `context` may already be large, so capping only the context and
        // then appending the lead would silently overshoot the budget.
        val system = SYSTEM_LEAD +
            "\n\n" + context.take(request.maxContextChars)
        val systemCapped = system.take(request.maxContextChars)

        val turnBudget = request.maxContextChars - systemCapped.length

        val history = request.conversation
            .filterNot { it.role == AiRole.SYSTEM }
            .takeLast(MAX_HISTORY_TURNS)

        // The newest user message always survives — it is the instruction. The
        // budget it receives is at least one character.
        val turns = clipTurnsToBudget(history, turnBudget.coerceAtLeast(1))

        return GeminiPrompt(systemInstruction = systemCapped, contents = turns)
    }

    private fun buildContext(request: AiRequest): String = buildString {
        if (request.projectTree.isNotEmpty()) {
            appendLine("PROJECT STRUCTURE (depth-limited):")
            request.projectTree.forEach { line ->
                if (!line.isBlank() && length < MAX_TREE_CHARS) appendLine(line)
            }
        }

        request.currentFile?.let { file ->
            if (file.path.isNotBlank()) appendLine("\nCURRENT FILE: ${file.path}")
            if (file.content.isNotEmpty()) {
                appendLine("--- begin ${file.path} ---")
                append(file.content.take(MAX_FILE_CHARS))
                if (file.content.length > MAX_FILE_CHARS) appendLine("\n[file truncated]")
                appendLine("\n--- end ${file.path} ---")
            }
        }

        if (request.buildErrors.isNotEmpty()) {
            appendLine("\nRECENT BUILD ERROR(S):")
            request.buildErrors.take(3).forEach { appendLine("- ${it.take(MAX_ERROR_CHARS)}") }
        }
    }

    /**
     * Keeps the newest turn at all costs and distributes any remaining budget
     * over the earlier turns, so trimming is deterministic and the user's
     * latest message always reaches the model. Earlier turns shrink or drop;
     * the newest only ever gets shorter, never lost.
     */
    private fun clipTurnsToBudget(turns: List<AiMessage>, budget: Int): List<AiMessage> {
        if (turns.isEmpty()) return turns

        var remaining = budget.coerceAtLeast(0)

        // The newest turn is reserved first. It is truncated, never dropped.
        val newest = turns.last()
        val newestContent = newest.content.take(remaining.coerceAtLeast(0))
        remaining -= newestContent.length + 16

        val keptEarlier = mutableListOf<AiMessage>()
        val earlier = turns.dropLast(1)
        for ((index, turn) in earlier.withIndex()) {
            if (remaining <= 0) break
            val share = (remaining / (earlier.size - index)).coerceAtLeast(1)
            val kept = turn.content.take(share)
            remaining -= kept.length + 16
            if (kept.isEmpty()) continue
            keptEarlier += turn.copy(content = kept)
        }

        val newestTurn = newest.copy(content = newestContent)
        return if (newestContent.isNotEmpty()) keptEarlier + newestTurn else keptEarlier
    }
}