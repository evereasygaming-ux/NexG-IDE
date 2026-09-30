package com.nexg.ide.testing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The decision layer: given what the bridge reported, is this PASS, FAIL, or
 * simply not observable yet?
 *
 * The recurring assertion in this file is that an unobservable precondition is
 * UNAVAILABLE, not FAIL and certainly not PASS.
 */
class EditorChecksTest {

    private val ready = TestSequences.AUTOMATED_EDITOR.first { it.id == "ED-02" }
    private val document = TestSequences.AUTOMATED_EDITOR.first { it.id == "ED-03" }
    private val edit = TestSequences.AUTOMATED_EDITOR.first { it.id == "ED-08" }
    private val selection = TestSequences.AUTOMATED_EDITOR.first { it.id == "ED-07" }
    private val undoRedo = TestSequences.AUTOMATED_EDITOR.first { it.id == "ED-09" }
    private val dirty = TestSequences.AUTOMATED_EDITOR.first { it.id == "ED-15" }
    private val readOnly = TestSequences.AUTOMATED_EDITOR.first { it.id == "ED-16" }
    private val tabs = TestSequences.AUTOMATED_EDITOR.first { it.id == "ED-18" }
    private val noSecret = TestSequences.PHASE4.first { it.id == "A4-07" }

    @Test
    fun editorReadyPassesOnlyWhenReady() {
        assertThat(EditorChecks.checkEditorReady("ED-02", ready, true).outcome)
            .isEqualTo(TestOutcome.PASS)
        // Not ready is "cannot tell yet", not "broken".
        assertThat(EditorChecks.checkEditorReady("ED-02", ready, false).outcome)
            .isEqualTo(TestOutcome.UNAVAILABLE)
    }

    @Test
    fun initialDocumentComparesExactly() {
        assertThat(EditorChecks.checkInitialDocument("ED-03", document, "val x = 1", "val x = 1").outcome)
            .isEqualTo(TestOutcome.PASS)
        assertThat(EditorChecks.checkInitialDocument("ED-03", document, "val x = 2", "val x = 1").outcome)
            .isEqualTo(TestOutcome.FAIL)
        assertThat(EditorChecks.checkInitialDocument("ED-03", document, null, "val x = 1").outcome)
            .isEqualTo(TestOutcome.UNAVAILABLE)
    }

    @Test
    fun editMustChangeTheDocument() {
        assertThat(EditorChecks.checkEditChangedDocument("ED-08", edit, "a", "ab").outcome)
            .isEqualTo(TestOutcome.PASS)
        assertThat(EditorChecks.checkEditChangedDocument("ED-08", edit, "a", "a").outcome)
            .isEqualTo(TestOutcome.FAIL)
        assertThat(EditorChecks.checkEditChangedDocument("ED-08", edit, null, "a").outcome)
            .isEqualTo(TestOutcome.UNAVAILABLE)
    }

    @Test
    fun selectionMustRoundTrip() {
        assertThat(EditorChecks.checkSelectionRoundTrip("ED-07", selection, 3 to 7, 3 to 7).outcome)
            .isEqualTo(TestOutcome.PASS)
        assertThat(EditorChecks.checkSelectionRoundTrip("ED-07", selection, 3 to 7, 3 to 8).outcome)
            .isEqualTo(TestOutcome.FAIL)
        assertThat(EditorChecks.checkSelectionRoundTrip("ED-07", selection, 3 to 7, null).outcome)
            .isEqualTo(TestOutcome.UNAVAILABLE)
    }

    @Test
    fun undoRestoresAndRedoReapplies() {
        assertThat(EditorChecks.checkUndoRedo("ED-09", undoRedo, "a", "a", "ab").outcome)
            .isEqualTo(TestOutcome.PASS)
        // Undo that changed nothing and redo that changed nothing: a real fail.
        assertThat(EditorChecks.checkUndoRedo("ED-09", undoRedo, "a", "a", "a").outcome)
            .isEqualTo(TestOutcome.FAIL)
        // Undo did not restore the prior text.
        assertThat(EditorChecks.checkUndoRedo("ED-09", undoRedo, "a", "zz", "zz").outcome)
            .isEqualTo(TestOutcome.FAIL)
        assertThat(EditorChecks.checkUndoRedo("ED-09", undoRedo, "a", null, "ab").outcome)
            .isEqualTo(TestOutcome.UNAVAILABLE)
    }

    @Test
    fun dirtyMustRiseThenClear() {
        assertThat(EditorChecks.checkDirtyCycle("ED-15", dirty, true, false).outcome)
            .isEqualTo(TestOutcome.PASS)
        assertThat(EditorChecks.checkDirtyCycle("ED-15", dirty, false, false).outcome)
            .isEqualTo(TestOutcome.FAIL)
        assertThat(EditorChecks.checkDirtyCycle("ED-15", dirty, true, true).outcome)
            .isEqualTo(TestOutcome.FAIL)
    }

    @Test
    fun readOnlyRefusesEdits() {
        assertThat(EditorChecks.checkReadOnlyEnforced("ED-16", readOnly, true, true).outcome)
            .isEqualTo(TestOutcome.PASS)
        assertThat(EditorChecks.checkReadOnlyEnforced("ED-16", readOnly, true, false).outcome)
            .isEqualTo(TestOutcome.FAIL)
        // A buffer that is not read-only cannot prove the guard works.
        assertThat(EditorChecks.checkReadOnlyEnforced("ED-16", readOnly, false, true).outcome)
            .isEqualTo(TestOutcome.UNAVAILABLE)
    }

    @Test
    fun tabSwitchPreservesIdentity() {
        val identity = "content://tree/root/document/file:Main.kt" to "Main.kt"
        assertThat(EditorChecks.checkTabIdentity("ED-18", tabs, identity, identity).outcome)
            .isEqualTo(TestOutcome.PASS)
        assertThat(EditorChecks.checkTabIdentity("ED-18", tabs, identity, identity.first to "Other.kt").outcome)
            .isEqualTo(TestOutcome.PASS)
        assertThat(EditorChecks.checkTabIdentity("ED-18", tabs, identity, "uri:other" to "Other.kt").outcome)
            .isEqualTo(TestOutcome.FAIL)
    }

    @Test
    fun noCredentialIsReachableThroughTheBridge() {
        assertThat(EditorChecks.checkNoCredentialInBridge("A4-07", noSecret, TestCommandSpec.SCHEMAS).outcome)
            .isEqualTo(TestOutcome.PASS)
    }

    @Test
    fun languageMappingMatchesTheProductionBridge() {
        assertThat(EditorChecks.jsLanguageFor("kotlin")).isEqualTo("kotlin")
        assertThat(EditorChecks.jsLanguageFor("nonsense")).isEqualTo("plain")
    }
}

/**
 * The catalogue itself, and the baseline a no-device run produces.
 */
class TestSequencesTest {

    @Test
    fun manualChecklistIsAllTwentySixAndUnchanged() {
        assertThat(TestSequences.MANUAL_DEVICE).hasSize(26)
        assertThat(TestSequences.MANUAL_DEVICE.first().name).isEqualTo("Open MainActivity.kt")
        assertThat(TestSequences.MANUAL_DEVICE.last().name).isEqualTo("Dirty indicator")
    }

    @Test
    fun everyManualItemIsManualDeviceRequired() {
        val environment = environment(shizuku = ShizukuState.SHIZUKU_READY, editorReady = true)
        val results = TestSequences.baselineResults(environment, 0L).associateBy { it.id }
        TestSequences.MANUAL_DEVICE.forEach { spec ->
            assertThat(results.getValue(spec.id).outcome).isEqualTo(TestOutcome.MANUAL_DEVICE_REQUIRED)
        }
    }

    @Test
    fun nothingIsPassWithoutARealRun() {
        // Even with Shizuku ready and the editor ready, a baseline run has
        // executed nothing, so nothing may be PASS except the build check.
        val environment = environment(shizuku = ShizukuState.SHIZUKU_READY, editorReady = true)
        val results = TestSequences.baselineResults(environment, 0L)
        val passes = results.filter { it.outcome == TestOutcome.PASS }.map { it.id }
        assertThat(passes).containsExactly("GEN-00")
    }

    @Test
    fun shizukuTouchChecksAreUnavailableWithoutShizuku() {
        val results = TestSequences.baselineResults(
            environment(shizuku = ShizukuState.SHIZUKU_UNAVAILABLE, editorReady = false),
            0L,
        ).associateBy { it.id }
        TestSequences.SHIZUKU_TOUCH.forEach { spec ->
            assertThat(results.getValue(spec.id).outcome).isEqualTo(TestOutcome.UNAVAILABLE)
            assertThat(results.getValue(spec.id).detail).contains("SHIZUKU_UNAVAILABLE")
        }
    }

    @Test
    fun specIdentifiersAreUnique() {
        val ids = TestSequences.ALL.map { it.id }
        assertThat(ids).containsNoDuplicates()
    }

    @Test
    fun everySpecHasANonEmptyName() {
        TestSequences.ALL.forEach { assertThat(it.name).isNotEmpty() }
    }

    private fun environment(shizuku: ShizukuState, editorReady: Boolean) = TestEnvironment(
        packageName = "com.nexg.ide",
        versionName = "0.1.0-test",
        versionCode = 1L,
        buildVariant = "debug",
        isDebuggable = true,
        testHarnessEnabled = true,
        shizukuState = shizuku,
        bridgeAvailable = true,
        editorBridgeReady = editorReady,
    )
}
