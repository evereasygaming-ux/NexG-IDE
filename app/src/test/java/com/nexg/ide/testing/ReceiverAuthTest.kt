package com.nexg.ide.testing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The property that actually matters about the token guard: an unauthorized
 * request must never reach the allowlist or the controller.
 *
 * [NexGTestBridge.handle] takes the dispatcher as a lambda precisely so this can
 * be asserted — asking "was the controller called?" is the only meaningful test
 * of an authorization check, and it is unaskable if the logic lives inside
 * `BroadcastReceiver.onReceive`.
 */
class ReceiverAuthTest {

    private val token = HarnessToken.newToken()
    private val ping = """{"command":"ping","args":{}}"""

    /** Records whether the controller was reached at all. */
    private class Recorder {
        var called = false
        var lastCommand: TestCommand? = null

        val dispatch: (TestRequest.Ok) -> TestResponse = { request ->
            called = true
            lastCommand = request.command
            TestResponse.ok(request.command.wire, "dispatched")
        }
    }

    @Test
    fun missingTokenNeverReachesTheController() {
        val recorder = Recorder()
        val response = NexGTestBridge.handle(ping, null, token, recorder.dispatch)

        assertThat(recorder.called).isFalse()
        assertThat(response.outcome).isEqualTo(TestOutcome.FAIL)
        assertThat(response.errorCode).isEqualTo(HarnessAuth.UNAUTHORIZED)
    }

    @Test
    fun emptyTokenNeverReachesTheController() {
        val recorder = Recorder()
        val response = NexGTestBridge.handle(ping, "", token, recorder.dispatch)

        assertThat(recorder.called).isFalse()
        assertThat(response.errorCode).isEqualTo(HarnessAuth.UNAUTHORIZED)
    }

    @Test
    fun invalidTokenNeverReachesTheController() {
        val recorder = Recorder()
        val response = NexGTestBridge.handle(ping, "not-a-token", token, recorder.dispatch)

        assertThat(recorder.called).isFalse()
        assertThat(response.errorCode).isEqualTo(HarnessAuth.UNAUTHORIZED)
    }

    @Test
    fun anotherInstallsTokenNeverReachesTheController() {
        val recorder = Recorder()
        val response = NexGTestBridge.handle(ping, HarnessToken.newToken(), token, recorder.dispatch)

        assertThat(recorder.called).isFalse()
        assertThat(response.errorCode).isEqualTo(HarnessAuth.UNAUTHORIZED)
    }

    @Test
    fun validTokenReachesTheController() {
        val recorder = Recorder()
        val response = NexGTestBridge.handle(ping, token, token, recorder.dispatch)

        assertThat(recorder.called).isTrue()
        assertThat(recorder.lastCommand).isEqualTo(TestCommand.PING)
        assertThat(response.outcome).isEqualTo(TestOutcome.PASS)
    }

    @Test
    fun authorizationIsCheckedBeforeTheAllowlist() {
        // An unauthenticated caller sending a *deliberately malformed* request
        // must still be told UNAUTHORIZED, not a parse error. If the order were
        // reversed, error codes would let an unauthenticated app map the
        // harness's command surface one probe at a time.
        val recorder = Recorder()
        val response = NexGTestBridge.handle("{ this is not json", null, token, recorder.dispatch)

        assertThat(recorder.called).isFalse()
        assertThat(response.errorCode).isEqualTo(HarnessAuth.UNAUTHORIZED)
    }

    @Test
    fun authorizationIsCheckedBeforeAnUnsafeArgumentIsScreened() {
        // Same argument as above, for a request that would otherwise be rejected
        // by the path/shell screen. The auth failure must win.
        val recorder = Recorder()
        val response = NexGTestBridge.handle(
            """{"command":"openFile","args":{"arg0":"../../etc/shadow"}}""",
            "not-a-token",
            token,
            recorder.dispatch,
        )

        assertThat(recorder.called).isFalse()
        assertThat(response.errorCode).isEqualTo(HarnessAuth.UNAUTHORIZED)
    }

    @Test
    fun theAllowlistStillAppliesToAnAuthorizedCaller() {
        // Token authorization must not have displaced the existing guard: a
        // properly authorized caller still cannot send an unknown command.
        val recorder = Recorder()
        val response = NexGTestBridge.handle(
            """{"command":"execShell","args":{"arg0":"id"}}""",
            token,
            token,
            recorder.dispatch,
        )

        assertThat(recorder.called).isFalse()
        assertThat(response.errorCode).isEqualTo("UNKNOWN_COMMAND")
    }

    @Test
    fun theEnumAllowlistStillAppliesToAnAuthorizedCaller() {
        val recorder = Recorder()
        val response = NexGTestBridge.handle(
            """{"command":"runSequence","args":{"arg0":"exec"}}""",
            token,
            token,
            recorder.dispatch,
        )

        assertThat(recorder.called).isFalse()
        assertThat(response.errorCode).isEqualTo("NOT_ALLOWED")
    }

    @Test
    fun thePathScreenStillAppliesToAnAuthorizedCaller() {
        val recorder = Recorder()
        val response = NexGTestBridge.handle(
            """{"command":"openFile","args":{"arg0":"/data/data/com.nexg.ide/nexg.db"}}""",
            token,
            token,
            recorder.dispatch,
        )

        assertThat(recorder.called).isFalse()
        assertThat(response.errorCode).isEqualTo("UNSAFE_ARGUMENT")
    }

    @Test
    fun everyAllowlistedCommandStillRequiresTheToken() {
        // Belt and braces: no command in the enum may have an unauthenticated
        // path, so this iterates the whole surface rather than trusting a sample.
        // Arguments are sent empty on purpose — the token must be rejected before
        // argument validation ever gets a say.
        TestCommand.entries.forEach { command ->
            val recorder = Recorder()
            val response = NexGTestBridge.handle(
                """{"command":"${command.wire}","args":{}}""",
                null,
                token,
                recorder.dispatch,
            )
            assertThat(recorder.called).isFalse()
            assertThat(response.errorCode).isEqualTo(HarnessAuth.UNAUTHORIZED)
        }
    }
}
