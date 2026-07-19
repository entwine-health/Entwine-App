// Conversation orchestrator: reducer (SDD matrix) + WS transport + audio lanes.
// The reducer decides; this class executes Actions and feeds events back.
package health.entwine.lucy

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

/** Longest single awaited hop inside PROCESSING (WS §7: utterance.end → stt.final). */
private const val PHASE_TIMEOUT_MS = 15_000L

/** Grace for `session.saved` before exiting anyway (matrix §Closing). */
private const val SAVED_TIMEOUT_MS = 5_000L

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
    // Mic permission denied — surfaces the enable-mic banner instead of a dead
    // recording (R-UXA-09/10). Refreshed on launch, permission result, and resume.
    val micDenied: Boolean = false,
    // R-LNG-01: session language — MainActivity derives locale + layout direction.
    val lang: String = "he",
    // Cosmetic presence flag: Lucy audio is playing (incl. server-initiated
    // scripted turns where the state machine stays IdleReady — the to_solve #10
    // residue). Drives the TalkOrb speaking visual, never the state matrix.
    val lucySpeaking: Boolean = false,
    // Set after a successful in-app deletion request (R-DEL-04) — the enroll
    // screen shows the confirmation once.
    val deletedDone: Boolean = false,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val http = OkHttpClient()
    private val codec = PassthroughPcm16()
    private val recorder = Recorder(codec, viewModelScope)
    private val player = Player(codec)

    private val _ui = MutableStateFlow(UiSlice(enrolled = store.deviceToken != null))

    init {
        // R-LNG-01: last known session language applies from launch (pre-ready).
        viewModelScope.launch { _ui.value = _ui.value.copy(lang = store.lang()) }
    }
    val ui: StateFlow<UiSlice> = _ui

    private var crisisTargets: List<CrisisTarget> = BAKED_TARGETS
    private var vadSilenceMs = 2000
    private var currentSeq = 0
    private var suppressReply = false
    private var watchdog: Job? = null

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

    // ---- account deletion request (R-DEL-04) ---------------------------------

    fun deleteAccount(onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val req = Request.Builder().url("${BuildConfig.API_BASE}/v1/account/delete")
                .header("Authorization", "Bearer ${store.deviceToken}")
                .post(ByteArray(0).toRequestBody(null)).build()
            val ok = runCatching {
                http.newCall(req).execute().use { it.isSuccessful }
            }.getOrDefault(false)
            if (ok) {
                // Collection stopped server-side; drop the dead token and return
                // to the enroll screen with the one-time confirmation.
                store.deviceToken = null
                client.close()
                _ui.value = UiSlice(enrolled = false, deletedDone = true, lang = _ui.value.lang)
            }
            launch(Dispatchers.Main) { onResult(ok) }
        }
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
        // Reason: a send that never reached the socket means no answer is
        // coming, so the turn would hang forever with an inert button (found
        // live 2026-07-17). Treat undeliverable as the drop it actually is.
        val delivered = t.actions.map(::execute).all { it }
        armWatchdog(t.next)
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
        if (!delivered) dispatch(Event.WsDrop)
    }

    /** Client-side phase watchdog — matrix I5, WS §7. Never let a state that
     *  waits on the server become a dead end.
     *
     *  Implements: R-LOOP-03 (the app never strands the user on a server-
     *  dependent state; SDD_App_State_Matrix I5, SDD_WS_Protocol §7).
     *
     *  # Reason: PROCESSING and CLOSING both hand control to the server and
     *  have no local escape. When the socket was silently dead the app sat on
     *  "לוסי חושבת" forever with a no-op button, and End-session wedged the
     *  same way (found live 2026-07-17). The reducer always had the exits —
     *  RecoverableError and SavedTimeout — but nothing ever fired them.
     */
    private fun armWatchdog(state: AppState) {
        watchdog?.cancel()
        // WS §7: the longest single awaited hop in PROCESSING is 15 s
        // (utterance.end → stt.final); matrix §Closing allows 5 s for
        // session.saved. Any server message re-arms the timer, so this only
        // fires on real silence — never on slow-but-alive synthesis.
        val timeoutMs = when (state) {
            AppState.Processing -> PHASE_TIMEOUT_MS
            AppState.Closing -> SAVED_TIMEOUT_MS
            else -> return
        }
        watchdog = viewModelScope.launch {
            delay(timeoutMs)
            if (state == AppState.Closing) {
                dispatch(Event.SavedTimeout)
            } else {
                dispatch(Event.RecoverableError)
                _ui.value = _ui.value.copy(errorKey = "err_llm") // dispatch clears it first
            }
        }
    }

    /** Re-arm on any server traffic: the turn is alive, only slow. */
    private fun touchWatchdog() {
        if (_ui.value.state is AppState.Processing) armWatchdog(AppState.Processing)
    }

    private fun execute(action: Action): Boolean {
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
            Action.SEND_UTTER_START -> return client.send(ClientMsg.utteranceStart())
            Action.SEND_UTTER_END_VAD ->
                return client.send(ClientMsg.utteranceEnd(recorder.capturedMs, "vad_silence"))
            Action.SEND_UTTER_END_TAP ->
                return client.send(ClientMsg.utteranceEnd(recorder.capturedMs, "tap"))
            Action.SEND_UTTER_END_WRAP -> // server-initiated endpoint (WS v1.6, R-LOOP-10)
                return client.send(ClientMsg.utteranceEnd(recorder.capturedMs, "wrap_phrase"))
            Action.SEND_BARGE_IN -> {
                // Playback already stopped (TTS_STOP ordering in the matrix row);
                // mic waits for turn.cancelled per WS §8.1.
                bargeMonitor.stop()
                return client.send(ClientMsg.bargeIn(currentSeq))
            }
            Action.SEND_TEXT -> Unit // text carried by sendText below
            Action.SEND_CLOSE -> return client.send(ClientMsg.sessionClose())
            Action.PERSIST_TEXT -> Unit // Compose field persists via Store on change
            Action.RECONNECT -> Unit // SessionClient reconnects on failure callbacks
            Action.EXIT_APP -> Unit // Activity observes Closing terminal state
        }
        return true
    }

    fun sendText(text: String) {
        if (text.isBlank()) return
        suppressReply = false
        _ui.value = _ui.value.copy(
            transcript = _ui.value.transcript + ("me" to text), partialReply = ""
        )
        dispatch(Event.TextSend(text))
        // Reason: SEND_TEXT carries no payload through execute(), so this send
        // is the one the reducer cannot check — an undelivered text turn would
        // hang exactly like the voice one did.
        if (!client.send(ClientMsg.textMessage(text))) dispatch(Event.WsDrop)
        viewModelScope.launch { store.setComposedText("") }
    }

    fun tapTalk() {
        // Never start a recording the OS won't feed: without the mic permission
        // AudioRecord returns silence forever and the orb hangs in RECORDING
        // (found: first-run "deny" → dead orb). Show the fix path instead.
        if (!micGranted()) {
            _ui.value = _ui.value.copy(micDenied = true)
            return
        }
        suppressReply = false
        _ui.value = _ui.value.copy(partialReply = "", micDenied = false)
        dispatch(Event.TapTalk)
    }

    private fun micGranted(): Boolean = ContextCompat.checkSelfPermission(
        getApplication(), Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    /** Re-read the mic grant (permission-result / resume / launch) into the UI. */
    fun refreshMicState() {
        _ui.value = _ui.value.copy(micDenied = !micGranted())
    }

    /** Deep-link to this app's system settings so the user can flip the mic on. */
    fun openAppSettings() {
        val ctx = getApplication<Application>()
        ctx.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", ctx.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
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
        touchWatchdog() // any traffic proves the turn is alive, only slow
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
            WsSignal.Dropped -> {
                _ui.value = _ui.value.copy(lucySpeaking = false) // no stale "speaking" while offline
                dispatch(Event.WsDrop)
            }
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
                    lang = msg.config.lang,
                )
                viewModelScope.launch { store.setLang(msg.config.lang) }
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
                // Finish the exchange ONLY when no audio follows (echo/text-only).
                // Full-mode replies end on tts.done; finishing here would flip the
                // UI to idle mid-playback — talk button live, barge-in gone, a tap
                // rejected as a double turn (R-LOOP-07; found 2026-07-18).
                if (!msg.hasTts && _ui.value.state is AppState.Processing) {
                    dispatch(Event.TtsPlaybackDone)
                }
            }
            is ServerMsg.TtsBegin -> {
                _ui.value = _ui.value.copy(lucySpeaking = true)
                player.begin(msg.sampleRate)
            }
            is ServerMsg.TtsDone -> {
                _ui.value = _ui.value.copy(lucySpeaking = false)
                dispatch(Event.TtsPlaybackDone)
            }
            is ServerMsg.UtteranceEndpoint -> // wrap fast-path (WS v1.6, R-LOOP-10)
                dispatch(Event.ServerEndpoint)
            is ServerMsg.TurnCancelled -> {
                _ui.value = _ui.value.copy(lucySpeaking = false)
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
            is ServerMsg.LanguageUpdated -> { // WS v1.9 (R-LNG-03): live locale flip
                _ui.value = _ui.value.copy(lang = msg.lang)
                viewModelScope.launch { store.setLang(msg.lang) }
            }
            is ServerMsg.Error -> {
                // Reason: dispatch() clears errorKey, so setting it first meant
                // the server's own copy never reached the screen (R-LOOP-03).
                if (msg.recoverable) dispatch(Event.RecoverableError)
                _ui.value = _ui.value.copy(errorKey = msg.userMessageKey)
            }
            is ServerMsg.Unknown -> Unit
        }
    }

    override fun onCleared() {
        watchdog?.cancel()
        recorder.stop()
        bargeMonitor.stop()
        player.stop()
        client.close()
    }
}
