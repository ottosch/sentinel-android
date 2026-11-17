package com.samourai.sentinel.ui.dojo

import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.ui.dojo.CommunityDojoTorWait.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    @Test
    fun `at the wait limit a bootstrap past 0 percent is still starting, with its percentage`() {
        // #581: a first bootstrap sat at 70 % for over five minutes.
        assertEquals(70, CommunityDojoTorWait.stillStartingProgress(EnumTorState.ON, 70))
        assertEquals(5, CommunityDojoTorWait.stillStartingProgress(EnumTorState.ON, 5))
        assertEquals(99, CommunityDojoTorWait.stillStartingProgress(EnumTorState.ON, 99))
        assertEquals(40, CommunityDojoTorWait.stillStartingProgress(EnumTorState.STARTING, 40))
    }

    @Test
    fun `at the wait limit Tor that is off, stopping or still at 0 percent keeps the real failure`() {
        assertNull(CommunityDojoTorWait.stillStartingProgress(EnumTorState.OFF, 0))
        assertNull(CommunityDojoTorWait.stillStartingProgress(EnumTorState.OFF, 70))
        assertNull(CommunityDojoTorWait.stillStartingProgress(EnumTorState.STOPPING, 70))
        assertNull(CommunityDojoTorWait.stillStartingProgress(EnumTorState.STARTING, 0))
        assertNull(CommunityDojoTorWait.stillStartingProgress(EnumTorState.ON, 0))
        // 100 % without a SOCKS port yet is not "starting": the fetch follows within moments.
        assertNull(CommunityDojoTorWait.stillStartingProgress(EnumTorState.ON, 100))
    }

    @Test
    fun `the still starting message ends as soon as Tor is stopping or off`() {
        // #585 I3: STOPPING used to leave "Tor is still starting (n%)…" up.
        assertTrue(CommunityDojoTorWait.stillStartingEnds(EnumTorState.STOPPING))
        assertTrue(CommunityDojoTorWait.stillStartingEnds(EnumTorState.OFF))
        assertFalse(CommunityDojoTorWait.stillStartingEnds(EnumTorState.STARTING))
        assertFalse(CommunityDojoTorWait.stillStartingEnds(EnumTorState.ON))
    }

    @Test
    fun `the sheet replaces the still starting message with the failure while Tor is stopping`() {
        val path = "java/com/samourai/sentinel/ui/dojo/CommunityDojoListBottomSheet.kt"
        val src = listOf(File("src/main/$path"), File("app/src/main/$path")).first { it.isFile }.readText()
        val block = src.substringAfter("if (torStillStarting && !fetchInFlight) {").substringBefore("when (CommunityDojoTorWait.onState")
        assertTrue(block.contains("CommunityDojoTorWait.stillStartingEnds(state.state)"))
        assertTrue(block.indexOf("stillStartingEnds") < block.indexOf("renderTorFailure()"))
        assertTrue(block.indexOf("renderTorFailure()") < block.indexOf("renderTorStillStarting"))
    }

    private fun stringResource(name: String): String {
        val path = "res/values/strings.xml"
        val xml = listOf(File("src/main/$path"), File("app/src/main/$path")).first { it.isFile }.readText()
        val line = xml.lines().single { it.contains("name=\"$name\"") }
        return line.substringAfter('>').substringBeforeLast("</string>").replace("\\'", "'")
    }

    @Test
    fun `the still starting texts show the live percentage`() {
        assertEquals(
            "Tor is still starting (70%)… The first start after installing can take a few minutes. " +
                "The list loads by itself when Tor is ready.",
            String.format(stringResource("community_dojo_tor_still_starting"), 70),
        )
        assertEquals(
            "Showing saved list from Nov 16 · Tor is still starting (70%)…",
            String.format(stringResource("community_dojo_cached_tor_starting"), "Nov 16", 70),
        )
    }
}
