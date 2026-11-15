package com.samourai.sentinel.ui.home

import com.samourai.sentinel.core.DojoContact
import com.samourai.sentinel.core.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
        val home = source("app/src/main/java/com/samourai/sentinel/ui/home/HomeViewModel.kt")
        val activity = source("app/src/main/java/com/samourai/sentinel/ui/home/HomeActivity.kt")
        assertTrue(activity.contains("model.onHomeResumed()"))
        val resumed = home.substringAfter("fun onHomeResumed()").substringBefore("private var lastReachability")
        assertTrue(resumed.contains("maybeQuietResync()"))
        val listener = home.substringAfter("reachabilityListener").substringBefore("private val reachabilityPoke")
        assertTrue(listener.contains("becameReachable(previous, next)"))
        assertTrue(listener.contains("maybeQuietResync()"))
        val collections = home.substringAfter("private suspend fun syncCollections")
            .substringBefore("private suspend fun gateTxHistory")
        val balance = home.substringAfter("fun fetchBalance").substringBefore("fun getErrorMessage")
        assertFalse(collections.contains("maybeQuietResync"))
        assertFalse(balance.contains("maybeQuietResync"))
        assertEquals(3, Regex("maybeQuietResync\\(\\)").findAll(home).count())
    }

    private fun source(relative: String): String {
        val file = sequenceOf(File(relative), File(relative.removePrefix("app/")))
            .firstOrNull { it.isFile }
            ?: error("missing $relative from ${File(".").absolutePath}")
        return file.readText()
    }
}
