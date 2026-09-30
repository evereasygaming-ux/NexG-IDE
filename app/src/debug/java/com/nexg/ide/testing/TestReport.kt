package com.nexg.ide.testing

import org.json.JSONArray
import org.json.JSONObject

/** One executed (or deliberately not-executed) check. */
data class TestResult(
    val id: String,
    val name: String,
    val group: TestGroup,
    val outcome: TestOutcome,
    val detail: String = "",
    val evidence: Map<String, String> = emptyMap(),
    val durationMs: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("group", group.wire)
        put("outcome", outcome.wire)
        put("detail", detail)
        put("durationMs", durationMs)
        put("evidence", JSONObject().apply { evidence.forEach { (k, v) -> put(k, v) } })
    }
}

/**
 * The structured result of one bridge call or one whole sequence.
 *
 * [command] is echoed so a caller can correlate a reply, and [results] is always
 * present (possibly empty) so "nothing ran" is visible rather than implied.
 */
data class TestResponse(
    val command: String,
    val outcome: TestOutcome,
    val detail: String = "",
    val errorCode: String? = null,
    val data: Map<String, String> = emptyMap(),
    val results: List<TestResult> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("command", command)
        put("outcome", outcome.wire)
        put("detail", detail)
        if (errorCode != null) put("errorCode", errorCode)
        put("data", JSONObject().apply { data.forEach { (k, v) -> put(k, v) } })
        put("results", JSONArray().apply { results.forEach { put(it.toJson()) } })
    }

    /** Rolls the response's own outcome up with its per-item results. */
    val rollup: TestOutcome
        get() = if (results.isEmpty()) outcome else TestOutcome.rollup(results.map { it.outcome })

    companion object {
        fun ok(command: String, detail: String = "", data: Map<String, String> = emptyMap()) =
            TestResponse(command, TestOutcome.PASS, detail, null, data)

        fun fail(command: String, detail: String, code: String = "FAILED") =
            TestResponse(command, TestOutcome.FAIL, detail, code)

        fun unavailable(command: String, detail: String, code: String = "UNAVAILABLE") =
            TestResponse(command, TestOutcome.UNAVAILABLE, detail, code)

        fun unsupported(command: String, detail: String) =
            TestResponse(command, TestOutcome.UNSUPPORTED, detail, "UNSUPPORTED")

        fun rejected(command: String, code: String, detail: String) =
            TestResponse(command, TestOutcome.FAIL, detail, code)
    }
}

/** Environment facts captured once at the start of a run. */
data class TestEnvironment(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val buildVariant: String,
    val isDebuggable: Boolean,
    val testHarnessEnabled: Boolean,
    val shizukuState: ShizukuState,
    val shizukuUid: Int? = null,
    val bridgeAvailable: Boolean,
    val editorBridgeReady: Boolean,
    val deviceModel: String = "unknown",
    val apiLevel: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("packageName", packageName)
        put("versionName", versionName)
        put("versionCode", versionCode)
        put("buildVariant", buildVariant)
        put("isDebuggable", isDebuggable)
        put("testHarnessEnabled", testHarnessEnabled)
        put("shizukuState", shizukuState.wire)
        // The uid is a system fact, not a secret, and is null unless it is known.
        if (shizukuUid != null) put("shizukuUid", shizukuUid)
        put("bridgeAvailable", bridgeAvailable)
        put("editorBridgeReady", editorBridgeReady)
        put("deviceModel", deviceModel)
        put("apiLevel", apiLevel)
    }
}

/**
 * A whole run: environment plus results, rendered to text.
 *
 * The renderer is deterministic and side-effect free so the same results always
 * produce byte-identical output, which is what makes the report usable as
 * evidence.
 */
data class TestReport(
    val environment: TestEnvironment,
    val startedAtMillis: Long,
    val results: List<TestResult>,
) {
    val rollup: TestOutcome get() = TestOutcome.rollup(results.map { it.outcome })

    fun counts(): Map<TestOutcome, Int> =
        TestOutcome.entries.associateWith { outcome -> results.count { it.outcome == outcome } }

    fun render(): String = buildString {
        appendLine("NexG IDE device test report")
        appendLine("=".repeat(72))
        appendLine("generated_utc      : ${Iso8601.format(startedAtMillis)}")
        appendLine("package            : ${environment.packageName}")
        appendLine("version            : ${environment.versionName} (${environment.versionCode})")
        appendLine("build_variant      : ${environment.buildVariant}")
        appendLine("debuggable         : ${environment.isDebuggable}")
        appendLine("test_harness       : ${if (environment.testHarnessEnabled) "ENABLED" else "DISABLED"}")
        appendLine("device             : ${environment.deviceModel} (API ${environment.apiLevel})")
        appendLine("shizuku            : ${environment.shizukuState.wire}")
        appendLine("bridge_available   : ${environment.bridgeAvailable}")
        appendLine("editor_bridge_ready: ${environment.editorBridgeReady}")
        appendLine()
        appendLine("SUMMARY")
        appendLine("-".repeat(72))
        TestOutcome.entries.forEach { outcome ->
            appendLine(String.format("%-24s %d", outcome.wire, counts()[outcome] ?: 0))
        }
        appendLine(String.format("%-24s %s", "OVERALL", rollup.wire))
        appendLine()

        TestGroup.entries.forEach { group ->
            val groupResults = results.filter { it.group == group }
            if (groupResults.isEmpty()) return@forEach
            appendLine("GROUP: ${group.wire}")
            appendLine("-".repeat(72))
            groupResults.forEach { result ->
                appendLine("${result.outcome.wire.padEnd(24)} ${result.id}  ${result.name}")
                if (result.detail.isNotEmpty()) appendLine("    detail   : ${result.detail}")
                result.evidence.forEach { (key, value) ->
                    appendLine("    $key: $value")
                }
            }
            appendLine()
        }

        val manual = results.filter { it.outcome == TestOutcome.MANUAL_DEVICE_REQUIRED }
        if (manual.isNotEmpty()) {
            appendLine("MANUAL DEVICE CHECKLIST (not automated, must be done by a human)")
            appendLine("-".repeat(72))
            manual.forEach { appendLine("${it.id.padEnd(6)} ${it.name}") }
            appendLine()
        }

        appendLine("NOTE")
        appendLine("-".repeat(72))
        appendLine("No item in this report is device-verified unless its outcome is PASS")
        appendLine("AND the run happened on real hardware. Touch, gesture, IME, native")
        appendLine("menu and scroll behaviour are always MANUAL_DEVICE_REQUIRED.")
        appendLine("No credentials appear in this report by construction.")
    }
}

/** Minimal UTC formatter, so reports do not depend on device locale or zone. */
object Iso8601 {
    fun format(millis: Long): String {
        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        calendar.timeInMillis = millis
        return String.format(
            "%04d-%02d-%02dT%02d:%02d:%02dZ",
            calendar.get(java.util.Calendar.YEAR),
            calendar.get(java.util.Calendar.MONTH) + 1,
            calendar.get(java.util.Calendar.DAY_OF_MONTH),
            calendar.get(java.util.Calendar.HOUR_OF_DAY),
            calendar.get(java.util.Calendar.MINUTE),
            calendar.get(java.util.Calendar.SECOND),
        )
    }
}
