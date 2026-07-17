// Conversation orchestrator: reducer (SDD matrix) + WS transport + audio lanes.
// The reducer decides; this class executes Actions and feeds events back.
package health.entwine.lucy

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import health.entwine.lucy.audio.EnergyVad
import health.entwine.lucy.audio.PassthroughPcm16
import health.entwine.lucy.audio.Player
import health.entwine.lucy.audio.Recorder
import health.entwine.lucy.audio.SpeechOnsetMonitor
import health.entwine.lucy.proto.ClientMsg
import health.entwine.lucy.proto.CrisisTarget
import health.entwine.lucy.proto.ServerMsg
import health.entwine.lucy.state.Action
import health.entwine.lucy.state.AppState
import health.entwine.lucy.state.Event
import health.entwine.lucy.state.reduce
import health.entwine.lucy.store.Store
import health.entwine.lucy.ws.SessionClient
import health.entwine.lucy.ws.WsSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

/** Semver-ish compare: true when current < minimum (Q-14 update check). */
fun versionBelow(current: String, minimum: String): Boolean {
    fun parts(v: String) = v.split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
    val c = parts(current); val m = parts(minimum)
    for (i in 0 until maxOf(c.size, m.size)) {
        val a = c.getOrElse(i) { 0 }; val b = m.getOrElse(i) { 0 }
        if (a != b) return a < b
    }
    return false
}

/** Baked-in national fallbacks — used only if no session.ready ever cached (WS §5.3). */
val BAKED_TARGETS = listOf(
    CrisisTarget("crisis_eran", "1201"),
    CrisisTarget("crisis_emergency", "101"),
)

data class UiSlice(
    val state: AppState = AppState.IdleReady,
    val enrolled: Boolean = false,
    val transcript: List<Pair<String, String>> = emptyList(), // (who, text)
    val partialReply: String = "",
    val motorChip: String? = null,
    val capSuggested: Boolean = false,
    val errorKey: String? = null,
    val updateNeeded: Boolean = false,
    val showSttEcho: Boolean = true,
    val micLevel: Float = 0f, // live input meter while recording (R-UXA-10)
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val http = OkHttpClient()
    private val codec = PassthroughPcm16()
    private val recorder = Recorder(codec, viewModelScope)
    private val player = Player(codec)

    private val _ui = MutableStateFlow(UiSlice(enrolled = store.deviceToken != null))
    val ui: StateFlow<UiSlice> = _ui

    private var crisisTargets: List<CrisisTarget> = BAKED_TARGETS
    private var vadSilenceMs = 2000
    private var currentSeq = 0
    private var suppressReply = false

    // Barge-in (ADR-0013): server-owned gate + monitor tuning (session.ready).
    private var bargeInMode = "tap"
    private var bargeInMinSpeechMs = 300
    private var bargeInRmsThreshold = 1600.0
    private val bargeMonitor = SpeechOnsetMonitor(viewModelScope)

    private val client = SessionClient(
        url = BuildConfig.WS_URL,
        tokenProvider = { store.deviceToken },
        scope = viewModelScope,
    )

    init {
        client.reopenMsg = {
            ClientMsg.sessionOpen(BuildConfig.VERSION_NAME, client.lastSessionId)
        }
        viewModelScope.launch { client.flow.collect(::onSignal) }
    }

    // ---- enrollment (R-ENR-05, R-ENR-02): one invite code, nothing else -------

    fun enroll(code: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val body = JSONObject().put("code", code).toString()
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("${BuildConfig.API_BASE}/v1/enroll")
                .post(body).build()
            val ok = runCatching {
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use false
                    val token = JSONObject(resp.body!!.string()).getString("device_token")
                    store.deviceToken = token
                    true
                }
            }.getOrDefault(false)
            _ui.value = _ui.value.copy(enrolled = ok || _ui.value.enrolled)
            // Reason: onCreate's openSession() ran before this device had a
            // token and returned without a socket, so a freshly enrolled app
            // stayed mute until a restart — every send dropped, the turn hung
            // on "Lucy is thinking" (found live 2026-07-17). Enrolment is the
            // moment the token exists; connect now.
            if (ok) openSession()
            onResult(ok)
        }
    }

    // ---- feedback (R-FBK-01) — off the conversation pipeline --------------------

    private var fbRecorder: Recorder? = null
    private val fbBuf = java.io.ByteArrayOutputStream()

    fun startFeedbackRecording() {
        fbBuf.reset()
        fbRecorder = Recorder(codec, viewModelScope).also { r ->
            r.start(
                vad = null,
                onFrame = { frame -> fbBuf.write(frame, 5, frame.size - 5) }, // strip envelope
                onVadSilence = {},
                onMaxUtterance = { stopFeedbackRecording() },
            )
        }
    }

    fun stopFeedbackRecording() {
        fbRecorder?.stop()
        fbRecorder = null
    }

    fun sendFeedback(text: String, onResult: (Boolean) -> Unit) {
        stopFeedbackRecording()
        val clip = fbBuf.toByteArray()
        viewModelScope.launch(Dispatchers.IO) {
            val mb = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("app_version", BuildConfig.VERSION_NAME)
            if (text.isNotBlank()) mb.addFormDataPart("text", text)
            if (clip.isNotEmpty()) {
                mb.addFormDataPart(
                    "audio", "clip.opus",
                    clip.toRequestBody("audio/ogg".toMediaType()),
                )
            }
            if (text.isBlank() && clip.isEmpty()) { onResult(false); return@launch }
            val req = Request.Builder().url("${BuildConfig.API_BASE}/v1/feedback")
                .header("Authorization", "Bearer ${store.deviceToken}")
                .post(mb.build()).build()
            val ok = runCatching {
                http.newCall(req).execute().use { it.isSuccessful }
            }.getOrDefault(false)
            fbBuf.reset()
            onResult(ok)
        }
    }

    // ---- session lifecycle -----------------------------------------------------

    fun openSession() {
        viewModelScope.launch {
            store.setLastSessionDay(LocalDate.now().toString()) // nudge suppression (R-NOT-02)
        }
        client.connect(ClientMsg.sessionOpen(BuildConfig.VERSION_NAME, null))
    }

    fun dispatch(event: Event) {
        val prev = _ui.value.state
        val t = reduce(prev, event, crisisTargets)
        _ui.value = _ui.value.copy(state = t.next, errorKey = null)
        t.actions.forEach(::execute)
        // Monitor mic lifecycle (matrix I6): alive exactly while RESPONDING in
        // speech mode; entering by transition only, never re-armed mid-state.
        if (t.next is AppState.Responding && prev !is AppState.Responding &&
            bargeInMode == "speech"
        ) {
            bargeMonitor.start(bargeInRmsThreshold, bargeInMinSpeechMs) {
                dispatch(Event.SpeechBargeIn)
            }
        } else if (t.next !is AppState.Responding) {
            bargeMonitor.stop()
        }
    }

    private fun execute(action: Action) {
        when (action) {
            Action.MIC_ON -> recorder.start(
                vad = EnergyVad(vadSilenceMs),
                onFrame = { client.send(it) },
                onVadSilence = { dispatch(Event.VadSilence) },
                onMaxUtterance = { dispatch(Event.MaxUtterance) },
                onLevel = { lvl ->
                    // Quantized so the meter doesn't recompose 50×/s (R-UXA-10).
                    val cur = _ui.value.micLevel
                    if (kotlin.math.abs(lvl - cur) > 0.04f) {
                        _ui.value = _ui.value.copy(micLevel = lvl)
                    }
                },
            )
            Action.MIC_OFF, Action.DROP_MIC -> {
                recorder.stop()
                _ui.value = _ui.value.copy(micLevel = 0f)
            }
            Action.FLUSH_MIC -> Unit // folded into SEND_UTTER_END_*
            Action.TTS_STOP -> player.stop()
            Action.SUPPRESS_REPLY -> suppressReply = true
            Action.SEND_UTTER_START -> client.send(ClientMsg.utteranceStart())
            Action.SEND_UTTER_END_VAD ->
                client.send(ClientMsg.utteranceEnd(recorder.capturedMs, "vad_silence"))
            Action.SEND_UTTER_END_TAP ->
                client.send(ClientMsg.utteranceEnd(recorder.capturedMs, "tap"))
            Action.SEND_UTTER_END_WRAP -> // server-initiated endpoint (WS v1.6, R-LOOP-10)
                client.send(ClientMsg.utteranceEnd(recorder.capturedMs, "wrap_phrase"))
            Action.SEND_BARGE_IN -> {
                // Playback already stopped (TTS_STOP ordering in the matrix row);
                // mic waits for turn.cancelled per WS §8.1.
                bargeMonitor.stop()
                client.send(ClientMsg.bargeIn(currentSeq))
            }
            Action.SEND_TEXT -> Unit // text carried by sendText below
            Action.SEND_CLOSE -> client.send(ClientMsg.sessionClose())
            Action.PERSIST_TEXT -> Unit // Compose field persists via Store on change
            Action.RECONNECT -> Unit // SessionClient reconnects on failure callbacks
            Action.EXIT_APP -> Unit // Activity observes Closing terminal state
        }
    }

    fun sendText(text: String) {
        if (text.isBlank()) return
        suppressReply = false
        _ui.value = _ui.value.copy(
            transcript = _ui.value.transcript + ("me" to text), partialReply = ""
        )
        dispatch(Event.TextSend(text))
        client.send(ClientMsg.textMessage(text))
        viewModelScope.launch { store.setComposedText("") }
    }

    fun tapTalk() {
        suppressReply = false
        _ui.value = _ui.value.copy(partialReply = "")
        dispatch(Event.TapTalk)
    }

    fun motorTap(state: String) {
        // Out-of-band by contract (R-MOT-01): no dispatch, capture untouched.
        // R-UXA-14 (SRS v1.9): reporting a state never re-lays-out the screen —
        // targets are permanently at the enlarged geometry (PdDim), so nothing
        // moves under a hand that is already struggling.
        client.send(ClientMsg.motorTap(state, Instant.now().toString()))
    }

    // ---- server signals → events ------------------------------------------------

    private fun onSignal(sig: WsSignal) {
        when (sig) {
            is WsSignal.Message -> onServer(sig.msg)
            is WsSignal.TtsChunk -> {
                if (suppressReply) return // crisis discard (WS §5.2)
                if (sig.data.size <= 5) return // envelope-only/short frame — nothing to play
                if (_ui.value.state is AppState.Processing) {
                    client.send(ClientMsg.ackPlaybackStart(currentSeq, Instant.now().toString()))
                    dispatch(Event.FirstTtsChunk)
                }
                player.feed(sig.data.copyOfRange(5, sig.data.size)) // strip envelope
            }
            WsSignal.Dropped -> dispatch(Event.WsDrop)
            WsSignal.AuthRejected -> {
                store.deviceToken = null
                _ui.value = _ui.value.copy(enrolled = false) // re-enrollment screen (§7 E_AUTH)
            }
        }
    }

    private fun onServer(msg: ServerMsg) {
        when (msg) {
            is ServerMsg.Ready -> {
                crisisTargets = msg.config.crisisTargets.ifEmpty { BAKED_TARGETS }
                vadSilenceMs = msg.config.vadSilenceMs
                bargeInMode = msg.config.bargeIn
                bargeInMinSpeechMs = msg.config.bargeInMinSpeechMs
                bargeInRmsThreshold = msg.config.bargeInRmsThreshold.toDouble()
                _ui.value = _ui.value.copy(
                    updateNeeded = versionBelow(
                        BuildConfig.VERSION_NAME, msg.config.minAppVersion
                    ),
                    showSttEcho = msg.config.showSttEcho,
                )
                if (_ui.value.state is AppState.Offline) dispatch(Event.ReconnectOk)
            }
            is ServerMsg.SttFinal -> {
                currentSeq = msg.exchangeSeq
                if (_ui.value.showSttEcho) { // "what Lucy heard" — config-gated (SDD §4)
                    _ui.value = _ui.value.copy(
                        transcript = _ui.value.transcript + ("me" to msg.text)
                    )
                }
            }
            is ServerMsg.ReplyDelta -> {
                if (!suppressReply) {
                    _ui.value = _ui.value.copy(partialReply = _ui.value.partialReply + msg.text)
                }
            }
            is ServerMsg.ReplyDone -> {
                if (!suppressReply && _ui.value.partialReply.isNotBlank()) {
                    _ui.value = _ui.value.copy(
                        transcript = _ui.value.transcript + ("lucy" to _ui.value.partialReply),
                        partialReply = "",
                    )
                }
                // Text-only turn (no TTS in echo mode): finish the exchange.
                if (_ui.value.state is AppState.Processing) dispatch(Event.TtsPlaybackDone)
            }
            is ServerMsg.TtsBegin -> player.begin(msg.sampleRate)
            is ServerMsg.TtsDone -> dispatch(Event.TtsPlaybackDone)
            is ServerMsg.UtteranceEndpoint -> // wrap fast-path (WS v1.6, R-LOOP-10)
                dispatch(Event.ServerEndpoint)
            is ServerMsg.TurnCancelled -> {
                suppressReply = false // cancelled exchange's stragglers already dropped
                _ui.value = _ui.value.copy(partialReply = "")
                dispatch(Event.TurnCancelled)
            }
            is ServerMsg.MotorAck -> _ui.value = _ui.value.copy(motorChip = msg.textKey)
            is ServerMsg.CrisisShow -> {
                crisisTargets = msg.targets.ifEmpty { crisisTargets }
                dispatch(Event.CrisisShown)
            }
            is ServerMsg.CapSuggest -> _ui.value = _ui.value.copy(capSuggested = true)
            is ServerMsg.Saved -> dispatch(Event.SessionSaved)
            is ServerMsg.Error -> {
                _ui.value = _ui.value.copy(errorKey = msg.userMessageKey)
                if (msg.recoverable) dispatch(Event.RecoverableError)
            }
            is ServerMsg.Unknown -> Unit
        }
    }

    override fun onCleared() {
        recorder.stop()
        bargeMonitor.stop()
        player.stop()
        client.close()
    }
}
