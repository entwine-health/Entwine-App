// The escapes from server-dependent states (matrix I5, WS §7) — the cells that
// existed in the reducer but that nothing ever fired until 2026-07-17.
// Verifies: TC-03 slice (no state may depend on the server without an exit).
package health.entwine.lucy

import health.entwine.lucy.proto.CrisisTarget
import health.entwine.lucy.state.Action
import health.entwine.lucy.state.AppState
import health.entwine.lucy.state.Event
import health.entwine.lucy.state.reduce
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val TARGETS = listOf(CrisisTarget("crisis_eran", "1201"))

class WatchdogTest {

    @Test fun `phase timeout releases processing to idle`() {
        // The live hang: PROCESSING with a dead socket, button inert forever.
        val t = reduce(AppState.Processing, Event.RecoverableError, TARGETS)
        assertEquals(AppState.IdleReady, t.next)
    }

    @Test fun `saved timeout exits closing`() {
        // End-session wedged identically — session.close went nowhere.
        val t = reduce(AppState.Closing, Event.SavedTimeout, TARGETS)
        assertEquals(AppState.Closing, t.next)
        assertTrue(Action.EXIT_APP in t.actions)
    }

    @Test fun `undeliverable send drops processing to offline with retry`() {
        // An unsent frame is a dead socket: Offline shows the retry affordance,
        // and composed text survives (I2).
        val t = reduce(AppState.Processing, Event.WsDrop, TARGETS)
        assertEquals(AppState.Offline, t.next)
        assertTrue(Action.RECONNECT in t.actions)
        assertTrue(Action.PERSIST_TEXT in t.actions)
    }

    @Test fun `every server-dependent state has a local exit`() {
        // Guard against a future state that can only be left by a server message.
        val processingExits = listOf(
            Event.RecoverableError, Event.WsDrop, Event.FirstTtsChunk, Event.TtsPlaybackDone,
        ).map { reduce(AppState.Processing, it, TARGETS).next }
        assertTrue(processingExits.any { it != AppState.Processing })
        val closingExits = listOf(Event.SavedTimeout, Event.SessionSaved)
            .map { reduce(AppState.Closing, it, TARGETS) }
        assertTrue(closingExits.all { Action.EXIT_APP in it.actions })
    }
}
