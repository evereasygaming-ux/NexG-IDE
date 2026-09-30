package com.nexg.ide.testing

import android.app.Activity
import android.os.Bundle

/**
 * A no-theme, no-history activity whose only job is to exist at install time.
 *
 * The test receiver is a `BroadcastReceiver`, and a receiver in a process that
 * has never been started has no `Context` graph to build a controller from. This
 * activity gives the debug variant a legitimate cold-start path that the CLI
 * can trigger with `am start`, so the first request after install produces a
 * structured answer instead of an unhandled-broadcast log line.
 *
 * It is also where the per-install token is minted and mirrored, because the
 * CLI cannot read app-private storage and the mirror is what lets the shell
 * learn the token at all. Running the app once is therefore the intended flow:
 * the CLI starts this activity, then reads the mirrored token.
 *
 * It is invisible (`Theme.NoDisplay`), leaves no trace in recents, and is not
 * exported, so it cannot be launched by anything except this app.
 */
class NexGTestBootActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            // Touch the harness so the controller is constructed once, in the
            // process that will answer requests.
            NexGTestReceiver.registry = TestController(applicationContext)
            // Mint on first run, otherwise reuse: the token is per-install, so a
            // restart must not invalidate a token the CLI is already holding.
            HarnessToken.currentAndMirror(applicationContext)
        }
        finish()
    }
}
