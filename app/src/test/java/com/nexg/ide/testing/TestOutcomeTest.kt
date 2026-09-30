package com.nexg.ide.testing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The honesty contract.
 *
 * If any of these tests were removed the reports could start lying, so they are
 * the most important tests in the harness: an unavailable environment must never
 * render as a pass, and a manual check must never be silently automated.
 */
class TestOutcomeTest {

    @Test
    fun onlyPassIsAVerifiedPass() {
        assertThat(TestOutcome.PASS.isVerifiedPass).isTrue()
        TestOutcome.entries.filter { it != TestOutcome.PASS }.forEach {
            assertThat(it.isVerifiedPass).isFalse()
        }
    }

    @Test
    fun unavailableAndUnsupportedBlockAutomation() {
        assertThat(TestOutcome.UNAVAILABLE.blocksAutomation).isTrue()
        assertThat(TestOutcome.UNSUPPORTED.blocksAutomation).isTrue()
        assertThat(TestOutcome.PASS.blocksAutomation).isFalse()
        assertThat(TestOutcome.MANUAL_DEVICE_REQUIRED.blocksAutomation).isFalse()
    }

    @Test
    fun rollupNeverTurnsUnavailableIntoPass() {
        assertThat(TestOutcome.rollup(listOf(TestOutcome.PASS, TestOutcome.UNAVAILABLE)))
            .isEqualTo(TestOutcome.UNAVAILABLE)
        assertThat(TestOutcome.rollup(listOf(TestOutcome.PASS, TestOutcome.UNSUPPORTED)))
            .isEqualTo(TestOutcome.UNSUPPORTED)
    }

    @Test
    fun rollupPrefersFailureOverEverythingElse() {
        assertThat(TestOutcome.rollup(listOf(TestOutcome.PASS, TestOutcome.FAIL, TestOutcome.UNAVAILABLE)))
            .isEqualTo(TestOutcome.FAIL)
        assertThat(TestOutcome.rollup(listOf(TestOutcome.UNAVAILABLE, TestOutcome.MANUAL_DEVICE_REQUIRED)))
            .isEqualTo(TestOutcome.MANUAL_DEVICE_REQUIRED)
    }

    @Test
    fun rollupOfNothingIsFailureBecauseNothingRan() {
        assertThat(TestOutcome.rollup(emptyList())).isEqualTo(TestOutcome.FAIL)
    }

    @Test
    fun rollupOfAllPassesIsPass() {
        assertThat(TestOutcome.rollup(List(7) { TestOutcome.PASS })).isEqualTo(TestOutcome.PASS)
    }

    @Test
    fun wireValuesRoundTrip() {
        TestOutcome.entries.forEach { assertThat(TestOutcome.fromWire(it.wire)).isEqualTo(it) }
        ShizukuState.entries.forEach { assertThat(ShizukuState.fromWire(it.wire)).isEqualTo(it) }
        // Unknown wire values are rejected, never defaulted to PASS.
        assertThat(TestOutcome.fromWire("nope")).isNull()
        assertThat(TestOutcome.fromWire(null)).isNull()
    }
}

class ShizukuStateTest {

    @Test
    fun classifyDistinguishesAbsentFromPermissionless() {
        assertThat(ShizukuProbe.classify(false, false, false)).isEqualTo(ShizukuState.SHIZUKU_UNAVAILABLE)
        // Installed and running, but no permission: a distinct, honest state.
        assertThat(ShizukuProbe.classify(true, false, true)).isEqualTo(ShizukuState.SHIZUKU_AVAILABLE)
        assertThat(ShizukuProbe.classify(true, true, true)).isEqualTo(ShizukuState.SHIZUKU_READY)
    }

    @Test
    fun onlyReadyCountsAsUsable() {
        assertThat(ShizukuState.SHIZUKU_READY.isUsable).isTrue()
        ShizukuState.entries.filter { it != ShizukuState.SHIZUKU_READY }.forEach {
            assertThat(it.isUsable).isFalse()
        }
    }

    @Test
    fun descriptionDoesNotClaimUiAutomation() {
        ShizukuState.entries.forEach { state ->
            val text = ShizukuProbe.describe(state).lowercase()
            assertThat(text).doesNotContain("ui automation")
            assertThat(text).doesNotContain("automated")
        }
        assertThat(ShizukuProbe.describe(ShizukuState.SHIZUKU_READY)).contains("privileged transport")
    }
}
