package com.samourai.sentinel.ui.home

import com.samourai.sentinel.core.DojoContact
import com.samourai.sentinel.core.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A standing transport failure starts one quiet round from Home resume
 * or from a flip to Reachable. The failure post itself does not start
 * another, and a second Reachable is not a new flip.
 */
class QuietResyncTest {

    private val transport = SyncState.Failed(SyncRoundTerminal.DOJO_UNREACHABLE, retryable = true)
    private val local = SyncState.Failed(SyncRoundTerminal.syncIncompleteReason(1, 2), retryable = true)

    @Test
    fun standingTransportWithTorReadyStartsOnceAndDoesNotLoop() {
        assertTrue(QuietResync.shouldStart(transport, torReady = true, roundInFlight = false))
        assertFalse(QuietResync.shouldStart(transport, torReady = true, roundInFlight = true))
        assertFalse(QuietResync.shouldStart(transport, torReady = false, roundInFlight = false))
        assertFalse(QuietResync.shouldStart(local, torReady = true, roundInFlight = false))
        assertFalse(QuietResync.shouldStart(SyncState.Success(1L), torReady = true, roundInFlight = false))
        assertFalse(QuietResync.shouldStart(null, torReady = true, roundInFlight = false))
        // The round that just ended can still be a transport failure.
        // The gate would allow another start, so the failure post must not ask.
        assertTrue(QuietResync.shouldStart(transport, torReady = true, roundInFlight = false))
    }

    @Test
    fun aSecondReachableIsNotAFlip() {
        val first = DojoContact.Reachable(1L)
        val again = DojoContact.Reachable(2L)
        assertTrue(becameReachable(DojoContact.Unknown, first))
        assertTrue(becameReachable(DojoContact.Unreachable(1L, "socks"), first))
        assertTrue(becameReachable(DojoContact.DojoError(1L, 500, "http"), first))
        assertFalse(becameReachable(first, again))
        assertFalse(becameReachable(first, DojoContact.Unreachable(3L, "timeout")))
        assertFalse(becameReachable(DojoContact.Unknown, DojoContact.Unknown))
    }

    @Test
    fun resumeAndTheReachableFlipAreTheOnlyStarts() {
        assertEquals(
            setOf(QuietResyncArm.HOME_RESUMED, QuietResyncArm.REACHABILITY),
            QuietResyncArm.entries.toSet(),
        )
        assertTrue(quietResyncArmed(QuietResyncArm.HOME_RESUMED, flippedToReachable = false))
        assertTrue(
            quietResyncArmed(
                QuietResyncArm.REACHABILITY,
                becameReachable(DojoContact.Unreachable(1L, "socks"), DojoContact.Reachable(2L)),
            ),
        )
        assertFalse(
            quietResyncArmed(
                QuietResyncArm.REACHABILITY,
                becameReachable(DojoContact.Reachable(1L), DojoContact.Reachable(2L)),
            ),
        )
        assertFalse(
            quietResyncArmed(
                QuietResyncArm.REACHABILITY,
                becameReachable(DojoContact.Unknown, DojoContact.Unreachable(3L, "timeout")),
            ),
        )
        // A finished transport failure would pass shouldStart. The only
        // arms are resume and a reachability flip, so the failure post
        // of a sync round is not one of them.
        assertTrue(QuietResync.shouldStart(transport, torReady = true, roundInFlight = false))
    }
}
