// Unified notice surfaces (2026-07-18 UI round): every transient message —
// error, offline, cap, update — is one quiet card with a colored edge, never
// a bare floating Text. One shape to learn; the edge color is the meaning.
package health.entwine.lucy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import health.entwine.lucy.R

@Composable
fun NoticeBanner(
    text: String,
    edge: Color,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Surface(
        color = EntwineCard,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row {
            Box(Modifier.width(4.dp).fillMaxHeight().background(edge))
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth()) {
                Text(text, fontSize = 18.sp, lineHeight = 26.sp, color = Color.White)
                action?.invoke()
            }
        }
    }
}

@Composable
fun ErrorBanner(key: String) {
    // user_message_key → Q-23 copy table; placeholders until CPO strings land.
    val res = when (key) {
        "err_stt" -> R.string.err_stt
        "err_llm" -> R.string.err_generic
        "err_resync" -> R.string.err_resync
        else -> R.string.err_generic
    }
    // FB-19f: recoverable hiccups use the calm/dim edge, NEVER the alarm red
    // (EntwineCoral = recording + crisis family). A "try again" must not read as
    // an emergency; the crisis screen stays the only red-alarm surface.
    NoticeBanner(androidx.compose.ui.res.stringResource(res), edge = EntwineDim)
}

@Composable
fun MicDeniedBanner(onEnable: () -> Unit) {
    // R-UXA-09/10: a denied mic must never leave a dead recording — say so plainly
    // (typing still works) and offer the one tap that fixes it.
    NoticeBanner(
        androidx.compose.ui.res.stringResource(R.string.mic_denied),
        edge = EntwineCoral,
    ) {
        OutlinedButton(
            onClick = onEnable,
            modifier = Modifier.padding(top = 8.dp).heightIn(min = PdDim.target),
        ) {
            Text(
                androidx.compose.ui.res.stringResource(R.string.btn_enable_mic),
                fontSize = 18.sp,
                color = Color.White,
            )
        }
    }
}

@Composable
fun OfflineBanner(onRetry: () -> Unit) {
    NoticeBanner(
        androidx.compose.ui.res.stringResource(R.string.offline_copy),
        edge = EntwineYellow,
    ) {
        OutlinedButton(
            onClick = onRetry,
            modifier = Modifier.padding(top = 8.dp).heightIn(min = PdDim.target),
        ) {
            Text(
                androidx.compose.ui.res.stringResource(R.string.btn_retry),
                fontSize = 18.sp,
                color = Color.White,
            )
        }
    }
}
