package com.nexg.ide.domain.port

import kotlinx.coroutines.flow.Flow

/**
 * The process-execution seam (PLAN.MD Part 3 port diagram, Tier 2).
 *
 * The Termux implementation ships in Phase 8; this interface is declared now
 * because the developer-tool boundary needs a place to run from, and it must
 * be testable without a device. Keep it as small as the plan does: there is no
 * PTY, no environment management, no signalling — only resolve + run + a
 * stream of process events.
 */
sealed interface ProcessEvent {
    data class Stdout(val line: String) : ProcessEvent
    data class Stderr(val line: String) : ProcessEvent
    data class Exited(val code: Int) : ProcessEvent
}

interface ExecutorPort {
    /** Whether an executor is installed at all (e.g. Termux present). */
    val available: Boolean

    /**
     * Absolute path of [name] on the executable search path, or `null`.
     *
     * The platform's `which`/`command -v` semantics. Returning `null` is the
     * graceful signal that a tool is simply not installed — never a crash.
     */
    suspend fun resolveExecutable(name: String): String?

    /** Runs [program] with [args] and streams its events to completion. */
    suspend fun run(program: String, args: List<String>): Flow<ProcessEvent>
}