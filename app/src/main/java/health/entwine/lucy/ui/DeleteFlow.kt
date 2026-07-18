// In-app data deletion (R-DEL-04, 2026-07-18): full-screen confirm — the
// heaviest action in the app gets the heaviest gate. Reached only through the
// end-session confirm cluster (R-UXA-08 second-step separation); copy states
// exactly what is deleted and that the purge completes within days.
package health.entwine.lucy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import health.entwine.lucy.AppViewModel
import health.entwine.lucy.R

@Composable
fun DeleteConfirmScreen(vm: AppViewModel, onCancel: () -> Unit) {
    var busy by rememberSaveable { mutableStateOf(false) }
    var failed by rememberSaveable { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                stringResource(R.string.delete_title),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            Text(
                stringResource(R.string.delete_copy),
                fontSize = 20.sp,
                lineHeight = 30.sp,
                color = Color(0xFFD9D9DE),
                textAlign = TextAlign.Center,
            )
            if (failed) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.delete_failed),
                    fontSize = 18.sp,
                    color = EntwineCoral,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = {
                    busy = true
                    failed = false
                    vm.deleteAccount { ok -> busy = false; failed = !ok }
                },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = EntwineCoral),
                modifier = Modifier.fillMaxWidth().heightIn(min = PdDim.target),
            ) {
                Text(
                    stringResource(R.string.delete_confirm),
                    fontSize = 20.sp,
                    color = Color.Black,
                )
            }
            Spacer(Modifier.height(PdDim.targetGap))
            OutlinedButton(
                onClick = onCancel,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = PdDim.target),
            ) { Text(stringResource(R.string.delete_cancel), fontSize = 20.sp) }
        }
    }
}
