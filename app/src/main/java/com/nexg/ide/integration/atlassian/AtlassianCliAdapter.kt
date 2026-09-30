package com.nexg.ide.integration.atlassian

import com.nexg.ide.core.log.AppLogger
import com.nexg.ide.core.log.LogCategory
import com.nexg.ide.domain.port.DeveloperToolEvent
import com.nexg.ide.domain.port.DeveloperToolHealth
import com.nexg.ide.domain.port.DeveloperToolPort
import com.nexg.ide.domain.port.DeveloperToolRequest
import com.nexg.ide.domain.port.ExecutorPort
import com.nexg.ide.domain.port.ProcessEvent
import com.nexg.ide.domain.port.ToolKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Atlassian command-line tool boundary (PLAN.MD Part 3 developer-tool seam).
 *
 * Phase 4 rule, tested: **no Atlassian binary ships inside the APK.** This
 * adapter only *drives* a real `acli` when the underlying [ExecutorPort]
 * reports it present; otherwise it degrades to "unavailable" without throwing
 * and without ever pretending a command ran.
 *
 * The production container passes `null` for [executor] until the Termux
 * executor phase ships (an unconfigured tool is a real state, not a mock). The
 * two behaviours — missing tool vs. running tool — are both covered by the
 * unit tests with a fake executor.
 */
class AtlassianCliAdapter(
    private val executor: ExecutorPort?,
    private val logger: AppLogger,
) : DeveloperToolPort {

    override val id: String = "atlassian-cli"

    private val binaryName: String = "acli"

    override suspend fun health(): DeveloperToolHealth {
        val exe = executor
        if (exe == null || !exe.available) {
            return DeveloperToolHealth(
                available = false,
                detail = "Command runner unavailable (lands with the Termux executor phase)",
            )
        }
        val path = exe.resolveExecutable(binaryName)
        return if (path == null) {
            DeveloperToolHealth(
                available = false,
                detail = "$binaryName not found on PATH.",
            )
        } else {
            DeveloperToolHealth(available = true, detail = "$binaryName at $path")
        }
    }

    override fun run(request: DeveloperToolRequest): Flow<DeveloperToolEvent> = flow {
        val exe = executor
        val path = exe?.let { if (it.available) it.resolveExecutable(binaryName) else null }

        if (exe == null || path == null) {
            logger.w(
                LogCategory.TERMUX,
                "atlassian.run unavailable kind=${request.kind} (tool not installed)",
            )
            emit(DeveloperToolEvent.Unavailable)
            return@flow
        }

        val args = commandArgs(request)

        logger.i(
            LogCategory.TERMUX,
            "atlassian.run kind=${request.kind} program=$binaryName",
        )

        exe.run(path, args).collect { event ->
            when (event) {
                is ProcessEvent.Stdout -> emit(DeveloperToolEvent.Output(event.line))
                is ProcessEvent.Stderr -> emit(DeveloperToolEvent.Output("[err] ${event.line}"))
                is ProcessEvent.Exited -> emit(DeveloperToolEvent.Exited(event.code))
            }
        }
    }

    private fun commandArgs(request: DeveloperToolRequest): List<String> = when (request.kind) {
        ToolKind.ISSUE_QUERY -> listOf("jira", "search", "--jql", request.payload)
        ToolKind.ISSUE_FETCH -> listOf(
            "jira",
            "getIssue",
            "--issue",
            request.projectKey ?: request.payload,
        )
        // Deliberately a passthrough the CLI itself validates: this adapter does
        // not know the full `acli` surface, and inventing a schema for it here
        // would break faster than the CLI would.
        ToolKind.COMMAND -> listOf(request.payload)
    }
}