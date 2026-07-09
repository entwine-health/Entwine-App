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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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

/** Streamed reply playback: first chunk starts audio immediately (R-LAT-03). */
class Player(private val codec: WireCodec) {
    private var track: AudioTrack? = null

    fun begin(sampleRate: Int) {
        stop()
        track = AudioTrack.Builder()
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
    }

    fun feed(payload: ByteArray) {
        val pcm = codec.decode(payload)
        track?.write(pcm, 0, pcm.size)
    }

    /** Hard cell §3.1: crisis stops audio *first* and discards — never pause. */
    fun stop() {
        track?.let {
            runCatching { it.pause(); it.flush(); it.stop() }
            it.release()
        }
        track = null
    }
}
