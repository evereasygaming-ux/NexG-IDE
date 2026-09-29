package com.nexg.ide

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.nexg.ide.ui.nav.NexGNavHost
import com.nexg.ide.ui.theme.NexGTheme

/**
 * The only Activity in Phase 1.
 *
 * Kept thin on purpose (PLAN.MD 4.6): it sets up edge-to-edge and hands off to
 * Compose. No business logic, no ViewModel lookup, no state. If this file ever
 * needs a `when` over a screen, the navigation logic has leaked out of
 * `ui/nav` and should be moved back.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Called before setContent so the first Compose frame is already
        // drawing under a transparent system bar. Insets are then consumed by
        // Scaffold/WindowInsets rather than by hard-coded offsets.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            NexGTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    NexGNavHost()
                }
            }
        }
    }
}
