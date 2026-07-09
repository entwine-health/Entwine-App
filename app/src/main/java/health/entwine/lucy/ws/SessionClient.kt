// WS session transport per SDD_WS_Protocol_v1 §1/§6 — OkHttp, silent reconnect
// 1 s / 3 s / 8 s then Offline. Emits parsed ServerMsg + lifecycle signals.
package health.entwine.lucy.ws

import health.entwine.lucy.proto.ServerMsg
import health.entwine.lucy.proto.parseServerMsg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

val RECONNECT_BACKOFF_MS = listOf(1_000L, 3_000L, 8_000L) // §6

sealed interface WsSignal {
    data class Message(val msg: ServerMsg) : WsSignal
    data class TtsChunk(val data: ByteArray) : WsSignal
    data object Dropped : WsSignal // one reconnect round exhausted → Offline
    data object AuthRejected : WsSignal // 4401 — re-enrollment (§7)
}

class SessionClient(
    private val url: String,
    private val tokenProvider: () -> String?,
    private val scope: CoroutineScope,
    client: OkHttpClient? = null,
) {
    private val http = client ?: OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var opened = false // handshake truth for reconnect probing
    @Volatile var lastSessionId: String? = null // resume_session_id on reconnect (§6)

    val signals = MutableSharedFlow<WsSignal>(
        extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val flow: SharedFlow<WsSignal> get() = signals

    fun connect(openMsg: String) {
        val token = tokenProvider() ?: return
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .build()
        ws = http.newWebSocket(req, Listener(openMsg))
    }

    fun send(text: String): Boolean = ws?.send(text) ?: false
    fun send(bytes: ByteArray): Boolean = ws?.send(bytes.toByteString()) ?: false

    fun close() {
        ws?.close(1000, null)
        ws = null
    }

    private inner class Listener(private val openMsg: String) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened = true
            webSocket.send(openMsg)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = parseServerMsg(text)
            if (msg is ServerMsg.Ready) lastSessionId = msg.sessionId
            signals.tryEmit(WsSignal.Message(msg))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            signals.tryEmit(WsSignal.TtsChunk(bytes.toByteArray()))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            opened = false
            ws = null
            if (response?.code == 401) {
                signals.tryEmit(WsSignal.AuthRejected)
                return
            }
            scope.launch { reconnectLoop() }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            opened = false
            ws = null
            when (code) {
                4401 -> signals.tryEmit(WsSignal.AuthRejected)
                1000 -> Unit // clean close after session.saved
                else -> scope.launch { reconnectLoop() }
            }
        }
    }

    @Volatile private var reconnecting = false

    private suspend fun reconnectLoop() {
        if (reconnecting) return
        reconnecting = true
        try {
            for (backoff in RECONNECT_BACKOFF_MS) {
                delay(backoff)
                if (tryOnce()) return
            }
            signals.tryEmit(WsSignal.Dropped)
        } finally {
            reconnecting = false
        }
    }

    // Wired by the ViewModel: rebuild session.open with resume_session_id (§6).
    var reopenMsg: (() -> String)? = null

    private suspend fun tryOnce(): Boolean {
        val open = reopenMsg?.invoke() ?: return false
        opened = false
        connect(open)
        // Reason: connect() returns before the handshake — success is the onOpen
        // callback, not a non-null handle. Short grace covers the round trip.
        delay(2_500)
        return opened
    }
}
