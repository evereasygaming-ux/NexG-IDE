package com.nexg.ide.integration.atlassian

import com.google.common.truth.Truth.assertThat
import com.nexg.ide.core.log.AppLogger
import com.nexg.ide.core.log.InMemoryLogSink
import com.nexg.ide.domain.port.DeveloperToolEvent
import com.nexg.ide.domain.port.DeveloperToolRequest
import com.nexg.ide.domain.port.ExecutorPort
import com.nexg.ide.domain.port.ProcessEvent
import com.nexg.ide.domain.port.ToolKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The Atlassian boundary: no binary ships in the APK, so the adapter must
 * degrade gracefully when the tool (or the runner) is absent, and only drive
 * a real CLI when one is present. Both behaviours are asserted here with a
 * fake executor.
 */
class AtlassianCliAdapterTest {

    private val logger = AppLogger(InMemoryLogSink())

    @Test
    fun `with no executor the tool is unavailable, never a shell`() = runTest {
        val adapter = AtlassianCliAdapter(executor = null, logger = logger)

        assertThat(adapter.health().available).isFalse()

        val events = adapter.run(DeveloperToolRequest(ToolKind.ISSUE_QUERY, "project = FOO")).toList()

        assertThat(events).containsExactly(DeveloperToolEvent.Unavailable)
    }

    @Test
    fun `an executor that cannot run degrades to unavailable`() = runTest {
        val adapter = AtlassianCliAdapter(executor = UnavailableExecutor, logger = logger)

        assertThat(adapter.health().available).isFalse()

        val events = adapter.run(DeveloperToolRequest(ToolKind.ISSUE_QUERY, "project = FOO")).toList()

        assertThat(events).containsExactly(DeveloperToolEvent.Unavailable)
    }

    @Test
    fun `missing binary reports not found without running`() = runTest {
        val adapter = AtlassianCliAdapter(
            executor = FakeExecutor(available = true, resolves = { null }),
            logger = logger,
        )

        assertThat(adapter.health().available).isFalse()
        assertThat(adapter.health().detail).contains("not found")
    }

    @Test
    fun `present binary is reported with its path`() = runTest {
        val adapter = AtlassianCliAdapter(
            executor = FakeExecutor(available = true, resolves = { "/usr/bin/acli" }),
            logger = logger,
        )

        assertThat(adapter.health().available).isTrue()
        assertThat(adapter.health().detail).contains("/usr/bin/acli")
    }

    @Test
    fun `a jql query runs through the binary and streams output and exit`() = runTest {
        val fake = FakeExecutor(available = true, resolves = { "/usr/bin/acli" })
        fake.outputs = { _, _ ->
            listOf(ProcessEvent.Stdout("KEY-1|bug|Open"), ProcessEvent.Exited(0))
        }
        val adapter = AtlassianCliAdapter(executor = fake, logger = logger)

        val events = adapter.run(
            DeveloperToolRequest(ToolKind.ISSUE_QUERY, "project = FOO"),
        ).toList()

        assertThat(events).containsExactly(
            DeveloperToolEvent.Output("KEY-1|bug|Open"),
            DeveloperToolEvent.Exited(0),
        )
        assertThat(fake.lastProgram).isEqualTo("/usr/bin/acli")
        assertThat(fake.lastArgs).isEqualTo(listOf("jira", "search", "--jql", "project = FOO"))
    }

    @Test
    fun `a failing command surfaces a non-zero exit`() = runTest {
        val fake = FakeExecutor(available = true, resolves = { "/usr/bin/acli" })
        fake.outputs = { _, _ -> listOf(ProcessEvent.Exited(1)) }
        val adapter = AtlassianCliAdapter(executor = fake, logger = logger)

        val events = adapter.run(
            DeveloperToolRequest(ToolKind.ISSUE_FETCH, "KEY-7", projectKey = "KEY-7"),
        ).toList()

        assertThat(events).contains(DeveloperToolEvent.Exited(1))
    }

    @Test
    fun `issue fetch passes the project key through`() = runTest {
        val fake = FakeExecutor(available = true, resolves = { "/usr/bin/acli" })
        fake.outputs = { _, _ -> listOf(ProcessEvent.Exited(0)) }
        val adapter = AtlassianCliAdapter(executor = fake, logger = logger)

        adapter.run(DeveloperToolRequest(ToolKind.ISSUE_FETCH, "KEY-7", projectKey = "KEY-9")).toList()

        assertThat(fake.lastArgs).isEqualTo(listOf("jira", "getIssue", "--issue", "KEY-9"))
    }

    // ------------------------------------------------------------------ fakes

    private object UnavailableExecutor : ExecutorPort {
        override val available: Boolean = false
        override suspend fun resolveExecutable(name: String): String? = null
        override suspend fun run(program: String, args: List<String>): Flow<ProcessEvent> =
            flowOf()
    }

    private class FakeExecutor(
        override val available: Boolean,
        private val resolves: () -> String?,
    ) : ExecutorPort {

        var lastProgram: String? = null
        var lastArgs: List<String>? = null

        /** Hook the test uses to script stdout/stderr/exit. */
        var outputs: (String, List<String>) -> List<ProcessEvent> = { _, _ ->
            listOf(ProcessEvent.Exited(0))
        }

        override suspend fun resolveExecutable(name: String): String? = resolves()

        override suspend fun run(
            program: String,
            args: List<String>,
        ): Flow<ProcessEvent> {
            lastProgram = program
            lastArgs = args
            return flowOf(*outputs(program, args).toTypedArray())
        }
    }
}