package com.samourai.sentinel.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The diff-empty round: a deletion or a reorder yields an empty
 * [SyncRoundDiff.changedCollectionIds] set (the diff walks only the
 * current ids). Such a round must be skipped at the arm site - both
 * signatures stamped, syncState untouched - not run as an empty fetch
 * that posts Success over a standing Failed. The failed pull-to-refresh
 * keeps its signature stamped, so the empty round was exactly the path
 * that turned "Could not reach Dojo." green with zero network contact
 * and disarmed QuietResync.
 *
 * The decision itself lives in SyncRoundPlan and is tested behaviorally
 * in SyncRoundPlanTest; this file only pins the launchSyncRound wiring.
 * The tree never constructs HomeViewModel (see DojoErrorEnvelopeRoundTest),
 * so the shape is asserted on the source, like QuietResyncTest.
 */
class SyncRoundDiffEmptySkipTest {

    @Test
    fun launchSyncRoundExecutesThePlanAndSkipsWithStamps() {
        val home = source("app/src/main/java/com/samourai/sentinel/ui/home/HomeViewModel.kt")
        val launch = between(
            home,
            "private fun launchSyncRound",
            "fun payloadReadFailure()",
        )
        val decide = launch.indexOf("SyncRoundPlan.decide(")
        val skip = launch.indexOf("is SyncRoundPlan.Skip -> {")
        val roundLaunch = launch.indexOf("syncRound = viewModelScope.launch")
        assertTrue("planner call not found", decide >= 0)
        assertTrue("skip branch not found", skip > decide)
        assertTrue("skip must precede the round launch", roundLaunch > skip)

        val skipBlock = launch.substring(skip, roundLaunch)
        // Both signatures stamped: later identical emissions hit the
        // signature-unchanged dedupe, and the trailing-re-run baseline
        // stays coherent.
        assertTrue(skipBlock.contains("lastRoundSignature = signature"))
        assertTrue(skipBlock.contains("roundStartSignature = signature"))
        // The point of the fix: no terminal state from a round that never
        // touched the network. Failed stays Failed, Success stays Success.
        assertFalse(skipBlock.contains("SyncState.Success"))
        assertFalse(skipBlock.contains("syncCollections("))
        // A skipped re-arm must not leave WaitingForTor standing - with
        // the round skipped, nothing replaces the banner.
        assertTrue(skipBlock.contains("syncState.value is SyncState.WaitingForTor"))
        assertTrue(skipBlock.contains("postValue(SyncState.Idle)"))
    }

    @Test
    fun theEmptyInRoundBranchStaysForOffNetworkRounds() {
        // The skip removes the diff-empty path only. syncCollections'
        // inRound.isEmpty() branch is still reached by a full round whose
        // collections are all off-network, and it still owns the
        // Success/Idle decision there.
        val home = source("app/src/main/java/com/samourai/sentinel/ui/home/HomeViewModel.kt")
        val collections = between(
            home,
            "private suspend fun syncCollections",
            "private suspend fun gateTxHistory",
        )
        assertTrue(collections.contains("if (inRound.isEmpty()) {"))
        assertTrue(collections.contains("SyncState.Success(System.currentTimeMillis())"))
        // The skip decision must not live here: the arm site owns it.
        assertFalse(collections.contains("SyncRoundPlan"))
    }

    private fun source(relative: String): String {
        val file = sequenceOf(File(relative), File(relative.removePrefix("app/")))
            .firstOrNull { it.isFile }
            ?: error("missing $relative from ${File(".").absolutePath}")
        return file.readText()
    }

    private fun between(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        require(from >= 0) { "start marker not found: $start" }
        val to = text.indexOf(end, from)
        require(to > from) { "end marker not found after $start: $end" }
        return text.substring(from, to)
    }
}
