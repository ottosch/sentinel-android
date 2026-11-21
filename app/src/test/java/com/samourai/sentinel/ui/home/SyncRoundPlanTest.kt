package com.samourai.sentinel.ui.home

import com.samourai.sentinel.core.SyncState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The round planner: what an auto sync round does with the current list.
 *
 * - a list change fetches exactly the changed ids,
 * - a removal-only change skips the round entirely (no Success post),
 * - a failed round is followed by a full one (the stamped BALANCE
 *   failure baseline describes a pre-outage list, so a delta round is
 *   only allowed over a standing clean Success).
 */
class SyncRoundPlanTest {

    private val idA = "idA"
    private val idB = "idB"
    private val idC = "idC"

    private fun signature(vararg entries: Pair<String, List<String>>) =
        entries.joinToString("|") { (id, keys) ->
            id + ":" + keys.joinToString(",")
        }

    private fun decide(
        last: String?,
        current: String,
        state: SyncState?,
    ): SyncRoundPlan = SyncRoundPlan.decide(
        lastSignature = last,
        currentSignature = current,
        stateIsSuccess = state is SyncState.Success,
    )

    @Test
    fun listChangeFetchesExactlyTheChangedIds() {
        val last = signature(idA to listOf("k1"), idB to listOf("k2"))
        val added = signature(idA to listOf("k1"), idB to listOf("k2"), idC to listOf("k9"))
        assertEquals(SyncRoundPlan.Delta(setOf(idC)), decide(last, added, SyncState.Success(1L)))
        val keyEdit = signature(idA to listOf("k1", "k5"), idB to listOf("k2"))
        assertEquals(SyncRoundPlan.Delta(setOf(idA)), decide(last, keyEdit, SyncState.Success(1L)))
    }

    @Test
    fun removalOnlySkipsTheRound() {
        val last = signature(idA to listOf("k1"), idB to listOf("k2"))
        val after = signature(idA to listOf("k1"))
        assertEquals(SyncRoundPlan.Skip, decide(last, after, SyncState.Success(1L)))
        assertEquals(SyncRoundPlan.Skip, decide(last, after, SyncState.Failed("x", retryable = true)))
    }

    @Test
    fun reorderSkipsTheRound() {
        val last = signature(idA to listOf("k1"), idB to listOf("k2"))
        val after = signature(idB to listOf("k2"), idA to listOf("k1"))
        assertEquals(SyncRoundPlan.Skip, decide(last, after, SyncState.Success(1L)))
    }

    @Test
    fun aFailedRoundIsFollowedByAFullOne() {
        // Reviewer repro: the failed pull-to-refresh keeps its baseline
        // stamped; the added collection alone would fetch while the rest
        // is still pre-outage. No clean Success -> full round.
        val last = signature(idA to listOf("k1"), idB to listOf("k2"))
        val added = signature(idA to listOf("k1"), idB to listOf("k2"), idC to listOf("k9"))
        assertEquals(SyncRoundPlan.Full, decide(last, added, SyncState.Failed("Could not reach Dojo.", retryable = true)))
        assertEquals(SyncRoundPlan.Full, decide(last, added, SyncState.WaitingForTor(50)))
        assertEquals(SyncRoundPlan.Full, decide(last, added, null))
    }

    @Test
    fun aCleanSuccessKeepsTheDelta() {
        val last = signature(idA to listOf("k1"), idB to listOf("k2"))
        val added = signature(idA to listOf("k1"), idB to listOf("k2"), idC to listOf("k9"))
        assertEquals(SyncRoundPlan.Delta(setOf(idC)), decide(last, added, SyncState.Success(1L)))
    }

    @Test
    fun noBaselineIsAFullRound() {
        val current = signature(idA to listOf("k1"), idB to listOf("k2"))
        assertEquals(SyncRoundPlan.Full, decide(null, current, SyncState.Success(1L)))
        assertEquals(SyncRoundPlan.Full, decide(null, current, SyncState.Failed("x", retryable = true)))
    }
}
