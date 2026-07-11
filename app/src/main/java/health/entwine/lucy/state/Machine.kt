// The SDD_App_State_Matrix_v1 truth table as a pure reducer.
// Every cell in the spec has a branch here; a new event/state without a spec row
// fails review (matrix §4). Pure Kotlin — unit-tested on the JVM.
package health.entwine.lucy.state

import health.entwine.lucy.proto.CrisisTarget

/** Exactly one visible at all times (R-LOOP-07). */
sealed interface AppState {
    data object IdleReady : AppState
    data object Recording : AppState
    data object Processing : AppState
    data object Responding : AppState
    data class Crisis(val targets: List<CrisisTarget>) : AppState
    data object Closing : AppState
    data object Offline : AppState
}

/** Everything that can happen, UI + network lanes (matrix §2). */
sealed interface Event {
    data object TapTalk : Event
    data object TapStop : Event // recording stop OR playback skip, context-dependent
    data object VadSilence : Event
    data object MaxUtterance : Event
    data object ServerEndpoint : Event // utterance.endpoint — wrap fast-path (WS v1.6, R-LOOP-10)
    data object SpeechBargeIn : Event // monitor mic heard the user during playback (ADR-0013)
    data object TurnCancelled : Event // turn.cancelled — barge-in acknowledged, lock released
    data class TextSend(val text: String) : Event
    data object EndSessionTap : Event
    data object CrisisShown : Event // crisis.show received — carries targets via ctx
    data object CrisisContinueTap : Event
    data object FirstTtsChunk : Event
    data object TtsPlaybackDone : Event
    data object RecoverableError : Event
    data object WsDrop : Event
    data object ReconnectOk : Event
    data object SessionSaved : Event
    data object SavedTimeout : Event
    data object AppBackground : Event
    data object MicPermissionRevoked : Event
}

/** Cleanup vocabulary from matrix §2 — executed by the runtime, decided here. */
enum class Action {
    MIC_ON, MIC_OFF, FLUSH_MIC, DROP_MIC, TTS_STOP, SEND_UTTER_START,
    SEND_UTTER_END_VAD, SEND_UTTER_END_TAP, SEND_UTTER_END_WRAP, SEND_TEXT, SEND_CLOSE,
    PERSIST_TEXT, RECONNECT, EXIT_APP, SUPPRESS_REPLY, SEND_BARGE_IN,
}

data class Transition(val next: AppState, val actions: List<Action>)

/** crisis targets provided out-of-band (cached from last session.ready, §5.3). */
fun reduce(state: AppState, event: Event, crisisTargets: List<CrisisTarget>): Transition {
    // I3: crisis.show honored in EVERY connected state, immediate, unconditional.
    if (event is Event.CrisisShown && state !is AppState.Closing) {
        val cleanup = buildList {
            if (state is AppState.Recording) { add(Action.MIC_OFF); add(Action.DROP_MIC) }
            // The hard cell: stop audio FIRST, discard queue, never replay (matrix §3.1).
            add(Action.TTS_STOP)
            add(Action.SUPPRESS_REPLY)
        }
        return Transition(AppState.Crisis(crisisTargets), cleanup)
    }

    return when (state) {
        AppState.IdleReady -> when (event) {
            Event.TapTalk -> Transition(
                AppState.Recording, listOf(Action.SEND_UTTER_START, Action.MIC_ON)
            )
            is Event.TextSend -> Transition(AppState.Processing, listOf(Action.SEND_TEXT))
            Event.EndSessionTap -> Transition(AppState.Closing, listOf(Action.SEND_CLOSE))
            Event.WsDrop -> Transition(
                AppState.Offline, listOf(Action.PERSIST_TEXT, Action.RECONNECT)
            )
            else -> stay(state)
        }

        AppState.Recording -> when (event) {
            Event.VadSilence -> Transition(
                AppState.Processing, listOf(Action.MIC_OFF, Action.SEND_UTTER_END_VAD)
            )
            Event.TapStop -> Transition(
                AppState.Processing, listOf(Action.MIC_OFF, Action.SEND_UTTER_END_TAP)
            )
            Event.MaxUtterance -> Transition( // 90 s runaway-mic guard
                AppState.Processing, listOf(Action.MIC_OFF, Action.SEND_UTTER_END_TAP)
            )
            Event.ServerEndpoint -> Transition( // wrap fast-path (matrix v1.2, R-LOOP-10)
                AppState.Processing, listOf(Action.MIC_OFF, Action.SEND_UTTER_END_WRAP)
            )
            // The hard cell: drop mid-utterance = deliberate discard, user re-speaks (§3.3).
            Event.WsDrop -> Transition(
                AppState.Offline,
                listOf(Action.MIC_OFF, Action.DROP_MIC, Action.PERSIST_TEXT, Action.RECONNECT),
            )
            Event.AppBackground -> Transition( // never record in background
                AppState.IdleReady, listOf(Action.MIC_OFF, Action.DROP_MIC)
            )
            Event.MicPermissionRevoked -> Transition(
                AppState.IdleReady, listOf(Action.MIC_OFF, Action.DROP_MIC)
            )
            else -> stay(state)
        }

        AppState.Processing -> when (event) {
            Event.FirstTtsChunk -> Transition(AppState.Responding, emptyList())
            Event.RecoverableError -> Transition(AppState.IdleReady, emptyList())
            Event.WsDrop -> Transition(
                AppState.Offline, listOf(Action.PERSIST_TEXT, Action.RECONNECT)
            )
            Event.TtsPlaybackDone -> Transition(AppState.IdleReady, emptyList()) // text-only turn
            else -> stay(state)
        }

        AppState.Responding -> when (event) {
            Event.TtsPlaybackDone -> Transition(AppState.IdleReady, emptyList())
            Event.TapStop -> Transition(AppState.IdleReady, listOf(Action.TTS_STOP)) // skip ≠ error
            // Barge-in pair (matrix v1.2, ADR-0013): stop audio + ask the server to
            // cancel, but the mic must NOT start until turn.cancelled (WS §8.1).
            Event.SpeechBargeIn -> Transition(
                state, listOf(Action.TTS_STOP, Action.SUPPRESS_REPLY, Action.SEND_BARGE_IN)
            )
            Event.TurnCancelled -> Transition(
                AppState.Recording, listOf(Action.SEND_UTTER_START, Action.MIC_ON)
            )
            Event.WsDrop -> Transition( // play out buffer while reconnecting (§2)
                AppState.Offline, listOf(Action.PERSIST_TEXT, Action.RECONNECT)
            )
            Event.RecoverableError -> Transition(AppState.IdleReady, listOf(Action.TTS_STOP))
            else -> stay(state)
        }

        is AppState.Crisis -> when (event) {
            // Continue is client-local; suppressed reply is never replayed (§2 CRISIS).
            Event.CrisisContinueTap -> Transition(AppState.IdleReady, emptyList())
            // ws_drop: screen persists, reconnect silently (targets already cached).
            Event.WsDrop -> Transition(state, listOf(Action.RECONNECT))
            else -> stay(state) // sticky: background/return keeps the crisis screen
        }

        AppState.Closing -> when (event) {
            Event.SessionSaved -> Transition(AppState.Closing, listOf(Action.EXIT_APP))
            Event.SavedTimeout -> Transition(AppState.Closing, listOf(Action.EXIT_APP))
            else -> stay(state)
        }

        AppState.Offline -> when (event) {
            Event.ReconnectOk -> Transition(AppState.IdleReady, emptyList())
            Event.EndSessionTap -> Transition(AppState.Closing, listOf(Action.EXIT_APP))
            else -> stay(state) // text_send refused with copy; text persists (I2)
        }
    }
}

private fun stay(state: AppState) = Transition(state, emptyList())
