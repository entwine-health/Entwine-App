// Crisis screen — deliberately OFF-brand (hardcoded red) so it never reads as
// a normal screen. ACTION_DIAL only: the dialer is the R-UXA-08 second step;
// targets come from the last session.ready, per-country (R-CRE-02, R-LNG-06).
package health.entwine.lucy.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import health.entwine.lucy.AppViewModel
import health.entwine.lucy.BAKED_TARGETS
import health.entwine.lucy.R
import health.entwine.lucy.proto.CrisisTarget
import health.entwine.lucy.state.Event

private val CrisisRed = Color(0xFF7F1D1D)

@Composable
internal fun CrisisScreen(targets: List<CrisisTarget>?, vm: AppViewModel) {
    val ctx = LocalContext.current
    val list = targets ?: BAKED_TARGETS
    Column(
        Modifier.fillMaxSize().background(CrisisRed).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.crisis_headline),
            fontSize = 32.sp,
            lineHeight = 42.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        list.forEach { t ->
            Button(
                onClick = {
                    // ACTION_DIAL: user confirms the call — no CALL_PHONE permission
                    // (R-CRE-02); the dialer itself is the R-UXA-08 second step.
                    ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${t.phone}")))
                },
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
                    .heightIn(min = PdDim.target),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
            ) {
                Text(
                    "${crisisLabel(t.labelKey)} · ${t.phone}",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CrisisRed,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        OutlinedButton(
            onClick = { vm.dispatch(Event.CrisisContinueTap) },
            modifier = Modifier.fillMaxWidth().heightIn(min = PdDim.target),
        ) {
            Text(stringResource(R.string.crisis_continue), fontSize = 18.sp, color = Color.White)
        }
    }
}

@Composable
private fun crisisLabel(key: String): String = when (key) {
    "crisis_eran" -> stringResource(R.string.crisis_eran)
    "crisis_emergency" -> stringResource(R.string.crisis_emergency)
    else -> key
}
