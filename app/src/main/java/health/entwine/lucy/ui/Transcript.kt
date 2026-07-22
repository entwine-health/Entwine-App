// Conversation transcript — reading-first, quiet chrome (2026-07-18 UI round).
// The user's words sit in a cyan-edged card (their contribution, visibly
// theirs); Lucy's words are plain high-contrast text — the conversation is
// the interface, so her side carries no decoration. 22 sp / relaxed leading
// for elderly eyes (R-UXA-13 contrast + 200% scale via sp). Auto-follows the
// newest line so nothing ever needs scrolling mid-conversation.
package health.entwine.lucy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import health.entwine.lucy.R

@Composable
fun TranscriptList(
    transcript: List<Pair<String, String>>,
    partialReply: String,
    replayable: Set<Int> = emptySet(),
    onReplay: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val total = transcript.size + if (partialReply.isNotBlank()) 1 else 0
    LaunchedEffect(total, partialReply.length) {
        if (total > 0) listState.scrollToItem(total - 1)
    }
    LazyColumn(modifier, state = listState) {
        itemsIndexed(transcript) { i, (who, text) ->
            if (who == "me") MeLine(text)
            else LucyLine(text, partial = false, canReplay = i in replayable, onReplay = { onReplay(i) })
        }
        if (partialReply.isNotBlank()) {
            item { LucyLine(partialReply, partial = true) }
        }
    }
}

@Composable
private fun MeLine(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Spacer(Modifier.width(28.dp)) // inset marks "mine" without alignment tricks
        Surface(
            color = EntwineCard,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row {
                Box(
                    Modifier.width(3.dp).padding(vertical = 2.dp)
                        .background(EntwineCyan)
                )
                Text(
                    text,
                    fontSize = 22.sp,
                    lineHeight = 30.sp,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun LucyLine(
    text: String,
    partial: Boolean,
    canReplay: Boolean = false,
    onReplay: () -> Unit = {},
) {
    val base = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    Column(if (canReplay) base.clickable(onClick = onReplay) else base) {
        Text(
            if (partial) "$text …" else text,
            fontSize = 22.sp,
            lineHeight = 32.sp,
            color = if (partial) Color(0xFFD9D9DE) else Color.White,
        )
        // #22d: a quiet "tap to hear again" affordance under replayable lines.
        if (canReplay) {
            Text(
                stringResource(R.string.replay_hint),
                fontSize = 15.sp,
                color = EntwineCyan,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
