package com.nexg.ide.testing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nexg.ide.BuildConfig
import java.io.File

/**
 * The debug-only transport between the Termux CLI and the harness.
 *
 * Why a broadcast plus a file, and not a socket: the CLI runs under Shizuku's
 * shell, which can start an activity or send a broadcast to an exported
 * component, but it cannot read this app's private storage. So the *request*
 * travels as a broadcast, and the *reply* is written to the app's external
 * files directory, which the shell can read. No listening socket exists, so
 * there is no service to discover, no port to squat on, and nothing that
 * answers unsolicited traffic.
 *
 * Safety properties, in order of importance:
 *  - The component lives in `app/src/debug/` and is declared in the debug
 *    manifest only, so it does not exist in a release APK at all.
 *  - The export is guarded by a per-install token ([HarnessToken]), so another
 *    app on the device cannot drive the harness even though the receiver is
 *    reachable. Without it, an export is enough to make the app *act*.
 *  - The reply is a fixed filename, so a hostile sender cannot choose a path.
 *  - [TestController.execute] is only reached with a request that presented the
 *    token *and* passed the allowlist in [TestRequestParser].
 *  - The reply carries no credentials: the harness only ever reports presence.
 */
class NexGTestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REQUEST) return
        // A release build has no receiver, but assert it anyway: a harness that
        // answers on a production build is a security bug, not a feature.
        if (!BuildConfig.TEST_HARNESS || !BuildConfig.DEBUG) {
            Log.w(TAG, "test request ignored: not a debug build")
            return
        }
        val app = context.applicationContext
        val controller = registry ?: TestController(app)
        val response = NexGTestBridge.handle(
            rawRequest = intent.getStringExtra(EXTRA_REQUEST),
            providedToken = intent.getStringExtra(EXTRA_TOKEN),
            expectedToken = HarnessToken.currentOrMint(app),
            dispatch = controller::execute,
        )
        writeReply(app, response)
    }

    companion object {
        private const val TAG = "NexGTest"

        const val ACTION_REQUEST = "com.nexg.ide.TEST_REQUEST"
        const val EXTRA_REQUEST = "request"

        /**
         * Per-install authorization token. See [HarnessToken] for why a token
         * rather than a permission: the shell cannot hold a signature permission,
         * and the receiver must stay exported for the `rish` transport.
         */
        const val EXTRA_TOKEN = "token"

        /** Fixed, non-attacker-controlled reply filename. */
        const val REPLY_FILE = "nexg-test-reply.json"

        private const val DIRECTORY = "nexg-test"

        /**
         * Installed by the debug Application so the receiver can reach the live
         * editor. Null in a process where the editor never opened, which the
         * controller reports as UNAVAILABLE rather than as a failure.
         */
        @Volatile
        var registry: TestController? = null

        /**
         * Where the reply is written.
         *
         * `getExternalFilesDir` is app-specific and shell-readable, and needs no
         * permission on any API level this app supports.
         */
        fun replyFile(context: Context): File? =
            context.getExternalFilesDir(DIRECTORY)?.let { File(it, REPLY_FILE) }

        /** Writes the reply as JSON, replacing any previous one. */
        fun writeReply(context: Context, response: TestResponse) {
            val file = replyFile(context) ?: return
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(response.toJson().toString())
            }.onFailure { Log.w(TAG, "could not write test reply: ${it.javaClass.simpleName}") }
        }

        /** Reads a previous reply, for tests and for `nexg-test report`. */
        fun readReply(context: Context): String? =
            replyFile(context)?.let { runCatching { if (it.exists()) it.readText() else null }.getOrNull() }

        /** Removes the reply file, so a stale reply is never mistaken for fresh. */
        fun clearReply(context: Context) {
            runCatching { replyFile(context)?.delete() }
        }

        /**
         * The intent a caller (or Shizuku) should send.
         *
         * [token] is the per-install secret from [HarnessToken.currentOrMint]; a
         * request without it is rejected before parsing.
         */
        fun requestIntent(context: Context, requestJson: String, token: String): Intent =
            Intent(ACTION_REQUEST).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_REQUEST, requestJson)
                putExtra(EXTRA_TOKEN, token)
            }
    }
}
