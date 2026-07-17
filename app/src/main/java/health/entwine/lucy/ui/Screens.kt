// Compose screens per SDD_App_State_Matrix — one visible state at all times
// (R-LOOP-07), PD-accessible sizing per SRS v1.5: ≥76dp targets + 190dp round
// talk control (R-UXA-06), up-event-only single taps — Compose click semantics,
// no gesture-only paths (R-UXA-07), destructive-tap confirm (R-UXA-08), live
// mic meter (R-UXA-10), OFF/Shifting enlarged mode (R-UXA-14), heightIn (not
// fixed height) so 200% font scaling grows controls (R-UXA-13). Full RTL.
// Copy = placeholders pending Q-23 (CPO).
package health.entwine.lucy.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import health.entwine.lucy.AppViewModel
import health.entwine.lucy.R
import health.entwine.lucy.UiSlice
import health.entwine.lucy.state.AppState
import health.entwine.lucy.state.Event

@Composable
fun Root(vm: AppViewModel) {
    val ui by vm.ui.collectAsState()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            !ui.enrolled -> EnrollScreen(vm)
            ui.state is AppState.Crisis ->
                CrisisScreen((ui.state as AppState.Crisis).targets.ifEmpty { null }, vm)
            else -> ConversationScreen(ui, vm)
        }
    }
}

@Composable
private fun EnrollScreen(vm: AppViewModel) {
    // Invite-only (R-ENR-05): one operator-provided code — no phone/email.
    var code by rememberSaveable { mutableStateOf("") }
    var failed by rememberSaveable { mutableStateOf(false) }
    var busy by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painterResource(R.drawable.entwine_wordmark),
            contentDescription = null, // decorative brand mark
            modifier = Modifier.fillMaxWidth(0.72f),
        )
        Spacer(Modifier.height(32.dp))
        Text(stringResource(R.string.enroll_title), fontSize = 26.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = code, onValueChange = { code = it },
            label = { Text(stringResource(R.string.enroll_code), fontSize = 18.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        if (failed) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.enroll_failed),
                color = MaterialTheme.colorScheme.error, fontSize = 18.sp,
            )
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                busy = true
                vm.enroll(code) { ok -> busy = false; failed = !ok }
            },
            enabled = !busy && code.count(Char::isDigit) >= 4,
            modifier = Modifier.fillMaxWidth().heightIn(min = PdDim.target),
        ) { Text(stringResource(R.string.enroll_go), fontSize = 22.sp) }
    }
}

@Composable
private fun ConversationScreen(ui: UiSlice, vm: AppViewModel) {
    var composed by rememberSaveable { mutableStateOf("") }
    var showFeedback by rememberSaveable { mutableStateOf(false) }
    var endArm by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(
                onClick = { showFeedback = true },
                modifier = Modifier.heightIn(min = PdDim.target),
            ) {
                Text(stringResource(R.string.btn_feedback), fontSize = 16.sp)
            }
        }
        if (showFeedback) FeedbackDialog(vm) { showFeedback = false }

        // Transcript
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = false) {
            items(ui.transcript) { (who, text) ->
                val mine = who == "me"
                Text(
                    text,
                    fontSize = 22.sp,
                    color = if (mine) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                )
            }
            if (ui.partialReply.isNotBlank()) {
                item { Text(ui.partialReply, fontSize = 22.sp) }
            }
        }

        ui.motorChip?.let {
            Text(
                stringResource(R.string.motor_logged), fontSize = 16.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (ui.updateNeeded) {
            Text(
                stringResource(R.string.update_needed), fontSize = 18.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }
        ui.errorKey?.let { ErrorBanner(it) }
        if (ui.state is AppState.Offline) OfflineBanner(vm)
        if (ui.capSuggested) {
            Text(stringResource(R.string.cap_suggest), fontSize = 18.sp)
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
                OutlinedButton(
                    onClick = { vm.motorTap(state) },
                    modifier = Modifier.weight(1f).heightIn(min = PdDim.target),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = tint),
                    border = BorderStroke(2.dp, tint),
                ) { Text(stringResource(label), fontSize = 18.sp, textAlign = TextAlign.Center) }
            }
        }

        // Live input meter — "she hears me" feedback for soft voices (R-UXA-10).
        if (ui.state is AppState.Recording) {
            Box(
                Modifier.fillMaxWidth().height(10.dp).padding(horizontal = 4.dp)
                    .background(EntwineBorder)
            ) {
                Box(
                    Modifier.fillMaxWidth(ui.micLevel.coerceIn(0f, 1f)).fillMaxHeight()
                        .background(EntwineCyan)
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        // Primary control: round, ≥180dp (R-UXA-06), fires on up-event with
        // slide-off abort — Compose Button semantics (R-UXA-07).
        val (label, action) = when (ui.state) {
            AppState.Recording ->
                R.string.btn_stop_talking to { vm.dispatch(Event.TapStop) }
            AppState.Responding ->
                R.string.btn_stop_playback to { vm.dispatch(Event.TapStop) }
            AppState.Processing -> R.string.btn_thinking to {}
            else -> R.string.btn_talk to { vm.tapTalk() }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Button(
                onClick = action,
                shape = CircleShape,
                modifier = Modifier.size(PdDim.talkDiameter),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (ui.state is AppState.Recording) EntwineCoral
                    else MaterialTheme.colorScheme.primary
                ),
            ) {
                Text(
                    stringResource(label), fontSize = 26.sp,
                    textAlign = TextAlign.Center, maxLines = 2,
                )
            }
        }

        // Always-visible text lane (R-LOOP-04) — the tap/type fallback that
        // R-UXA-09 requires alongside every voice path.
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
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
        // auto-disarms after 5 s (R-UXA-08; dyskinesia mis-tap containment).
        if (endArm) {
            LaunchedEffect(Unit) { delay(5_000); endArm = false }
            Text(
                stringResource(R.string.end_confirm_q), fontSize = 18.sp,
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
        } else {
            OutlinedButton(
                onClick = { endArm = true },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    .heightIn(min = PdDim.target),
            ) { Text(stringResource(R.string.btn_end_session), fontSize = 18.sp) }
        }
    }
}

@Composable
private fun FeedbackDialog(vm: AppViewModel, onClose: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var recording by rememberSaveable { mutableStateOf(false) }
    var sent by rememberSaveable { mutableStateOf<Boolean?>(null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { vm.stopFeedbackRecording(); onClose() },
        title = { Text(stringResource(R.string.fb_title), fontSize = 22.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text(stringResource(R.string.fb_text_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        recording = !recording
                        if (recording) vm.startFeedbackRecording()
                        else vm.stopFeedbackRecording()
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = PdDim.target),
                ) {
                    Text(
                        stringResource(
                            if (recording) R.string.fb_stop_rec else R.string.fb_record
                        ),
                        fontSize = 18.sp,
                    )
                }
                sent?.let {
                    Text(
                        stringResource(if (it) R.string.fb_sent else R.string.fb_fail),
                        fontSize = 16.sp,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { vm.sendFeedback(text) { ok -> sent = ok; if (ok) onClose() } },
                modifier = Modifier.heightIn(min = PdDim.target),
            ) {
                Text(stringResource(R.string.fb_send), fontSize = 18.sp)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = { vm.stopFeedbackRecording(); onClose() },
                modifier = Modifier.heightIn(min = PdDim.target),
            ) {
                Text(stringResource(R.string.fb_cancel), fontSize = 18.sp)
            }
        },
    )
}

@Composable
private fun CrisisScreen(
    targets: List<health.entwine.lucy.proto.CrisisTarget>?, vm: AppViewModel,
) {
    val ctx = LocalContext.current
    val list = targets ?: health.entwine.lucy.BAKED_TARGETS
    Column(
        Modifier.fillMaxSize().background(Color(0xFF7F1D1D)).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.crisis_headline),
            fontSize = 30.sp, color = Color.White,
        )
        Spacer(Modifier.height(28.dp))
        list.forEach { t ->
            Button(
                onClick = {
                    // ACTION_DIAL: user confirms the call — no CALL_PHONE permission
                    // (R-CRE-02); the dialer itself is the R-UXA-08 second step.
                    ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${t.phone}")))
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    .heightIn(min = PdDim.target),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
            ) {
                Text(
                    "${crisisLabel(t.labelKey)} · ${t.phone}",
                    fontSize = 24.sp, color = Color(0xFF7F1D1D),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        OutlinedButton(
            onClick = { vm.dispatch(Event.CrisisContinueTap) },
            modifier = Modifier.fillMaxWidth().heightIn(min = PdDim.target),
        ) { Text(stringResource(R.string.crisis_continue), fontSize = 18.sp, color = Color.White) }
    }
}

@Composable
private fun crisisLabel(key: String): String = when (key) {
    "crisis_eran" -> stringResource(R.string.crisis_eran)
    "crisis_emergency" -> stringResource(R.string.crisis_emergency)
    else -> key
}

@Composable
private fun ErrorBanner(key: String) {
    // user_message_key → Q-23 copy table; placeholders until CPO strings land.
    val res = when (key) {
        "err_stt" -> R.string.err_stt
        "err_llm" -> R.string.err_generic
        "err_resync" -> R.string.err_generic
        else -> R.string.err_generic
    }
    Text(
        stringResource(res), fontSize = 18.sp,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

@Composable
private fun OfflineBanner(vm: AppViewModel) {
    Box(
        Modifier.fillMaxWidth().background(EntwineBorder).padding(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.offline_copy), fontSize = 18.sp, color = Color.White)
            OutlinedButton(
                onClick = { vm.openSession() },
                modifier = Modifier.heightIn(min = PdDim.target),
            ) {
                Text(stringResource(R.string.btn_retry), color = Color.White)
            }
        }
    }
}
