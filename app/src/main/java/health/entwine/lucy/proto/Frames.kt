// Binary frame envelope per SDD_WS_Protocol_v1 §2.
// [1B type][4B uint32 LE seq][payload]. 0x01 = mic→server, 0x81 = TTS→client.
package health.entwine.lucy.proto

import java.nio.ByteBuffer
import java.nio.ByteOrder

const val FRAME_MIC: Int = 0x01
const val FRAME_TTS: Int = 0x81
const val HEADER_LEN: Int = 5

data class Frame(val type: Int, val seq: Long, val payload: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is Frame && type == other.type && seq == other.seq &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * (31 * type + seq.toInt()) + payload.contentHashCode()
}

fun encodeFrame(type: Int, seq: Long, payload: ByteArray): ByteArray {
    val buf = ByteBuffer.allocate(HEADER_LEN + payload.size).order(ByteOrder.LITTLE_ENDIAN)
    buf.put(type.toByte())
    buf.putInt(seq.toInt()) // uint32 LE — Kotlin Int reinterpreted on the wire
    buf.put(payload)
    return buf.array()
}

fun decodeFrame(data: ByteArray): Frame {
    require(data.size >= HEADER_LEN) { "frame shorter than envelope" }
    val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    val type = buf.get().toInt() and 0xFF
    val seq = buf.int.toLong() and 0xFFFFFFFFL
    val payload = ByteArray(data.size - HEADER_LEN)
    buf.get(payload)
    return Frame(type, seq, payload)
}
