package com.nexg.ide.testing

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import com.nexg.ide.BuildConfig
import com.nexg.ide.testing.TestOutcome.Companion.rollup

/**
 * The debug-only entry point for everything the harness can observe.
 *
 * Responsibilities, all of them read-only or allowlisted: report status, run a
 * named sequence, and produce a report. It deliberately exposes no generic
 * "do this" entry point, so there is nothing a caller can use to reach behaviour
 * that is not spelled out in [TestCommandSpec].
 */
class TestController(
    private val context: Context,
    private val editor: CodeMirrorTestSurface? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** True only for a debuggable build with the harness compiled in. */
    fun isHarnessEnabled(): Boolean = BuildConfig.TEST_HARNESS && BuildConfig.DEBUG

    /**
     * Current environment facts.
     *
     * [TestEnvironment.bridgeAvailable] is false on a release build, which is
     * the value a caller must branch on: a release build is not "harness
     * working but nothing to do", it is "this must not be here at all".
     */
    fun environment(): TestEnvironment {
        val info: PackageInfo? = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val shizuku = if (isHarnessEnabled()) ShizukuProbe.probe(context) else ShizukuState.UNSUPPORTED
        return TestEnvironment(
            packageName = context.packageName,
            versionName = info?.versionName ?: "unknown",
            versionCode = info?.let { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() } ?: 0L,
            buildVariant = if (BuildConfig.DEBUG) "debug" else "release",
            isDebuggable = BuildConfig.DEBUG,
            testHarnessEnabled = isHarnessEnabled(),
            shizukuState = shizuku,
            shizukuUid = if (isHarnessEnabled()) ShizukuProbe.uidOrNull() else null,
            bridgeAvailable = isHarnessEnabled(),
            editorBridgeReady = editor?.isEditorReady() ?: false,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            apiLevel = Build.VERSION.SDK_INT,
        )
    }

    /**
     * A full report with nothing executed.
     *
     * This is what the CLI's `status` and `report` commands return when no
     * session has run. It is deliberately a report of unavailability rather than
     * an empty one.
     */
    fun baselineReport(): TestReport {
        val environment = environment()
        return TestReport(environment, clock(), TestSequences.baselineResults(environment, clock()))
    }

    /**
     * Handles one allowlisted command.
     *
     * Validation has already happened in [TestRequestParser]; a caller that
     * skips it cannot get here, because this function takes a [TestCommand]
     * rather than a string.
     */
    fun execute(request: TestRequest.Ok): TestResponse {
        if (!isHarnessEnabled()) {
            return TestResponse.rejected(request.command.wire, "PRODUCTION_BUILD", "harness is disabled on this build")
        }
        return when (request.command) {
            TestCommand.PING -> TestResponse.ok("ping", "harness alive")
            TestCommand.APP_VERSION -> TestResponse.ok(
                "appVersion",
                "package=${context.packageName}",
                mapOf(
                    "versionName" to environment().versionName,
                    "versionCode" to environment().versionCode.toString(),
                    "buildVariant" to environment().buildVariant,
                    "isDebuggable" to environment().isDebuggable.toString(),
                    "testHarnessEnabled" to environment().testHarnessEnabled.toString(),
                ),
            )
            TestCommand.TEST_BUILD_INFO -> {
                val env = environment()
                TestResponse.ok(
                    "testBuildInfo",
                    "shizuku=${env.shizukuState.wire}",
                    mapOf(
                        "shizukuState" to env.shizukuState.wire,
                        "shizukuDetail" to ShizukuProbe.describe(env.shizukuState),
                        "bridgeAvailable" to env.bridgeAvailable.toString(),
                        "editorBridgeReady" to env.editorBridgeReady.toString(),
                        "deviceModel" to env.deviceModel,
                        "apiLevel" to env.apiLevel.toString(),
                    ),
                )
            }
            TestCommand.RUN_SEQUENCE -> {
                val sequence = request.args["arg0"] as String
                val report = run(sequence)
                TestResponse(
                    command = request.command.wire,
                    // Deliberately PASS only when the roll-up is a real pass;
                    // an all-UNAVAILABLE run reports UNAVAILABLE.
                    outcome = report.rollup,
                    detail = "sequence=$sequence results=${report.results.size}",
                    data = mapOf("sequence" to sequence),
                    results = report.results,
                )
            }
            TestCommand.INSPECT_AI_STATE, TestCommand.INSPECT_SETTINGS_STATE, TestCommand.TEST_CREDENTIAL_STATE ->
                credentialStateResponse(request.command)
            // Project, editor and general sequences all need a live session that
            // only a device provides. Reporting UNAVAILABLE here is the honest
            // answer; fabricating a PASS would be the one unacceptable option.
            else -> TestResponse.unavailable(
                request.command.wire,
                "requires a live device session; run `nexg-test ${request.command.wire}` from the device",
            )
        }
    }

    /**
     * Phase 4 state without credentials.
     *
     * The only thing reported about the Gemini key is whether one exists. The
     * value is never read, so it cannot leak through this path even by accident.
     */
    private fun credentialStateResponse(command: TestCommand): TestResponse {
        val state = credentialProbe?.invoke() ?: return TestResponse.unavailable(
            command.wire,
            "no credential probe is attached in this process",
        )
        return TestResponse.ok(
            command.wire,
            "credential presence only; the value is never read",
            state,
        )
    }

    /**
     * Supplies the key-present flag. Injected so the harness never needs a
     * `CredentialStore` reference, and so a test can drive the state machine
     * without a keystore.
     */
    var credentialProbe: (() -> Map<String, String>)? = null

    /** Runs a whole named sequence and returns a report. */
    fun run(sequence: String): TestReport {
        val environment = environment()
        val started = clock()
        val baseline = TestSequences.baselineResults(environment, started)
        val requested = TestSequences.ALL.filter { sequence.equals("full", true) || it.group.wire == sequence }
        val results = if (requested.isEmpty()) {
            baseline + TestResult("GEN-ERR", "unknown sequence: $sequence", TestGroup.GENERAL, TestOutcome.FAIL, "known: full, project, editor, phase4, general")
        } else {
            baseline.filter { baselineResult -> requested.any { it.id == baselineResult.id } } +
                requested.mapNotNull { spec -> executeSpec(spec) }
        }
        return TestReport(environment, started, results)
    }

    /**
     * Executes a single spec, or returns `null` when the spec is one this
     * harness must not claim to have run.
     */
    private fun executeSpec(spec: TestSequences.Spec): TestResult? {
        // The manual list is declared, never executed.
        if (TestSequences.MANUAL_DEVICE.any { it.id == spec.id }) {
            return TestResult(spec.id, spec.name, spec.group, TestOutcome.MANUAL_DEVICE_REQUIRED, "human interaction on real hardware")
        }
        if (spec.requiresShizuku && !environment().shizukuState.isUsable) {
            return TestResult(spec.id, spec.name, spec.group, TestOutcome.UNAVAILABLE, "shizuku ${environment().shizukuState.wire}")
        }
        if (editor == null && spec.group == TestGroup.EDITOR) {
            return TestResult(spec.id, spec.name, spec.group, TestOutcome.UNAVAILABLE, "no editor attached to this process")
        }
        // Remaining specs need a real session; nothing here is invented.
        return TestResult(spec.id, spec.name, spec.group, TestOutcome.UNAVAILABLE, "no device session executed this check")
    }

    /** Roll-up of a report, exposed so the CLI need not re-derive it. */
    fun rollup(report: TestReport): TestOutcome = rollup(report.results.map { it.outcome })
}
