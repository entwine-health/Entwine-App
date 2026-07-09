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
