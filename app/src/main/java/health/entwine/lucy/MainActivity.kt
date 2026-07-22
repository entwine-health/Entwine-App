// Single activity (D-11). First run walks setup: mic permission, notifications,
// battery-optimization exemption (R-NOT-04) — then the conversation screen.
package health.entwine.lucy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import health.entwine.lucy.ui.EntwineCyan
import health.entwine.lucy.state.Event
import health.entwine.lucy.ui.EntwineTheme
import health.entwine.lucy.ui.Root
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    // Grant result feeds the mic state (a denied mic must show a fix path, never a
    // dead recording); the battery-exemption prompt is deferred to post-enrollment
    // so it no longer fires over the unread invite screen.
    // FB-20f: a one-shot callback chained after the runtime-permission dialogs so
    // the first-run sequence can continue (→ battery exemption → open the gate).
    private var onPermsResult: (() -> Unit)? = null
    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        vm.refreshMicState()
        onPermsResult?.invoke()
        onPermsResult = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // R-LNG-01: session language drives strings locale + layout direction,
            // live (language.updated recomposes — no activity recreate needed).
            val ui by vm.ui.collectAsState()
            // #26g: End conversation → server saves + closes → ViewModel emits exitApp →
            // finish the activity (a benign "ended"; reopening starts a fresh session).
            LaunchedEffect(Unit) { vm.exitApp.collect { finish() } }
            val base = LocalContext.current
            val localized = remember(ui.lang) {
                val cfg = Configuration(base.resources.configuration)
                cfg.setLocale(Locale(ui.lang))
                base.createConfigurationContext(cfg)
            }
            // R-NOT-04: ask for the battery exemption only once the user is
            // enrolled — nudges (its only purpose) are meaningless pre-enrollment,
            // and the system screen must not ambush a first-run user. Re-fires are
            // harmless: askBatteryExemption() no-ops once already exempt.
            // FB-20f: on a fresh install request EVERY permission up front — before
            // the invite screen — so the mic / notification / battery prompts never
            // surface at random times mid-conversation. An enrolled or already-
            // granted device skips straight through.
            // FB-20f field re-test 2026-07-20: the old predicate (ui.enrolled ||
            // micGranted()) opened the gate whenever mic was already granted OR the
            // device was enrolled, so notification + battery prompts were skipped
            // here and surfaced later "at random times". Gate on the first-run
            // sequence having RUN, so ALL prompts are front-loaded before the invite
            // screen. Self-terminating: granted runtime perms re-show no dialog and
            // askBatteryExemption() no-ops when already exempt (fully-permissioned
            // users see one SetupScreen frame).
            // ponytail: re-nags on every cold start only for a user who keeps
            // declining battery-opt. Upgrade: persist a `firstRunDone` bool via
            // Store's Keystore-prefs (Store.kt deviceToken pattern) and gate on it.
            var permsGate by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { firstRunPermissions { permsGate = true } }
            CompositionLocalProvider(LocalContext provides localized) {
                EntwineTheme(ui.lang) {
                    if (permsGate) Root(vm) else SetupScreen()
                }
            }
        }
        vm.openSession()
    }

    /** First-run permission sequence (FB-20f): the mic + notification dialog, then
     *  the battery-exemption screen, then open the gate to the invite screen — so
     *  everything is asked once, up front, never at random times mid-use. */
    private fun firstRunPermissions(onDone: () -> Unit) {
        onPermsResult = {
            askBatteryExemption() // OEM-killer guard (R-NOT-04), still pre-enrollment
            onDone()
        }
        permissions.launch(
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        )
    }

    private fun micGranted(): Boolean = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    override fun onResume() {
        super.onResume()
        vm.refreshMicState() // catch a mic grant/revoke the user made in Settings
    }

    /** Guided battery exemption (R-NOT-04): OEM killers are the top nudge threat. */
    private fun askBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                )
            )
        }
    }

    override fun onStop() {
        super.onStop()
        vm.dispatch(Event.AppBackground) // never record in background (matrix)
    }
}

/** Brief holding screen shown behind the first-run permission dialogs (FB-20f),
 *  before the invite screen appears. */
@Composable
private fun SetupScreen() {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = EntwineCyan)
        }
    }
}
