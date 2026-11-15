package com.samourai.sentinel.ui.settings

import com.samourai.sentinel.core.DojoContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DojoStatusRowTest {

    private val now = 1_000_000L

    @Test
    fun rowFollowsTheLastContact() {
        assertEquals(
            DojoRowPresentation(DojoStatusRow.CONNECTED, DojoRowTone.GREEN),
            DojoStatusRow.present(DojoContact.Reachable(now - 1_000), now, torReady = true, checking = false),
        )
        assertEquals(
            DojoRowPresentation(DojoStatusRow.NOT_CHECKED, DojoRowTone.AMBER),
            DojoStatusRow.present(DojoContact.Unknown, now, torReady = true, checking = false),
        )
        assertEquals(
            DojoRowPresentation(DojoStatusRow.CHECKING, DojoRowTone.AMBER),
            DojoStatusRow.present(DojoContact.Unknown, now, torReady = true, checking = true),
        )
        assertEquals(
            DojoRowPresentation(DojoStatusRow.DOJO_ERROR, DojoRowTone.AMBER),
            DojoStatusRow.present(DojoContact.DojoError(now, 500, "http"), now, torReady = true, checking = false),
        )
        assertEquals(
            DojoRowPresentation(DojoStatusRow.UNREACHABLE, DojoRowTone.RED),
            DojoStatusRow.present(
                DojoContact.Unreachable(now, "socks"),
                now,
                torReady = true,
                checking = false,
            ),
        )
    }

    @Test
    fun torNotReadyIsAmberAndNeverGreen() {
        val states = listOf(
            DojoContact.Unknown,
            DojoContact.Reachable(now),
            DojoContact.DojoError(now, 500, "http"),
            DojoContact.Unreachable(now, "socks"),
        )
        states.forEach { contact ->
            val row = DojoStatusRow.present(contact, now, torReady = false, checking = true)
            assertEquals(DojoStatusRow.NOT_CHECKED, row.text)
            assertEquals(DojoRowTone.AMBER, row.tone)
            assertFalse(DojoStatusRow.shouldProbe(contact, now, torReady = false))
        }
    }

    @Test
    fun aStaleContactIsCheckedAgainAndADefinitiveResultIsNot() {
        val fresh = DojoContact.Reachable(now - DojoStatusRow.RECENT_MS)
        val stale = DojoContact.Reachable(now - DojoStatusRow.RECENT_MS - 1)
        assertEquals(
            DojoRowPresentation(DojoStatusRow.CONNECTED, DojoRowTone.GREEN),
            DojoStatusRow.present(fresh, now, torReady = true, checking = false),
        )
        assertFalse(DojoStatusRow.shouldProbe(fresh, now, torReady = true))
        assertEquals(
            DojoRowPresentation(DojoStatusRow.CHECKING, DojoRowTone.AMBER),
            DojoStatusRow.present(stale, now, torReady = true, checking = false),
        )
        assertTrue(DojoStatusRow.shouldProbe(stale, now, torReady = true))
        assertTrue(DojoStatusRow.shouldProbe(DojoContact.Unknown, now, torReady = true))
        assertFalse(
            DojoStatusRow.shouldProbe(DojoContact.DojoError(now, 500, "http"), now, torReady = true),
        )
        assertFalse(
            DojoStatusRow.shouldProbe(DojoContact.Unreachable(now, "io"), now, torReady = true),
        )
    }
}
