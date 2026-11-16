package com.samourai.sentinel.ui.dojo

import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.ui.dojo.CommunityDojoTorWait.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityDojoTorWaitTest {

    /** Feeds a Tor state sequence through the sheet's rule and returns the actions. */
    private fun run(vararg states: EnumTorState): List<Action> {
        var saw = false
        return states.map { s ->
            saw = CommunityDojoTorWait.sawStart(s, saw)
            CommunityDojoTorWait.onState(s, saw)
        }
    }

    @Test
    fun `cold start - the stale OFF before Tor starts is a wait, not a failure`() {
        assertEquals(
            listOf(Action.WAIT, Action.WAIT, Action.FETCH_WHEN_READY),
            run(EnumTorState.OFF, EnumTorState.STARTING, EnumTorState.ON),
        )
    }

    @Test
    fun `Tor that starts and then stops is a failure`() {
        assertEquals(
            listOf(Action.WAIT, Action.WAIT, Action.WAIT, Action.FAIL),
            run(EnumTorState.OFF, EnumTorState.STARTING, EnumTorState.STOPPING, EnumTorState.OFF),
        )
        assertEquals(Action.FAIL, run(EnumTorState.ON, EnumTorState.OFF).last())
    }

    @Test
    fun `Tor that never starts fails after the grace period`() {
        assertTrue(CommunityDojoTorWait.failsAfterGrace(sawStart = false, torReady = false, fetchStarted = false))
        assertFalse(CommunityDojoTorWait.failsAfterGrace(sawStart = true, torReady = false, fetchStarted = false))
        assertFalse(CommunityDojoTorWait.failsAfterGrace(sawStart = false, torReady = true, fetchStarted = false))
        assertFalse(CommunityDojoTorWait.failsAfterGrace(sawStart = false, torReady = false, fetchStarted = true))
        assertTrue(CommunityDojoTorWait.START_GRACE_MS in 10_000L..60_000L)
    }

    @Test
    fun `Waiting for Tor has a limit even when Tor started`() {
        // Started but stuck or slow: fails at the limit.
        assertTrue(CommunityDojoTorWait.failsAfterReadyLimit(torReady = false, fetchStarted = false))
        assertFalse(CommunityDojoTorWait.failsAfterReadyLimit(torReady = true, fetchStarted = false))
        assertFalse(CommunityDojoTorWait.failsAfterReadyLimit(torReady = false, fetchStarted = true))
        assertEquals(120_000L, CommunityDojoTorWait.READY_LIMIT_MS)
        assertTrue(CommunityDojoTorWait.READY_LIMIT_MS > CommunityDojoTorWait.START_GRACE_MS)
    }
}
