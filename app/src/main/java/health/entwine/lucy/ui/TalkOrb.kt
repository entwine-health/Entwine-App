// The talk orb — Lucy as a presence, not a button (2026-07-18 UI round).
// One breathing circle carries every conversation state; nothing else on the
// screen animates. Geometry is FIXED at PdDim.talkDiameter (R-UXA-14): every
// visual state draws inside the same 218 dp circle, the tappable area never
// changes, and the ring never grows past the bounds. Up-event semantics via
// Compose clickable (R-UXA-07); the recording ring IS the live input-level
// indicator (R-UXA-10) — level maps to ring thickness and brightness.
package health.entwine.lucy.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import health.entwine.lucy.state.AppState

/** Visual mode of the orb — derived, never stored (single source: AppState). */
private enum class OrbMode { IDLE, RECORDING, THINKING, SPEAKING, OFFLINE }

private fun modeOf(state: AppState, lucySpeaking: Boolean): OrbMode = when {
    // Offline wins over a stale lucySpeaking flag (a drop mid-playback can leave
    // it true) — otherwise the disconnected orb would still pulse "speaking".
    state is AppState.Offline -> OrbMode.OFFLINE
    state is AppState.Recording -> OrbMode.RECORDING
    state is AppState.Responding || lucySpeaking -> OrbMode.SPEAKING
    state is AppState.Processing -> OrbMode.THINKING
    else -> OrbMode.IDLE
}

@Composable
fun TalkOrb(
    state: AppState,
    micLevel: Float,
    lucySpeaking: Boolean,
    label: String,
    onTap: () -> Unit,
) {
    val mode = modeOf(state, lucySpeaking)
    val breath = rememberInfiniteTransition(label = "orb")
    // Idle breathing: slow and shallow — calm presence, never busy (PD-IDP §B).
    val breathT by breath.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Reverse),
        label = "breath",
    )
    // Thinking: one quiet rotating arc.
    val spin by breath.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "spin",
    )
    // Speaking: a gentle pulse, slower than speech itself — she glows, not flickers.
    val pulse by breath.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )

    val disc = when (mode) {
        OrbMode.RECORDING -> EntwineCoral // stop affordance — same as before the redesign
        OrbMode.IDLE -> EntwineCyan
        // Muted-but-visible grey: EntwineBorder (#1C1C1E) vanished on the black
        // background — the orb looked like it disappeared when offline.
        OrbMode.OFFLINE -> EntwineDim
        else -> EntwineCyanDeep // thinking / speaking: Lucy holds the floor
    }
    val onDisc = when (mode) {
        OrbMode.RECORDING, OrbMode.IDLE, OrbMode.OFFLINE -> Color.Black
        else -> Color.White
    }

    Box(
        Modifier
            .size(PdDim.talkDiameter)
            .clip(CircleShape)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(PdDim.talkDiameter)) {
            val r = size.minDimension / 2f
            val center = Offset(r, r)
            val ringInset = 10.dp.toPx()
            // Disc first — the ring draws in the outer margin, inside bounds.
            drawCircle(disc, radius = r - ringInset * 2f, center = center)
            when (mode) {
                OrbMode.IDLE -> drawCircle(
                    EntwineCyan.copy(alpha = 0.20f + 0.25f * breathT),
                    radius = r - ringInset,
                    center = center,
                    style = Stroke(width = 3.dp.toPx() + 2.dp.toPx() * breathT),
                )
                OrbMode.RECORDING -> drawCircle(
                    // R-UXA-10: the ring is the live level meter — soft voices
                    // still move it (upstream AGC), silence leaves it thin.
                    EntwineCyan.copy(alpha = 0.55f + 0.45f * micLevel.coerceIn(0f, 1f)),
                    radius = r - ringInset,
                    center = center,
                    style = Stroke(
                        width = 3.dp.toPx() + 11.dp.toPx() * micLevel.coerceIn(0f, 1f)
                    ),
                )
                OrbMode.THINKING -> drawArc(
                    EntwineCyan.copy(alpha = 0.8f),
                    startAngle = spin,
                    sweepAngle = 80f,
                    useCenter = false,
                    topLeft = Offset(ringInset, ringInset),
                    size = androidx.compose.ui.geometry.Size(
                        (r - ringInset) * 2f, (r - ringInset) * 2f
                    ),
                    style = Stroke(
                        width = 4.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(6.dp.toPx(), 10.dp.toPx())
                        ),
                    ),
                )
                OrbMode.SPEAKING -> drawCircle(
                    EntwineCyan.copy(alpha = 0.35f + 0.4f * pulse),
                    radius = r - ringInset,
                    center = center,
                    style = Stroke(width = 4.dp.toPx() + 4.dp.toPx() * pulse),
                )
                OrbMode.OFFLINE -> drawCircle(
                    EntwineDim.copy(alpha = 0.5f),
                    radius = r - ringInset,
                    center = center,
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
        }
        Text(
            label,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            color = onDisc,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}
