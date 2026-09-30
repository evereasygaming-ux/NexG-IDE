package com.nexg.ide.testing

import com.nexg.ide.testing.TestOutcome.FAIL
import com.nexg.ide.testing.TestOutcome.MANUAL_DEVICE_REQUIRED
import com.nexg.ide.testing.TestOutcome.PASS
import com.nexg.ide.testing.TestOutcome.UNAVAILABLE

/**
 * The catalogue of checks, split into what automation may decide and what only
 * a human on the phone may decide.
 *
 * The [MANUAL_DEVICE] list is the owner's 26-step checklist, kept verbatim and
 * marked `MANUAL_DEVICE_REQUIRED` even when a scripted check covers a related
 * state, because "the state is right" and "a finger can do it" are different
 * claims. Deleting entries here would be the easiest way to make a report look
 * better, so they are declared as data instead.
 */
object TestSequences {

    data class Spec(
        val id: String,
        val name: String,
        val group: TestGroup,
        val requiresShizuku: Boolean = false,
    )

    /** Editor checks that are genuinely decidable from app state. */
    val AUTOMATED_EDITOR: List<Spec> = listOf(
        Spec("ED-01", "editor page loads (WebView reports page finished)", TestGroup.EDITOR),
        Spec("ED-02", "editorReady received from CodeMirror", TestGroup.EDITOR),
        Spec("ED-03", "initial document matches expected content", TestGroup.EDITOR),
        Spec("ED-04", "setDocument pushes authoritative text", TestGroup.EDITOR),
        Spec("ED-05", "documentChanged observed after an edit", TestGroup.EDITOR),
        Spec("ED-06", "selectionChanged observed", TestGroup.EDITOR),
        Spec("ED-07", "setSelection round-trips offsets", TestGroup.EDITOR),
        Spec("ED-08", "an edit changes the document", TestGroup.EDITOR),
        Spec("ED-09", "undo restores prior text", TestGroup.EDITOR),
        Spec("ED-10", "redo reapplies undone text", TestGroup.EDITOR),
        Spec("ED-11", "find command is accepted and dispatched", TestGroup.EDITOR),
        Spec("ED-12", "replace command is accepted and dispatched", TestGroup.EDITOR),
        Spec("ED-13", "saveRequested path persists text", TestGroup.EDITOR),
        Spec("ED-14", "Kotlin receives the exact saved text", TestGroup.EDITOR),
        Spec("ED-15", "dirty state rises on edit and clears on save", TestGroup.EDITOR),
        Spec("ED-16", "read-only state is enforced", TestGroup.EDITOR),
        Spec("ED-17", "a second file opens with its own identity", TestGroup.EDITOR),
        Spec("ED-18", "tab switching preserves file identity", TestGroup.EDITOR),
    )

    /**
     * The owner's 26-step manual checklist. These are never executed by the
     * harness; they are reported as [TestOutcome.MANUAL_DEVICE_REQUIRED].
     */
    val MANUAL_DEVICE: List<Spec> = listOf(
        Spec("M-01", "Open MainActivity.kt", TestGroup.EDITOR),
        Spec("M-02", "Tap editor", TestGroup.EDITOR),
        Spec("M-03", "Type", TestGroup.EDITOR),
        Spec("M-04", "Backspace/delete", TestGroup.EDITOR),
        Spec("M-05", "Move caret", TestGroup.EDITOR),
        Spec("M-06", "Long-press select", TestGroup.EDITOR),
        Spec("M-07", "Drag selection", TestGroup.EDITOR),
        Spec("M-08", "Copy", TestGroup.EDITOR),
        Spec("M-09", "Cut", TestGroup.EDITOR),
        Spec("M-10", "Paste", TestGroup.EDITOR),
        Spec("M-11", "Select All", TestGroup.EDITOR),
        Spec("M-12", "Undo", TestGroup.EDITOR),
        Spec("M-13", "Redo", TestGroup.EDITOR),
        Spec("M-14", "Multiline editing", TestGroup.EDITOR),
        Spec("M-15", "Scroll", TestGroup.EDITOR),
        Spec("M-16", "Search", TestGroup.EDITOR),
        Spec("M-17", "Replace", TestGroup.EDITOR),
        Spec("M-18", "Go to line", TestGroup.EDITOR),
        Spec("M-19", "Save", TestGroup.EDITOR),
        Spec("M-20", "Close", TestGroup.EDITOR),
        Spec("M-21", "Reopen", TestGroup.EDITOR),
        Spec("M-22", "Verify exact content", TestGroup.EDITOR),
        Spec("M-23", "Open another file", TestGroup.EDITOR),
        Spec("M-24", "Switch tabs", TestGroup.EDITOR),
        Spec("M-25", "Syntax highlighting", TestGroup.EDITOR),
        Spec("M-26", "Dirty indicator", TestGroup.EDITOR),
    )

    /**
     * Checks that need a privileged transport to inject real input. They are
     * only ever attempted when Shizuku is genuinely ready; otherwise they report
     * UNAVAILABLE. They still are not a substitute for the manual list, because
     * `input tap` is not a finger.
     */
    val SHIZUKU_TOUCH: List<Spec> = listOf(
        Spec("SZ-01", "inject tap at editor centre (Shizuku input tap)", TestGroup.EDITOR, requiresShizuku = true),
        Spec("SZ-02", "inject long press (Shizuku input swipe + duration)", TestGroup.EDITOR, requiresShizuku = true),
        Spec("SZ-03", "capture screen via Shizuku screencap", TestGroup.EDITOR, requiresShizuku = true),
        Spec("SZ-04", "dump view hierarchy via Shizuku uiautomator", TestGroup.EDITOR, requiresShizuku = true),
    )

    val PROJECT: List<Spec> = listOf(
        Spec("PR-01", "list projects", TestGroup.PROJECT),
        Spec("PR-02", "open an existing project", TestGroup.PROJECT),
        Spec("PR-03", "create a disposable test project", TestGroup.PROJECT),
        Spec("PR-04", "inspect project tree", TestGroup.PROJECT),
    )

    val PHASE4: List<Spec> = listOf(
        Spec("A4-01", "credential state: no key", TestGroup.PHASE4),
        Spec("A4-02", "credential state: key present (value never read)", TestGroup.PHASE4),
        Spec("A4-03", "connection state reported", TestGroup.PHASE4),
        Spec("A4-04", "rejected-key state reported without the key", TestGroup.PHASE4),
        Spec("A4-05", "credential clear returns to no-key state", TestGroup.PHASE4),
        Spec("A4-06", "credential persists across store instances", TestGroup.PHASE4),
        Spec("A4-07", "no API key reachable from the test bridge", TestGroup.PHASE4),
        Spec("A4-08", "no API key present in harness logs", TestGroup.PHASE4),
    )

    val DEVTOOLS: List<Spec> = listOf(
        Spec("DT-01", "acli reported unavailable when not installed", TestGroup.GENERAL),
        Spec("DT-02", "no acli binary inside the APK", TestGroup.GENERAL),
    )

    /** Every spec the harness knows about, in reporting order. */
    val ALL: List<Spec> = AUTOMATED_EDITOR + MANUAL_DEVICE + SHIZUKU_TOUCH + PROJECT + PHASE4 + DEVTOOLS

    /**
     * The baseline: what a run reports before any device interaction has
     * happened. Nothing here is PASS — the editor, Shizuku and phase 4 checks
     * start UNAVAILABLE and the manual list is declared as manual. This is the
     * shape a report takes when the harness is alive but no device is attached,
     * which is exactly the honest answer rather than a run of green ticks.
     */
    fun baselineResults(environment: TestEnvironment, nowMillis: Long): List<TestResult> = buildList {
        add(
            TestResult(
                id = "GEN-00",
                name = "harness reachable and build is debuggable",
                group = TestGroup.GENERAL,
                outcome = if (environment.isDebuggable) PASS else FAIL,
                detail = "isDebuggable=${environment.isDebuggable}",
            ),
        )
        AUTOMATED_EDITOR.forEach { spec ->
            add(
                TestResult(
                    id = spec.id,
                    name = spec.name,
                    group = spec.group,
                    outcome = UNAVAILABLE,
                    detail = "requires a live editor session on a device",
                    durationMs = 0L,
                ),
            )
        }
        SHIZUKU_TOUCH.forEach { spec ->
            add(
                TestResult(
                    id = spec.id,
                    name = spec.name,
                    group = spec.group,
                    outcome = UNAVAILABLE,
                    detail = if (environment.shizukuState.isUsable) {
                        "shizuku ready but no touch session was executed"
                    } else {
                        "shizuku ${environment.shizukuState.wire}"
                    },
                ),
            )
        }
        PROJECT.forEach { spec ->
            add(TestResult(spec.id, spec.name, spec.group, UNAVAILABLE, "requires a device session"))
        }
        PHASE4.forEach { spec ->
            add(TestResult(spec.id, spec.name, spec.group, UNAVAILABLE, "requires a device session"))
        }
        DEVTOOLS.forEach { spec ->
            add(TestResult(spec.id, spec.name, spec.group, UNAVAILABLE, "requires a device session"))
        }
        MANUAL_DEVICE.forEach { spec ->
            add(
                TestResult(
                    id = spec.id,
                    name = spec.name,
                    group = spec.group,
                    outcome = MANUAL_DEVICE_REQUIRED,
                    detail = "human interaction on real hardware",
                ),
            )
        }
    }
}
