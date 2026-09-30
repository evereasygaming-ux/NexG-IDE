package com.nexg.ide.domain.port

import kotlinx.coroutines.flow.Flow

/** What the user asked a developer tool to do. */
enum class ToolKind {
    /** Atlassian Jira / JQL search. */
    ISSUE_QUERY,
    /** Fetch one issue by key. */
    ISSUE_FETCH,
    /** A generic CLI command passed straight through. */
    COMMAND,
}

data class DeveloperToolRequest(
    val kind: ToolKind,
    val payload: String,
    val projectKey: String? = null,
)

data class DeveloperToolHealth(
    val available: Boolean,
    val detail: String,
)

sealed interface DeveloperToolEvent {
    /** One line of tool stdout. */
    data class Output(val text: String) : DeveloperToolEvent

    data class Exited(val code: Int) : DeveloperToolEvent

    /** The tool is not installed / not runnable, emitted before the flow ends. */
    data object Unavailable : DeveloperToolEvent
}

/**
 * The developer-tool seam: Jira / issue CLIs and anything else that talks to a
 * local developer toolchain (PLAN.MD Part 3 is explicit that these are
 * Tier 2 / Tier 3 boundaries).
 *
 * The Phase 4 rule is strict: **no binary ships inside the APK**. The adapter
 * probes the executor for the real tool and degrades to [DeveloperToolHealth]
 * with `available = false` when it is absent. A UI that cannot run a tool must
 * say so — never fake the shell.
 */
interface DeveloperToolPort {
    val id: String

    suspend fun health(): DeveloperToolHealth

    /**
     * Runs one request. When the tool is unavailable the flow must emit
     * [DeveloperToolEvent.Unavailable] and end, without throwing and without
     * ever pretending a command ran.
     */
    fun run(request: DeveloperToolRequest): Flow<DeveloperToolEvent>
}