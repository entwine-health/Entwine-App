// Capture + playback lanes (TDD D-11). Independent from the UI event bus —
// a motor tap can never glitch capture (matrix hard cell §3.2).
//
// Codec status (ADR-0004): the wire contract wants Opus. Device Opus *encoders*
// via MediaCodec are inconsistent across OEM builds, so Stage-0 ships a codec
// seam: PassthroughPcm16 feeds the echo pipeline today; the Opus implementation
// slots in behind the same interface after the device-matrix probe (TC-27).
package health.entwine.lucy.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import health.entwine.lucy.proto.FRAME_MIC
import health.entwine.lucy.proto.encodeFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

const val MIC_SAMPLE_RATE = 16_000
const val FRAME_MS = 20
const val MAX_UTTERANCE_MS = 90_000L // runaway-mic guard (matrix RECORDING row)

/** Seam for the wire codec — Opus later, PCM16 passthrough in echo bring-up. */
interface WireCodec {
    fun encode(pcm: ShortArray): ByteArray
    fun decode(payload: ByteArray): ShortArray
}

class PassthroughPcm16 : WireCodec {
    override fun encode(pcm: ShortArray): ByteArray {
        val out = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            out[2 * i] = (pcm[i].toInt() and 0xFF).toByte()
            out[2 * i + 1] = (pcm[i].toInt() shr 8).toByte()
        }
        return out
    }

    override fun decode(payload: ByteArray): ShortArray {
        val out = ShortArray(payload.size / 2)
        for (i in out.indices) {
            out[i] = ((payload[2 * i].toInt() and 0xFF) or
                (payload[2 * i + 1].toInt() shl 8)).toShort()
        }
        return out
    }
}

/**
 * Energy VAD for end-pointing (Q-11 default `vad`): silence when RMS stays under
 * threshold for `silenceMs`. PD-generous by config (session.ready vad_silence_ms).
 *
 * Speech-gated (R-UXA-11): the silence window only arms AFTER speech was heard
 * once — tapping talk and then thinking never cuts the turn off; with no speech
 * at all, capture simply runs until tap-stop or the runaway guard.
 */
class EnergyVad(private val silenceMs: Int, private val threshold: Double = 900.0) {
    private var silentAccumMs = 0
    private var speechSeen = false

    fun feed(pcm: ShortArray, frameMs: Int): Boolean {
        var acc = 0.0
        for (s in pcm) acc += s.toDouble() * s.toDouble()
        val rms = kotlin.math.sqrt(acc / pcm.size.coerceAtLeast(1))
        if (rms >= threshold) {
            speechSeen = true
            silentAccumMs = 0
        } else if (speechSeen) {
            silentAccumMs += frameMs
        }
        return speechSeen && silentAccumMs >= silenceMs
    }

    fun reset() {
        silentAccumMs = 0
        speechSeen = false
    }
}

/** Mic capture → framed wire chunks. Emits via callbacks on an IO coroutine. */
class Recorder(private val codec: WireCodec, private val scope: CoroutineScope) {
    private var job: Job? = null
    private var seq = 0L

    @Volatile
    var capturedMs: Long = 0 // read from the UI thread while the IO loop writes
        private set

    @SuppressLint("MissingPermission") // RECORD_AUDIO gated by the UI flow
    fun start(
        vad: EnergyVad?,
        onFrame: (ByteArray) -> Unit,
        onVadSilence: () -> Unit,
        onMaxUtterance: () -> Unit,
        onLevel: ((Float) -> Unit)? = null, // live input meter (R-UXA-10)
    ) {
        stop()
        seq = 0
        capturedMs = 0
        val samplesPerFrame = MIC_SAMPLE_RATE * FRAME_MS / 1000
        val minBuf = AudioRecord.getMinBufferSize(
            MIC_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        job = scope.launch(Dispatchers.IO) {
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MIC_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, samplesPerFrame * 4),
            )
            // Soft-voice gain (R-UXA-10): hypophonic speech needs every dB —
            // enable the platform AGC on this capture session where available.
            val agc = if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(rec.audioSessionId)?.also { it.enabled = true }
            } else {
                null
            }
            val buf = ShortArray(samplesPerFrame)
            try {
                rec.startRecording()
                while (isActive) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    capturedMs += FRAME_MS
                    onLevel?.let { cb ->
                        var acc = 0.0
                        for (i in 0 until n) acc += buf[i].toDouble() * buf[i].toDouble()
                        val rms = kotlin.math.sqrt(acc / n)
                        // sqrt curve keeps soft voices visible on the meter.
                        cb(kotlin.math.sqrt((rms / 6000.0).coerceIn(0.0, 1.0)).toFloat())
                    }
                    onFrame(encodeFrame(FRAME_MIC, seq++, codec.encode(buf.copyOf(n))))
                    if (vad?.feed(buf, FRAME_MS) == true) {
                        onVadSilence()
                        break
                    }
                    if (capturedMs >= MAX_UTTERANCE_MS) {
                        onMaxUtterance()
                        break
                    }
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
                agc?.release()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}

/**
 * Barge-in speech-onset monitor (ADR-0013, matrix I6): runs ONLY during
 * RESPONDING when the server config says `barge_in: "speech"`. Uses the
 * VOICE_COMMUNICATION source so the platform echo canceller subtracts Lucy's
 * own playback. Frames feed the RMS detector and die here — nothing is sent,
 * nothing is stored (R-LOG-02).
 */
class SpeechOnsetMonitor(private val scope: CoroutineScope) {
    private var job: Job? = null

    @SuppressLint("MissingPermission") // RECORD_AUDIO gated by the UI flow
    fun start(rmsThreshold: Double, minSpeechMs: Int, onSpeech: () -> Unit) {
        stop()
        val samplesPerFrame = MIC_SAMPLE_RATE * FRAME_MS / 1000
        val minBuf = AudioRecord.getMinBufferSize(
            MIC_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        job = scope.launch(Dispatchers.IO) {
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, // platform AEC on this session
                MIC_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, samplesPerFrame * 4),
            )
            val buf = ShortArray(samplesPerFrame)
            var speechMs = 0
            try {
                rec.startRecording()
                while (isActive) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var acc = 0.0
                    for (i in 0 until n) acc += buf[i].toDouble() * buf[i].toDouble()
                    val rms = kotlin.math.sqrt(acc / n)
                    speechMs = if (rms >= rmsThreshold) speechMs + FRAME_MS else 0
                    if (speechMs >= minSpeechMs) {
                        onSpeech()
                        break
                    }
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}

/** Streamed reply playback: first chunk starts audio immediately (R-LAT-03). */
class Player(private val codec: WireCodec) {
    private var track: AudioTrack? = null
    // FB-20 field test 2026-07-20: AudioTrack.write() BLOCKS, and a ~0.5 s track
    // buffer + a fast chunk stream made it block the *caller* — the main-thread
    // WS collector — for seconds → ANR ("app not responding" while Lucy talks),
    // and starved Compose so captions painted seconds late. Decode stays on the
    // caller (cheap); the blocking write runs on a dedicated single-thread drain.
    // Single consumer = FIFO = playback order preserved.
    private var scope: CoroutineScope? = null
    private var queue: Channel<ShortArray>? = null

    fun begin(sampleRate: Int) {
        stop()
        pending = ByteArray(0) // never carry a byte across replies
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(sampleRate) // ~0.5 s of 16-bit mono
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
        track = t
        // UNLIMITED: the server already paces the stream (_stream_cached) and live
        // synth arrives slower than playback, so the queue tracks a small backlog;
        // the blocking write to the 0.5 s track buffer self-paces the drain.
        val q = Channel<ShortArray>(Channel.UNLIMITED)
        val s = CoroutineScope(Dispatchers.IO + SupervisorJob())
        queue = q
        scope = s
        s.launch {
            // `t` captured (not the field) → no cross-thread visibility gap; a
            // write after stop()'s release just throws and is swallowed.
            for (pcm in q) runCatching { t.write(pcm, 0, pcm.size) }
        }
    }

    // Implements: SDD_WS_Protocol v1.8 §2 (whole-sample framing invariant).
    // Reason: PCM16 samples may straddle two frames. Decoding each frame alone
    // dropped the dangling byte, so every later sample was assembled from the
    // wrong two bytes — full-scale static after the first word (2026-07-17).
    // The server now sends aligned frames; this keeps playback correct even if
    // some future carrier does not.
    private var pending = ByteArray(0)

    fun feed(payload: ByteArray) {
        val buf = if (pending.isEmpty()) payload else pending + payload
        val whole = buf.size - buf.size % 2
        pending = if (whole == buf.size) ByteArray(0) else buf.copyOfRange(whole, buf.size)
        if (whole == 0) return
        val pcm = codec.decode(if (whole == buf.size) buf else buf.copyOfRange(0, whole))
        queue?.trySend(pcm) // non-blocking handoff; UNLIMITED never drops a frame
    }

    /** Hard cell §3.1: crisis stops audio *first* and discards — never pause. */
    fun stop() {
        scope?.cancel() // stop the drain
        scope = null
        queue?.close()
        queue = null
        track?.let {
            runCatching { it.pause(); it.flush(); it.stop() } // flush unblocks an in-flight write
            it.release()
        }
        track = null
    }
}

/** "Thinking" wait cue (to_solve #22 #4): a soft, steady tone pulse played while
 *  Lucy composes a reply, replacing the old spoken fillers. Design from the
 *  2026-07-22 research pass (elderly-PD-appropriate): ~440 Hz sine, 150 ms pulse
 *  (25 ms raised-cosine fades → no click), one per 1.6 s, steady pitch/level, peak
 *  ~ −12 dBFS (2× louder — to_solve #2, field round-5; still subordinate to speech),
 *  warm — NOT a hospital-monitor beep, no rising
 *  pitch (which reads as alarm). One pre-computed [pulse+silence] period looped in
 *  hardware (MODE_STATIC) → zero runtime cost. Caller starts it ~600 ms into the
 *  wait and stops it on the first reply audio. */
class WaitTone(private val rate: Int = 24_000) {
    private var track: AudioTrack? = null
    private val period: ShortArray = buildPeriod()

    private fun buildPeriod(): ShortArray {
        val n = (rate * 1.6).toInt()
        val pulse = (rate * 0.150).toInt()
        val fade = (rate * 0.025).toInt()
        val amp = Short.MAX_VALUE * 0.25 // ~ −12 dBFS (to_solve #2: 2× louder, field round-5)
        val out = ShortArray(n) // remainder stays silence
        for (i in 0 until pulse) {
            val env = when {
                i < fade -> 0.5 * (1 - cos(PI * i / fade))
                i > pulse - fade -> 0.5 * (1 - cos(PI * (pulse - i) / fade))
                else -> 1.0
            }
            out[i] = (amp * env * sin(2 * PI * 440.0 * i / rate)).toInt().toShort()
        }
        return out
    }

    fun start() {
        stop()
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION) // ducks under speech
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(period.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(period, 0, period.size)
        t.setLoopPoints(0, period.size, -1) // −1 = loop forever, in hardware
        t.play()
        track = t
    }

    fun stop() {
        track?.let { runCatching { it.pause(); it.flush(); it.release() } }
        track = null
    }
}
