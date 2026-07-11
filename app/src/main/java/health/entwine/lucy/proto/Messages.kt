// JSON protocol messages per SDD_WS_Protocol_v1 §3/§4 — "t"-keyed frames.
// Parsing is tolerant: unknown fields ignored, unknown types surface as Unknown
// (forward compatibility; the app never crashes on a new server message).
package health.entwine.lucy.proto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

val json = Json { ignoreUnknownKeys = true }

data class CrisisTarget(val labelKey: String, val phone: String)

data class SessionConfig(
    val crisisTargets: List<CrisisTarget>,
    val softCap: Int,
    val vadSilenceMs: Int,
    val endpointDefault: String,
    val showSttEcho: Boolean = true,
    val minAppVersion: String = "0.0.0",
    // WS v1.6 (ADR-0013): "tap" | "speech" + monitor tuning, all server-owned.
    val bargeIn: String = "tap",
    val bargeInMinSpeechMs: Int = 300,
    val bargeInRmsThreshold: Int = 1600,
)

/** Server → client messages the app reacts to (SDD §4). */
sealed interface ServerMsg {
    data class Ready(
        val sessionId: String,
        val nextExchangeSeq: Int,
        val resumed: Boolean,
        val config: SessionConfig,
    ) : ServerMsg

    data class SttFinal(val exchangeSeq: Int, val text: String) : ServerMsg
    data class ReplyDelta(val exchangeSeq: Int, val text: String) : ServerMsg
    data class ReplyDone(val exchangeSeq: Int) : ServerMsg
    data class UtteranceEndpoint(val reason: String) : ServerMsg // WS v1.6, R-LOOP-10
    data class TurnCancelled(val exchangeSeq: Int) : ServerMsg // WS v1.6, barge-in ack
    data class TtsBegin(val exchangeSeq: Int, val sampleRate: Int) : ServerMsg
    data class TtsDone(val exchangeSeq: Int) : ServerMsg
    data class MotorAck(val textKey: String) : ServerMsg
    data class CrisisShow(val exchangeSeq: Int, val targets: List<CrisisTarget>) : ServerMsg
    data class CapSuggest(val exchangeSeq: Int) : ServerMsg
    data class Saved(val sessionId: String, val exchangeCount: Int) : ServerMsg
    data class Error(
        val code: String,
        val recoverable: Boolean,
        val userMessageKey: String,
        val retryAfterMs: Long?,
    ) : ServerMsg

    data class Unknown(val t: String) : ServerMsg
}

private fun targets(obj: JsonObject, key: String): List<CrisisTarget> =
    obj[key]?.jsonArray?.map {
        val o = it.jsonObject
        CrisisTarget(
            o["label_key"]?.jsonPrimitive?.content ?: "",
            o["phone"]?.jsonPrimitive?.content ?: "",
        )
    } ?: emptyList()

fun parseServerMsg(text: String): ServerMsg {
    val obj = json.parseToJsonElement(text).jsonObject
    val seq = { obj["exchange_seq"]?.jsonPrimitive?.int ?: 0 }
    return when (val t = obj["t"]?.jsonPrimitive?.content ?: "") {
        "session.ready" -> {
            val cfg = obj["config"]?.jsonObject
            ServerMsg.Ready(
                sessionId = obj["session_id"]?.jsonPrimitive?.content ?: "",
                nextExchangeSeq = obj["next_exchange_seq"]?.jsonPrimitive?.int ?: 0,
                resumed = obj["resumed"]?.jsonPrimitive?.boolean ?: false,
                config = SessionConfig(
                    crisisTargets = cfg?.let { targets(it, "crisis_targets") } ?: emptyList(),
                    softCap = cfg?.get("soft_cap")?.jsonPrimitive?.int ?: 9,
                    vadSilenceMs = cfg?.get("vad_silence_ms")?.jsonPrimitive?.int ?: 2000,
                    endpointDefault = cfg?.get("endpoint_default")?.jsonPrimitive?.content
                        ?: "vad",
                    showSttEcho = cfg?.get("show_stt_echo")?.jsonPrimitive?.boolean ?: true,
                    minAppVersion = cfg?.get("min_app_version")?.jsonPrimitive?.content
                        ?: "0.0.0",
                    bargeIn = cfg?.get("barge_in")?.jsonPrimitive?.content ?: "tap",
                    bargeInMinSpeechMs = cfg?.get("barge_in_min_speech_ms")
                        ?.jsonPrimitive?.int ?: 300,
                    bargeInRmsThreshold = cfg?.get("barge_in_rms_threshold")
                        ?.jsonPrimitive?.int ?: 1600,
                ),
            )
        }
        "stt.final" -> ServerMsg.SttFinal(seq(), obj["text"]?.jsonPrimitive?.content ?: "")
        "reply.delta" -> ServerMsg.ReplyDelta(seq(), obj["text"]?.jsonPrimitive?.content ?: "")
        "reply.done" -> ServerMsg.ReplyDone(seq())
        "tts.begin" -> ServerMsg.TtsBegin(
            seq(), obj["sample_rate"]?.jsonPrimitive?.int ?: 24000
        )
        "tts.done" -> ServerMsg.TtsDone(seq())
        "utterance.endpoint" -> ServerMsg.UtteranceEndpoint(
            obj["reason"]?.jsonPrimitive?.content ?: "wrap_phrase"
        )
        "turn.cancelled" -> ServerMsg.TurnCancelled(seq())
        "motor.ack" -> ServerMsg.MotorAck(obj["text_key"]?.jsonPrimitive?.content ?: "")
        "crisis.show" -> ServerMsg.CrisisShow(seq(), targets(obj, "targets"))
        "cap.suggest" -> ServerMsg.CapSuggest(seq())
        "session.saved" -> ServerMsg.Saved(
            obj["session_id"]?.jsonPrimitive?.content ?: "",
            obj["exchange_count"]?.jsonPrimitive?.int ?: 0,
        )
        "error" -> ServerMsg.Error(
            code = obj["code"]?.jsonPrimitive?.content ?: "E_UNKNOWN",
            recoverable = obj["recoverable"]?.jsonPrimitive?.boolean ?: true,
            userMessageKey = obj["user_message_key"]?.jsonPrimitive?.content ?: "err_generic",
            retryAfterMs = obj["retry_after_ms"]?.jsonPrimitive?.content?.toLongOrNull(),
        )
        else -> ServerMsg.Unknown(t)
    }
}

/** Client → server builders (SDD §3). */
object ClientMsg {
    fun sessionOpen(appVersion: String, resumeSessionId: String?): String = buildJsonObject {
        put("t", "session.open")
        put("protocol_version", 1)
        put("app_version", appVersion)
        if (resumeSessionId != null) put("resume_session_id", resumeSessionId)
    }.toString()

    fun utteranceStart(): String = buildJsonObject {
        put("t", "utterance.start"); put("mode", "voice")
    }.toString()

    fun utteranceEnd(audioMs: Long, reason: String): String = buildJsonObject {
        put("t", "utterance.end"); put("client_audio_ms", audioMs); put("endpoint_reason", reason)
    }.toString()

    fun textMessage(text: String): String = buildJsonObject {
        put("t", "text.message"); put("text", text.take(2000))
    }.toString()

    fun motorTap(state: String, clientTs: String): String = buildJsonObject {
        put("t", "motor.tap"); put("state", state); put("client_ts", clientTs)
    }.toString()

    fun ackPlaybackStart(exchangeSeq: Int, clientTs: String): String = buildJsonObject {
        put("t", "ack.playback_start"); put("exchange_seq", exchangeSeq); put("client_ts", clientTs)
    }.toString()

    fun bargeIn(exchangeSeq: Int): String = buildJsonObject {
        put("t", "barge_in"); put("exchange_seq", exchangeSeq)
    }.toString()

    fun sessionClose(): String = buildJsonObject {
        put("t", "session.close"); put("reason", "user_ended")
    }.toString()
}
