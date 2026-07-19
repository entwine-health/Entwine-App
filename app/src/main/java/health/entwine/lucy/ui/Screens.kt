// Root dispatcher + the conversation screen (2026-07-18 UI round — split from
// the former single-file Screens.kt into TalkOrb/Transcript/Banners/Enroll/
// Crisis/Feedback/DeleteFlow). One visible state at all times (R-LOOP-07);
// geometry is FIXED (R-UXA-14, PdDim); up-event single taps only (R-UXA-07);
// destructive taps gated (R-UXA-08); the orb ring is the live input meter
// (R-UXA-10); heightIn (not fixed height) so 200% font scaling grows controls
// (R-UXA-13). Direction follows the session language (R-UXA-19 as amended).
package health.entwine.lucy.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import health.entwine.lucy.AppViewModel
import health.entwine.lucy.R
import health.entwine.lucy.UiSlice
import health.entwine.lucy.state.AppState
import health.entwine.lucy.state.Event
import kotlinx.coroutines.delay

/** Gender-correct motor label (FB-18). Hebrew has grammatical gender
 *  (מרגיש/מרגישה, תקוע/תקועה); English does not, so non-Hebrew and unknown-gender
 *  fall back to the base (neutral/slash) resource. SHIFTING (משתנה) is invariant. */
private fun motorLabelRes(state: String, base: Int, lang: String, gender: String?): Int {
    if (lang != "he") return base
    return when {
        state == "ON" && gender == "m" -> R.string.motor_on_m
        state == "ON" && gender == "f" -> R.string.motor_on_f
        state == "OFF" && gender == "m" -> R.string.motor_off_m
        state == "OFF" && gender == "f" -> R.string.motor_off_f
        else -> base
    }
}

@Composable
fun Root(vm: AppViewModel) {
    val ui by vm.ui.collectAsState()
    var showDelete by rememberSaveable { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            !ui.enrolled -> EnrollScreen(vm, deletedDone = ui.deletedDone)
            showDelete -> DeleteConfirmScreen(vm, onCancel = { showDelete = false })
            ui.state is AppState.Crisis ->
                CrisisScreen((ui.state as AppState.Crisis).targets.ifEmpty { null }, vm)
            else -> ConversationScreen(ui, vm, onDeleteRequest = { showDelete = true })
        }
    }
}

@Composable
private fun ConversationScreen(ui: UiSlice, vm: AppViewModel, onDeleteRequest: () -> Unit) {
    var composed by rememberSaveable { mutableStateOf("") }
    var showFeedback by rememberSaveable { mutableStateOf(false) }
    var endArm by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // Top strip: feedback entry + the transient motor acknowledgement.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ui.motorChip?.let {
                Text(
                    stringResource(R.string.motor_logged),
                    fontSize = 16.sp,
                    color = EntwineCyan,
                )
            } ?: Spacer(Modifier.size(1.dp))
            OutlinedButton(
                onClick = { showFeedback = true },
                modifier = Modifier.heightIn(min = PdDim.target),
            ) { Text(stringResource(R.string.btn_feedback), fontSize = 16.sp) }
        }
        if (showFeedback) FeedbackDialog(vm) { showFeedback = false }

        TranscriptList(
            ui.transcript,
            ui.partialReply,
            Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp),
        )

        if (ui.updateNeeded) {
            NoticeBanner(stringResource(R.string.update_needed), edge = EntwineYellow)
        }
        ui.errorKey?.let { ErrorBanner(it) }
        if (ui.state is AppState.Offline) OfflineBanner(onRetry = { vm.openSession() })
        if (ui.micDenied) MicDeniedBanner(onEnable = { vm.openAppSettings() })
        if (ui.capSuggested) {
            NoticeBanner(stringResource(R.string.cap_suggest), edge = EntwineCyan)
        }

        // Motor-state row — enabled in every state except crisis/closing (I1).
        // Colors follow the website's ON/TRANSITION/OFF semantic: cyan/yellow/coral.
        // Row-gap exception to the 50dp spacing rule: R-UXA-06 rationale (3
        // coequal color-differentiated states; Q-07 validates).
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(PdDim.rowGap),
        ) {
            listOf(
                Triple("ON", R.string.motor_on, EntwineCyan),
                Triple("SHIFTING", R.string.motor_shifting, EntwineYellow),
                Triple("OFF", R.string.motor_off, EntwineCoral),
            ).forEach { (state, label, tint) ->
                // FB-19c: the last-reported state stays filled with a ✓ so the user
                // can see "you are here" all session, not just a transient flash.
                val selected = ui.motorState == state
                // FB-18: gender-correct Hebrew wording (מרגיש/מרגישה, תקוע/תקועה),
                // matching the gendered voice; English has no grammatical gender so
                // it keeps the base label, and unknown gender keeps the slash form.
                val labelRes = motorLabelRes(state, label, ui.lang, ui.gender)
                OutlinedButton(
                    onClick = { vm.motorTap(state) },
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.weight(1f).heightIn(min = PdDim.target),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (selected) tint else Color.Transparent,
                        contentColor = if (selected) Color.Black else tint,
                    ),
                    border = if (selected) null else BorderStroke(2.dp, tint),
                ) {
                    Text(
                        (if (selected) "✓ " else "") + stringResource(labelRes),
                        fontSize = 18.sp,
                        lineHeight = 24.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // The orb: primary control AND state display in one fixed circle
        // (R-UXA-06 ≥180dp; the ring doubles as the R-UXA-10 level meter).
        val (label, action) = when {
            // Server-initiated greeting (Session-0): she holds the floor while the
            // state stays IdleReady — show she's speaking and DON'T let a tap start
            // recording over her (a first-run tap used to cut off the hello).
            ui.lucySpeaking && ui.state is AppState.IdleReady ->
                R.string.lucy_speaking to {}
            ui.state is AppState.Recording ->
                R.string.btn_stop_talking to { vm.dispatch(Event.TapStop) }
            ui.state is AppState.Responding ->
                R.string.btn_stop_playback to { vm.dispatch(Event.TapStop) }
            ui.state is AppState.Processing -> R.string.btn_thinking to {}
            // Offline: honest label + the tap retries the connection (was a silent no-op).
            ui.state is AppState.Offline ->
                R.string.btn_offline to { vm.openSession() }
            else -> R.string.btn_talk to { vm.tapTalk() }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TalkOrb(
                    state = ui.state,
                    micLevel = ui.micLevel,
                    lucySpeaking = ui.lucySpeaking,
                    label = stringResource(label),
                    onTap = action,
                )
                // (The server-speech caption moved INTO the orb label above — during
                // the greeting the orb itself now reads "לוסי מדברת…", no duplicate.)
            }
        }

        // Always-visible text lane (R-LOOP-04) — the tap/type fallback that
        // R-UXA-09 requires alongside every voice path.
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = composed,
                onValueChange = { composed = it },
                modifier = Modifier.weight(1f),
                enabled = ui.state is AppState.IdleReady,
            )
            Spacer(Modifier.size(8.dp))
            Button(
                onClick = { vm.sendText(composed); composed = "" },
                enabled = ui.state is AppState.IdleReady && composed.isNotBlank(),
                modifier = Modifier.heightIn(min = PdDim.target),
            ) { Text(stringResource(R.string.btn_send), fontSize = 18.sp) }
        }

        // End-session: destructive tap → separated second-tap confirm that
        // auto-disarms after 5 s (R-UXA-08). The armed cluster is also the only
        // road to full data deletion (R-DEL-04) — destructive actions live
        // together, behind the same deliberate first tap.
        if (endArm) {
            LaunchedEffect(Unit) { delay(5_000); endArm = false }
            Text(
                stringResource(R.string.end_confirm_q),
                fontSize = 18.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(PdDim.targetGap),
            ) {
                Button(
                    onClick = { endArm = false; vm.dispatch(Event.EndSessionTap) },
                    modifier = Modifier.weight(1f).heightIn(min = PdDim.target),
                    colors = ButtonDefaults.buttonColors(containerColor = EntwineCoral),
                ) { Text(stringResource(R.string.end_confirm_yes), fontSize = 18.sp) }
                OutlinedButton(
                    onClick = { endArm = false },
                    modifier = Modifier.weight(1f).heightIn(min = PdDim.target),
                ) { Text(stringResource(R.string.end_confirm_no), fontSize = 18.sp) }
            }
            OutlinedButton(
                onClick = { endArm = false; onDeleteRequest() },
                modifier = Modifier.fillMaxWidth().padding(top = PdDim.targetGap)
                    .heightIn(min = PdDim.target),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = EntwineCoral),
                border = BorderStroke(2.dp, EntwineCoral),
            ) { Text(stringResource(R.string.delete_entry), fontSize = 18.sp) }
        } else {
            OutlinedButton(
                onClick = { endArm = true },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    .heightIn(min = PdDim.target),
            ) { Text(stringResource(R.string.btn_end_session), fontSize = 18.sp) }
        }
    }
}
