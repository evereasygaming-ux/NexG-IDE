package com.nexg.ide.testing

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
// NOTE: the Maven group is `dev.rikka.shizuku` but the Java package is
// `rikka.shizuku` (no `dev.` prefix). Verified against the api-13.1.5.aar.
import rikka.shizuku.Shizuku

/**
 * Observes Shizuku. It never changes it.
 *
 * Constraints this class holds itself to, because the temptation to "fix" a
 * missing Shizuku by requesting something is exactly what a test harness must
 * not do:
 *
 *  - No permission requests. [Shizuku.pingBinder] and the permission check are
 *    read-only; the harness does not call `requestPermission`, so a user is
 *    never prompted by a test run.
 *  - No root, no `su`, no shell, no `rish`. Shizuku is used as a transport that
 *    the CLI in Termux may use; this side only *reports* whether it is there.
 *  - No secrets. Nothing readable from the credential store passes through here.
 *  - Failure is a reported state, never a thrown exception: a device with no
 *    Shizuku must still be able to answer `status`.
 */
object ShizukuProbe {

    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    /**
     * Classifies the current Shizuku state.
     *
     * @param binderAlive result of a cheap binder ping, injected so the state
     *   machine stays unit-testable without a device.
     * @param permissionGranted whether we hold Shizuku's permission.
     * @param installed whether the Shizuku package is present.
     */
    fun classify(
        binderAlive: Boolean,
        permissionGranted: Boolean,
        installed: Boolean,
    ): ShizukuState = when {
        !installed || !binderAlive -> ShizukuState.SHIZUKU_UNAVAILABLE
        permissionGranted -> ShizukuState.SHIZUKU_READY
        else -> ShizukuState.SHIZUKU_AVAILABLE
    }

    /** True when the Shizuku API classes this build compiled against exist at runtime. */
    fun apiSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP

    /** Live probe. Any surprise from the platform is reported, not thrown. */
    fun probe(context: Context): ShizukuState {
        if (!apiSupported()) return ShizukuState.UNSUPPORTED
        val installed = runCatching {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        }.getOrDefault(false)
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val permission = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)
        return classify(binderAlive, permission, installed)
    }

    /**
     * The Shizuku service uid, when Shizuku exposes one.
     *
     * This is a system fact, not a credential, so it is safe to report. It is
     * `null` whenever it cannot be read, rather than guessed.
     */
    fun uidOrNull(): Int? = runCatching {
        if (!Shizuku.pingBinder()) return null
        Shizuku.getUid()
    }.getOrNull()

    /**
     * A one-line human summary for `nexg-test status`.
     *
     * Deliberately says "privilege transport", not "UI automation": even a ready
     * Shizuku does not make touch behaviour verified.
     */
    fun describe(state: ShizukuState): String = when (state) {
        ShizukuState.SHIZUKU_READY -> "ready (privileged transport available; not a UI test framework)"
        ShizukuState.SHIZUKU_AVAILABLE -> "installed and running, permission not granted"
        ShizukuState.SHIZUKU_UNAVAILABLE -> "not installed or not running"
        ShizukuState.SHIZUKU_PERMISSION_DENIED -> "permission denied"
        ShizukuState.UNSUPPORTED -> "unsupported on this platform"
    }
}
