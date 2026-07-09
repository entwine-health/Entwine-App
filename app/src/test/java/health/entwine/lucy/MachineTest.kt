// The SDD matrix cells as JVM tests — incl. the three hard cells (§3).
// Verifies: TC-03/04/05/06 slices (client-side transitions).
package health.entwine.lucy

import health.entwine.lucy.proto.CrisisTarget
import health.entwine.lucy.state.Action
import health.entwine.lucy.state.AppState
import health.entwine.lucy.state.Event
import health.entwine.lucy.state.reduce
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val TARGETS = listOf(CrisisTarget("crisis_eran", "1201"))

class MachineTest {

    @Test fun `talk starts recording with mic actions`() {
        val t = reduce(AppState.IdleReady, Event.TapTalk, TARGETS)
        assertEquals(AppState.Recording, t.next)
        assertEquals(listOf(Action.SEND_UTTER_START, Action.MIC_ON), t.actions)
    }

    @Test fun `vad silence ends utterance into processing`() {
        val t = reduce(AppState.Recording, Event.VadSilence, TARGETS)
        assertEquals(AppState.Processing, t.next)
        assertTrue(Action.SEND_UTTER_END_VAD in t.actions)
    }

    // Hard cell §3.1 — crisis during active TTS: stop first, discard, suppress.
    @Test fun `crisis during responding stops tts and suppresses reply`() {
        val t = reduce(AppState.Responding, Event.CrisisShown, TARGETS)
        assertIs<AppState.Crisis>(t.next)
        assertTrue(t.actions.indexOf(Action.TTS_STOP) >= 0)
        assertTrue(Action.SUPPRESS_REPLY in t.actions)
    }

    // Hard cell §3.2 — motor tap mid-recording never touches capture: the tap
    // is out-of-band by design; the reducer has NO motor event at all.
    @Test fun `recording survives every non-matrix event untouched`() {
        val t = reduce(AppState.Recording, Event.ReconnectOk, TARGETS)
        assertEquals(AppState.Recording, t.next)
        assertTrue(t.actions.isEmpty())
    }

    // Hard cell §3.3 — drop mid-utterance discards audio, never re-sends.
    @Test fun `ws drop while recording drops mic deliberately`() {
        val t = reduce(AppState.Recording, Event.WsDrop, TARGETS)
        assertEquals(AppState.Offline, t.next)
        assertTrue(Action.DROP_MIC in t.actions)
        assertTrue(Action.FLUSH_MIC !in t.actions)
    }

    @Test fun `crisis is honored from every connected state`() {
        for (s in listOf(
            AppState.IdleReady, AppState.Recording, AppState.Processing,
            AppState.Responding, AppState.Offline,
        )) {
            val t = reduce(s, Event.CrisisShown, TARGETS)
            assertIs<AppState.Crisis>(t.next, "from $s")
        }
    }

    @Test fun `crisis screen is sticky except explicit continue`() {
        val crisis = AppState.Crisis(TARGETS)
        assertEquals(crisis, reduce(crisis, Event.AppBackground, TARGETS).next)
        assertEquals(crisis, reduce(crisis, Event.WsDrop, TARGETS).next)
        assertEquals(AppState.IdleReady, reduce(crisis, Event.CrisisContinueTap, TARGETS).next)
    }

    @Test fun `closing exits on saved or timeout`() {
        assertTrue(Action.EXIT_APP in reduce(AppState.Closing, Event.SessionSaved, TARGETS).actions)
        assertTrue(Action.EXIT_APP in reduce(AppState.Closing, Event.SavedTimeout, TARGETS).actions)
    }

    @Test fun `background kills recording without flush`() {
        val t = reduce(AppState.Recording, Event.AppBackground, TARGETS)
        assertEquals(AppState.IdleReady, t.next)
        assertTrue(Action.DROP_MIC in t.actions)
    }

    @Test fun `skip playback is not an error`() {
        val t = reduce(AppState.Responding, Event.TapStop, TARGETS)
        assertEquals(AppState.IdleReady, t.next)
        assertEquals(listOf(Action.TTS_STOP), t.actions)
    }
}
