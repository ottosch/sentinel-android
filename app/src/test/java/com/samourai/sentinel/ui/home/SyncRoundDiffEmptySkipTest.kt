package com.samourai.sentinel.ui.home

import com.samourai.sentinel.core.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A deletion or a reorder is a [SyncRoundPlan.Skip]. The arm stamps
 * both signatures and does not post Success over a standing Failed.
 * An all-off-network round is a different decision: Success when an
 * endpoint exists, and it does not go through the diff planner.
 */
class SyncRoundDiffEmptySkipTest {

    @Test
    fun launchSyncRoundExecutesThePlanAndSkipsWithStamps() {
        val signature = "a:k1"
        val failed = SyncState.Failed("Could not reach Dojo.", retryable = true)
        val skipFailed = diffEmptySkip(signature, failed)
        assertEquals(signature, skipFailed.signature)
        assertNull(skipFailed.postedState)

        val success = diffEmptySkip(signature, SyncState.Success(1L))
        assertNull(success.postedState)
        assertFalse(success.postedState is SyncState.Success)

        val waiting = diffEmptySkip(signature, SyncState.WaitingForTor(40))
        assertEquals(SyncState.Idle, waiting.postedState)
        assertEquals(signature, waiting.signature)
    }

    @Test
    fun theEmptyInRoundBranchStaysForOffNetworkRounds() {
        val now = 1_700_000_000_000L
        assertEquals(
            SyncState.Success(now),
            emptyOnNetworkRoundState(endpointEnabled = true, standingFailed = false, now = now),
        )
        assertEquals(
            SyncState.Idle,
            emptyOnNetworkRoundState(endpointEnabled = false, standingFailed = false, now = now),
        )
        assertNull(
            emptyOnNetworkRoundState(endpointEnabled = false, standingFailed = true, now = now),
        )
        // A removal is a skip, not this empty on-network ending.
        val removed = SyncRoundPlan.decide(
            lastSignature = "a:k1|b:k2",
            currentSignature = "a:k1",
            stateIsSuccess = true,
        )
        assertEquals(SyncRoundPlan.Skip, removed)
        assertFalse(removed is SyncRoundPlan.Full)
    }
}
