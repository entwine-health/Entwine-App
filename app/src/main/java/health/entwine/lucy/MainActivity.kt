// Single activity (D-11). First run walks setup: mic permission, notifications,
// battery-optimization exemption (R-NOT-04) — then the conversation screen.
package health.entwine.lucy

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import health.entwine.lucy.state.Event
import health.entwine.lucy.ui.EntwineTheme
import health.entwine.lucy.ui.Root

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { askBatteryExemption() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { EntwineTheme { Root(vm) } }
        permissions.launch(
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        )
        vm.openSession()
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
