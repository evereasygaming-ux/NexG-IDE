package com.nexg.ide.testing

/**
 * The only verdicts a test result may carry.
 *
 * The whole point of this enum is that "I could not check" and "it works" are
 * different values, and no code path is allowed to turn the first into the
 * second. [isVerifiedPass] exists so a caller that wants to treat a result as a
 * success has to ask for it explicitly, and [blocksAutomation] exists so a
 * caller can tell "blocked by the environment" apart from "genuinely broken".
 */
enum class TestOutcome(val wire: String) {
    /** The behaviour was exercised and the assertion held. */
    PASS("PASS"),

    /** The behaviour was exercised and the assertion did not hold. */
    FAIL("FAIL"),

    /**
     * The test could not run: no device, Shizuku down, the app not installed,
     * the target screen absent, and so on. This is never a success, and it is
     * not a failure of the code under test either.
     */
    UNAVAILABLE("UNAVAILABLE"),

    /**
     * This build/device cannot support the test at all — for example a Shizuku
     * feature that does not exist in the API version this app compiles against.
     */
    UNSUPPORTED("UNSUPPORTED"),

    /**
     * The check requires a human at the phone: a real finger, the real IME, the
     * native copy menu. Automating it would be a lie, so it is declared instead
     * of simulated.
     */
    MANUAL_DEVICE_REQUIRED("MANUAL_DEVICE_REQUIRED"),
    ;

    /** True only for a real, executed, passing test. */
    val isVerifiedPass: Boolean get() = this == PASS

    /** True when the environment stopped the test from running at all. */
    val blocksAutomation: Boolean get() = this == UNAVAILABLE || this == UNSUPPORTED

    /** True when a human has to perform this check on the device. */
    val needsHuman: Boolean get() = this == MANUAL_DEVICE_REQUIRED

    override fun toString(): String = wire

    companion object {
        /** Parses a wire value; unknown input is rejected, never defaulted to PASS. */
        fun fromWire(value: String?): TestOutcome? = entries.firstOrNull { it.wire == value }

        /**
         * Collapses a list of outcomes into one roll-up verdict.
         *
         * Order of precedence, most severe first: any FAIL makes the run FAIL;
         * then anything a human must do; then anything the environment blocked;
         * PASS only when every item actually passed. An empty list is FAIL,
         * because "no tests ran" must never read as success.
         */
        fun rollup(outcomes: List<TestOutcome>): TestOutcome {
            if (outcomes.isEmpty()) return FAIL
            return when {
                outcomes.any { it == FAIL } -> FAIL
                outcomes.any { it == MANUAL_DEVICE_REQUIRED } -> MANUAL_DEVICE_REQUIRED
                outcomes.any { it == UNSUPPORTED } -> UNSUPPORTED
                outcomes.any { it == UNAVAILABLE } -> UNAVAILABLE
                else -> PASS
            }
        }
    }
}

/**
 * Shizuku's state, as observed from inside the app.
 *
 * Shizuku is a privilege transport, not a UI-testing framework: even a perfect
 * [SHIZUKU_READY] says nothing about whether any touch test can run, because
 * that additionally needs a live binder, a granted permission and a device.
 */
enum class ShizukuState(val wire: String) {
    /** Binder is alive and the app holds the granted permission. */
    SHIZUKU_READY("SHIZUKU_READY"),

    /** Shizuku is installed and the binder is alive, but we lack permission. */
    SHIZUKU_AVAILABLE("SHIZUKU_AVAILABLE"),

    /** Shizuku is not installed, or is installed but not running. */
    SHIZUKU_UNAVAILABLE("SHIZUKU_UNAVAILABLE"),

    /** Shizuku is present and the user explicitly refused the permission. */
    SHIZUKU_PERMISSION_DENIED("SHIZUKU_PERMISSION_DENIED"),

    /** This build cannot talk to Shizuku at all (no API on this platform). */
    UNSUPPORTED("UNSUPPORTED"),
    ;

    /** True only when a privileged transport is actually usable right now. */
    val isUsable: Boolean get() = this == SHIZUKU_READY

    override fun toString(): String = wire

    companion object {
        fun fromWire(value: String?): ShizukuState? = entries.firstOrNull { it.wire == value }
    }
}
