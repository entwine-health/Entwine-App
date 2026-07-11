// Wire envelope round-trip per SDD_WS_Protocol §2. Verifies: TC-01 client slice.
package health.entwine.lucy

import health.entwine.lucy.proto.FRAME_MIC
import health.entwine.lucy.proto.FRAME_TTS
import health.entwine.lucy.proto.decodeFrame
import health.entwine.lucy.proto.encodeFrame
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FramesTest {

    @Test fun `mic frame round trips`() {
        val payload = ByteArray(60) { it.toByte() }
        val f = decodeFrame(encodeFrame(FRAME_MIC, 7, payload))
        assertEquals(FRAME_MIC, f.type)
        assertEquals(7L, f.seq)
        assertContentEquals(payload, f.payload)
    }

    @Test fun `envelope is little endian uint32`() {
        val bytes = encodeFrame(FRAME_TTS, 0x01020304L, ByteArray(0))
        assertEquals(0x81.toByte(), bytes[0])
        assertContentEquals(byteArrayOf(0x04, 0x03, 0x02, 0x01), bytes.copyOfRange(1, 5))
    }

    @Test fun `seq survives full uint32 range`() {
        val f = decodeFrame(encodeFrame(FRAME_MIC, 0xFFFFFFFFL, byteArrayOf(1)))
        assertEquals(0xFFFFFFFFL, f.seq)
    }

    @Test fun `short frame rejected`() {
        assertFailsWith<IllegalArgumentException> { decodeFrame(byteArrayOf(1, 2, 3)) }
    }
}

// ---- WS v1.6 message parsing -------------------------------------------------

class MessagesV16Test {
    @Test fun `utterance endpoint and turn cancelled parse`() {
        val ep = health.entwine.lucy.proto.parseServerMsg(
            """{"t":"utterance.endpoint","reason":"wrap_phrase"}"""
        )
        kotlin.test.assertTrue(ep is health.entwine.lucy.proto.ServerMsg.UtteranceEndpoint)
        val tc = health.entwine.lucy.proto.parseServerMsg(
            """{"t":"turn.cancelled","exchange_seq":4}"""
        )
        kotlin.test.assertEquals(
            4, (tc as health.entwine.lucy.proto.ServerMsg.TurnCancelled).exchangeSeq
        )
    }

    @Test fun `ready parses barge-in config with defaults`() {
        val ready = health.entwine.lucy.proto.parseServerMsg(
            """{"t":"session.ready","session_id":"s","next_exchange_seq":0,"resumed":false,
               "config":{"soft_cap":9,"vad_silence_ms":2000,"endpoint_default":"vad",
               "barge_in":"speech","barge_in_min_speech_ms":250,"barge_in_rms_threshold":1800}}"""
        ) as health.entwine.lucy.proto.ServerMsg.Ready
        kotlin.test.assertEquals("speech", ready.config.bargeIn)
        kotlin.test.assertEquals(250, ready.config.bargeInMinSpeechMs)
        val defaults = health.entwine.lucy.proto.parseServerMsg(
            """{"t":"session.ready","session_id":"s","next_exchange_seq":0,"resumed":false,
               "config":{"soft_cap":9,"vad_silence_ms":2000,"endpoint_default":"vad"}}"""
        ) as health.entwine.lucy.proto.ServerMsg.Ready
        kotlin.test.assertEquals("tap", defaults.config.bargeIn)
    }

    @Test fun `barge in builder emits contract shape`() {
        val json = health.entwine.lucy.proto.ClientMsg.bargeIn(7)
        kotlin.test.assertTrue(""""t":"barge_in"""" in json && """"exchange_seq":7""" in json)
    }
}
