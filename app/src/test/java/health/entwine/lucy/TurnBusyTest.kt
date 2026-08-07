package health.entwine.lucy

import health.entwine.lucy.proto.ServerMsg
import health.entwine.lucy.proto.parseServerMsg
import health.entwine.lucy.proto.CrisisTarget
import health.entwine.lucy.state.AppState
import health.entwine.lucy.state.Action
import health.entwine.lucy.state.Event
import health.entwine.lucy.state.reduce
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WS v1.11 §8.1a — `turn.busy` is "not yet", not an error.
 *
 * The point of the message is what it must NOT do. The old `E_SESSION_STATE`
 * answer took the client through RecoverableError, which from Responding fires
 * TTS_STOP and killed the reply the person was still listening to (#22a).
 */
class TurnBusyTest {
    @Test
    fun `turn busy parses as its own type, never as an error`() {
        val msg = parseServerMsg("""{"t":"turn.busy","reason":"turn_in_flight"}""")
        assertTrue(msg is ServerMsg.TurnBusy, "parsed as $msg")
        assertEquals("turn_in_flight", (msg as ServerMsg.TurnBusy).reason)
    }

    @Test
    fun `an unknown reason still parses rather than falling over`() {
        val msg = parseServerMsg("""{"t":"turn.busy"}""")
        assertTrue(msg is ServerMsg.TurnBusy)
    }

    @Test
    fun `the path turn busy must never take would stop playback`() {
        // Guards the reason TurnBusy is handled without a dispatch: this is what
        // treating it as a recoverable error would have done mid-reply.
        val targets = listOf(CrisisTarget("crisis_eran", "1201"))
        val t = reduce(AppState.Responding, Event.RecoverableError, targets)
        assertTrue(Action.TTS_STOP in t.actions, "expected the audio-killing action here")
        assertEquals(AppState.IdleReady, t.next)
    }
}
