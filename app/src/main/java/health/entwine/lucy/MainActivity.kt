// Single activity (D-11). First run walks setup: mic permission, notifications,
// battery-optimization exemption (R-NOT-04) — then the conversation screen.
package health.entwine.lucy

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import health.entwine.lucy.state.Event
import health.entwine.lucy.ui.EntwineTheme
import health.entwine.lucy.ui.Root
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    // Grant result feeds the mic state (a denied mic must show a fix path, never a
    // dead recording); the battery-exemption prompt is deferred to post-enrollment
    // so it no longer fires over the unread invite screen.
    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { vm.refreshMicState() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // R-LNG-01: session language drives strings locale + layout direction,
            // live (language.updated recomposes — no activity recreate needed).
            val ui by vm.ui.collectAsState()
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
            LaunchedEffect(ui.enrolled) { if (ui.enrolled) askBatteryExemption() }
            CompositionLocalProvider(LocalContext provides localized) {
                EntwineTheme(ui.lang) { Root(vm) }
            }
        }
        permissions.launch(
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        )
        vm.openSession()
    }

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
