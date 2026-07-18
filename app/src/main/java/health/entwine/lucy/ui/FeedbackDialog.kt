// In-app feedback (R-FBK-01/02): typed and/or recorded, off the live turn.
package health.entwine.lucy.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import health.entwine.lucy.AppViewModel
import health.entwine.lucy.R

@Composable
internal fun FeedbackDialog(vm: AppViewModel, onClose: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var recording by rememberSaveable { mutableStateOf(false) }
    var sent by rememberSaveable { mutableStateOf<Boolean?>(null) }
    AlertDialog(
        onDismissRequest = { vm.stopFeedbackRecording(); onClose() },
        title = { Text(stringResource(R.string.fb_title), fontSize = 22.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
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
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (recording) EntwineCoral else EntwineCyan
                    ),
                ) {
                    Text(
                        stringResource(
                            if (recording) R.string.fb_stop_rec else R.string.fb_record
                        ),
                        fontSize = 18.sp,
                    )
                }
                sent?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(if (it) R.string.fb_sent else R.string.fb_fail),
                        fontSize = 16.sp,
                        color = if (it) EntwineCyan else EntwineCoral,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { vm.sendFeedback(text) { ok -> sent = ok; if (ok) onClose() } },
                modifier = Modifier.heightIn(min = PdDim.target),
            ) { Text(stringResource(R.string.fb_send), fontSize = 18.sp) }
        },
        dismissButton = {
            OutlinedButton(
                onClick = { vm.stopFeedbackRecording(); onClose() },
                modifier = Modifier.heightIn(min = PdDim.target),
            ) { Text(stringResource(R.string.fb_cancel), fontSize = 18.sp) }
        },
    )
}
