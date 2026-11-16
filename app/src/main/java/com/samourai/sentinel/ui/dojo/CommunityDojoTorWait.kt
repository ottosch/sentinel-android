package com.samourai.sentinel.ui.dojo

import com.samourai.sentinel.tor.EnumTorState

/**
 * How the Community Dojos sheet reacts to Tor's state while it waits to
 * fetch. Right after the sheet asks Tor to start on a cold start, Tor still
 * reports OFF for a few seconds, so OFF alone is not a failure: the sheet
 * shows "Waiting for Tor…". OFF counts as a failure once Tor has been seen
 * starting (it started, then stopped), when Tor never starts within
 * [START_GRACE_MS], or when Tor started but is still not ready (bootstrap
 * stuck, or a fresh bootstrap that is taking minutes) at [READY_LIMIT_MS].
 * The wait then ends in the usual Tor failure state (the saved list with
 * the unreachable banner, or the message) with Retry. The sheet keeps
 * listening, so if Tor finishes later the fetch still starts on its own.
 */
object CommunityDojoTorWait {
    const val START_GRACE_MS = 30_000L

    /**
     * Total "Waiting for Tor…" time, counted from the same start as
     * [START_GRACE_MS]. Tor ready from its cache takes 1-4 s and a healthy
     * fresh bootstrap well under a minute; Tor's own stall watchdog
     * finishes its restarts and wipes in about 105 s. 120 s lets that
     * recovery finish and matches the normal fetch limit.
     */
    const val READY_LIMIT_MS = 120_000L

    enum class Action { WAIT, FETCH_WHEN_READY, FAIL }

    /** True once Tor has been seen STARTING or ON since the sheet asked it to start. */
    fun sawStart(state: EnumTorState, sawStartBefore: Boolean): Boolean =
        sawStartBefore || state == EnumTorState.STARTING || state == EnumTorState.ON

    fun onState(state: EnumTorState, sawStart: Boolean): Action = when (state) {
        EnumTorState.ON -> Action.FETCH_WHEN_READY
        EnumTorState.OFF -> if (sawStart) Action.FAIL else Action.WAIT
        EnumTorState.STARTING, EnumTorState.STOPPING -> Action.WAIT
    }

    /** At the end of the grace period: fail only if Tor never started and no fetch began. */
    fun failsAfterGrace(sawStart: Boolean, torReady: Boolean, fetchStarted: Boolean): Boolean =
        !sawStart && !torReady && !fetchStarted

    /** At [READY_LIMIT_MS]: fail if Tor is still not ready and no fetch began, whether or not it started. */
    fun failsAfterReadyLimit(torReady: Boolean, fetchStarted: Boolean): Boolean =
        !torReady && !fetchStarted

    /**
     * At [READY_LIMIT_MS], when [failsAfterReadyLimit] holds: the bootstrap
     * percentage to show as "Tor is still starting (n%)…" while Tor is past
     * 0 % and not yet ready (a slow first bootstrap can sit at one step for
     * minutes, #581). Null when Tor is off, stopping or still at 0 %: the
     * sheet then shows the real "Tor is not connected" failure. Retry and
     * Change Dojo Bay address stay available either way.
     */
    fun stillStartingProgress(state: EnumTorState, progress: Int): Int? =
        if ((state == EnumTorState.STARTING || state == EnumTorState.ON) && progress in 1..99) {
            progress
        } else {
            null
        }
}
