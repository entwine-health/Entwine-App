// In-app feedback (R-FBK-01/02): typed and/or recorded, off the live turn.
package health.entwine.lucy.ui

import android.content.res.Configuration
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import health.entwine.lucy.AppViewModel
import health.entwine.lucy.R
import java.util.Locale

@Composable
internal fun FeedbackDialog(vm: AppViewModel, onClose: () -> Unit) {
    // #26i: an AlertDialog hosts a detached window whose LocalContext does NOT inherit
    // the session-language override MainActivity provides to Root, so the feedback UI
    // rendered in the device locale and mis-aligned. Resolve strings against an explicit
    // session-language context (ctx.getString — robust regardless of composition-local
    // propagation into the dialog window) and force the matching layout direction.
    val lang = vm.ui.collectAsState().value.lang
    val base = LocalContext.current
    val ctx = remember(lang) {
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(Locale(lang))
        base.createConfigurationContext(cfg)
    }
    val dir = if (lang == "he") LayoutDirection.Rtl else LayoutDirection.Ltr

    var text by rememberSaveable { mutableStateOf("") }
    var recording by rememberSaveable { mutableStateOf(false) }
    var sent by rememberSaveable { mutableStateOf<Boolean?>(null) }

    CompositionLocalProvider(LocalLayoutDirection provides dir) {
        AlertDialog(
            onDismissRequest = { vm.stopFeedbackRecording(); onClose() },
            title = { Text(ctx.getString(R.string.fb_title), fontSize = 22.sp) },
            text = {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text(ctx.getString(R.string.fb_text_label)) },
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
                            ctx.getString(if (recording) R.string.fb_stop_rec else R.string.fb_record),
                            fontSize = 18.sp,
                        )
                    }
                    sent?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            ctx.getString(if (it) R.string.fb_sent else R.string.fb_fail),
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
                ) { Text(ctx.getString(R.string.fb_send), fontSize = 18.sp) }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { vm.stopFeedbackRecording(); onClose() },
                    modifier = Modifier.heightIn(min = PdDim.target),
                ) { Text(ctx.getString(R.string.fb_cancel), fontSize = 18.sp) }
            },
        )
    }
}
