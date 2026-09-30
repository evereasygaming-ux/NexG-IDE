package com.nexg.ide.testing

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The per-install authorization token for the exported debug receiver.
 *
 * Why this exists: [NexGTestReceiver] has to be `exported` so that Termux can
 * reach it through Shizuku's shell, and a signature-level permission is not an
 * option because the shell cannot hold one. Without an extra check, *any* app
 * installed on the device could send `am broadcast` to the receiver and make the
 * app perform allowlisted actions — for example overwriting the file the owner
 * currently has open with `setTestDocument`. Such an app could not read the
 * reply (that lives in this app's own `Android/data` directory), but the ability
 * to *act* is the part that matters. The token closes it.
 *
 * Lifecycle — deliberately per-install, not per-process:
 *  - Minted once with [SecureRandom], stored in app-private `SharedPreferences`.
 *  - Survives process restarts, so a test run is not broken by Android killing
 *    the app between two requests.
 *  - Regenerated only when those preferences are wiped, i.e. on reinstall. Both
 *    the preferences and the mirrored file below live under the app's data
 *    directories, so uninstalling removes both together.
 *
 * Distribution to the CLI: the shell cannot read app-private storage, so the
 * token is mirrored to [tokenFile], which the shell can read. That mirror is the
 * whole reason another app cannot also read it on API 30+: scoped storage denies
 * one app access to another's `Android/data` directory. On API 26-29 the
 * guarantee is weaker, because those versions permit that read; this is recorded
 * in the generated report rather than glossed over.
 */
object HarnessToken {

    /** 128 bits of entropy. */
    private const val TOKEN_BYTES = 16

    /** Hex-encoded token length. */
    const val TOKEN_CHARS = TOKEN_BYTES * 2

    private const val PREFS_NAME = "nexg-test-harness"
    private const val PREFS_KEY_TOKEN = "auth-token"

    private const val DIRECTORY = "nexg-test"

    /** Mirrored, shell-readable copy of the token. */
    const val TOKEN_FILE = "nexg-test-token.txt"

    private const val HEX_DIGITS = "0123456789abcdef"

    /**
     * A fresh 128-bit token as lowercase hex.
     *
     * [SecureRandom] is injected rather than hard-coded so tests can pin the
     * generator; production callers use the default platform instance.
     */
    fun newToken(random: SecureRandom = SecureRandom()): String {
        val raw = ByteArray(TOKEN_BYTES)
        random.nextBytes(raw)
        val out = StringBuilder(TOKEN_CHARS)
        for (b in raw) {
            val v = b.toInt() and 0xFF
            out.append(HEX_DIGITS[v ushr 4])
            out.append(HEX_DIGITS[v and 0x0F])
        }
        return out.toString()
    }

    /**
     * Cheap shape check, so a nonsense value is reported as `MALFORMED_TOKEN`
     * rather than as a wrong token.
     */
    fun isWellFormed(token: String): Boolean {
        if (token.length != TOKEN_CHARS) return false
        return token.all { it in '0'..'9' || it in 'a'..'f' }
    }

    /**
     * Constant-time comparison, with null and empty rejected outright.
     *
     * [MessageDigest.isEqual] is used instead of `String.equals` so the
     * comparison does not leak the matching prefix length through timing.
     */
    fun matches(expected: String?, provided: String?): Boolean {
        if (expected == null || provided == null) return false
        if (!isWellFormed(expected) || !isWellFormed(provided)) return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            provided.toByteArray(Charsets.UTF_8),
        )
    }

    /** Where the shell-readable mirror lives. */
    fun tokenFile(context: Context): File? =
        context.getExternalFilesDir(DIRECTORY)?.let { File(it, TOKEN_FILE) }

    /**
     * The current token, minting and persisting one on first use.
     *
     * Safe to call on every request: the value is stable for the life of the
     * install, so the receiver and the CLI always agree.
     */
    fun currentOrMint(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(PREFS_KEY_TOKEN, null)
            ?.takeIf { isWellFormed(it) }
            ?.let { return it }
        val minted = newToken()
        prefs.edit().putString(PREFS_KEY_TOKEN, minted).apply()
        return minted
    }

    /**
     * Writes the shell-readable mirror.
     *
     * Best-effort by design: if external storage is unavailable the harness still
     * works for `nexg-test check` on the JVM, and only the on-device leg reports
     * UNAVAILABLE.
     */
    fun mirror(context: Context, token: String) {
        val file = tokenFile(context) ?: return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(token)
        }
    }

    /** The token the receiver will accept, also refreshing the mirror. */
    fun currentAndMirror(context: Context): String {
        val token = currentOrMint(context)
        mirror(context, token)
        return token
    }
}

/**
 * The authorization decision, as a pure function.
 *
 * Splitting it out of the receiver is what makes the policy testable: this
 * project has no Robolectric, so a rule buried in `onReceive` would be untestable,
 * whereas this can be exercised directly on the JVM.
 */
object HarnessAuth {

    const val MISSING_TOKEN = "MISSING_TOKEN"
    const val MALFORMED_TOKEN = "MALFORMED_TOKEN"
    const val WRONG_TOKEN = "WRONG_TOKEN"

    /** Reported to the caller for every rejection, so it learns nothing extra. */
    const val UNAUTHORIZED = "UNAUTHORIZED"

    sealed interface Verdict {
        data object Allow : Verdict
        data class Reject(val reason: String) : Verdict
    }

    fun authorize(expected: String?, provided: String?): Verdict {
        if (provided.isNullOrEmpty()) return Verdict.Reject(MISSING_TOKEN)
        if (!HarnessToken.isWellFormed(provided)) return Verdict.Reject(MALFORMED_TOKEN)
        if (!HarnessToken.matches(expected, provided)) return Verdict.Reject(WRONG_TOKEN)
        return Verdict.Allow
    }

    /** The reply sent for any rejection, with no hint about the real token. */
    fun rejectionResponse(command: String = "?") = TestResponse.rejected(
        command = command,
        code = UNAUTHORIZED,
        detail = "missing or invalid authorization token",
    )
}
