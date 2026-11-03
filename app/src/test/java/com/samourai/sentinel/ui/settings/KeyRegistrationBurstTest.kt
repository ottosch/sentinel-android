package com.samourai.sentinel.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #158: the vocabulary seam + the single-flight holder. Pure JVM - no
 * Android surface, no Robolectric.
 */
class KeyRegistrationBurstTest {

    @Before
    fun resetBurstState() {
        KeyRegistrationBurst.markFinished()
    }

    @Test
    fun progress_counts_attempts_under_the_renamed_vocabulary() {
        assertEquals(
            "Registering derivation paths with Dojo\u2026 14 of 19",
            KeyRegistrationBurst.progressText(14, 19)
        )
    }

    @Test
    fun full_pass_states_the_plain_tally() {
        assertEquals(
            "Dojo connected (19 derivation paths registered)",
            KeyRegistrationBurst.resultText(19, 0, 19)
        )
    }

    @Test
    fun partial_pass_states_the_failed_delta_out_loud() {
        // The witnessed case: the row read "19 of 19" (attempts) while an
        // item failed - the completion must name the delta, not just say
        // "partial".
        assertEquals(
            "Dojo connected (18 of 19 derivation paths registered, 1 failed - retry pairing to re-register)",
            KeyRegistrationBurst.resultText(18, 1, 19)
        )
    }

    @Test
    fun erase_skip_shape_gets_the_retry_note_without_a_zero_failed_wart() {
        val text = KeyRegistrationBurst.resultText(17, 0, 19)
        assertTrue(text.contains("17 of 19 derivation paths registered"))
        assertFalse(text.contains("0 failed"))
        assertTrue(text.contains("retry pairing to re-register"))
    }

    @Test
    fun single_flight_blocks_a_second_burst_while_one_is_live() {
        assertTrue(KeyRegistrationBurst.markStarted())
        assertFalse(KeyRegistrationBurst.markStarted())
    }

    @Test
    fun completion_re_arms_the_gate_so_every_retry_round_reports() {
        // The every-time condition: each round fires its own report, so
        // the gate must re-arm at completion - never stick.
        assertTrue(KeyRegistrationBurst.markStarted())
        KeyRegistrationBurst.complete(18, 1, 19)
        assertFalse(KeyRegistrationBurst.inFlight)
        assertTrue(KeyRegistrationBurst.markStarted())
    }

    @Test
    fun in_flight_row_renders_the_re_entry_marker_from_state() {
        assertTrue(KeyRegistrationBurst.markStarted())
        KeyRegistrationBurst.begin(19)
        KeyRegistrationBurst.onItemStarted(14)
        assertEquals(
            "Registering derivation paths with Dojo\u2026 14 of 19",
            KeyRegistrationBurst.statusText("Enabled")
        )
    }

    @Test
    fun partial_round_leaves_residue_on_the_rest_state_until_displaced() {
        KeyRegistrationBurst.markStarted()
        KeyRegistrationBurst.complete(18, 1, 19)
        assertEquals(
            "Enabled (18 of 19 registered)",
            KeyRegistrationBurst.residueText(18, 19)
        )
        assertEquals(
            "Enabled (18 of 19 registered)",
            KeyRegistrationBurst.statusText("Enabled")
        )
        KeyRegistrationBurst.markStarted()
        KeyRegistrationBurst.complete(19, 0, 19)
        assertEquals("Enabled", KeyRegistrationBurst.statusText("Enabled"))
    }

    @Test
    fun empty_wallet_launch_resets_clean_with_no_residue() {
        assertTrue(KeyRegistrationBurst.markStarted())
        KeyRegistrationBurst.markFinished()
        assertEquals("Enabled", KeyRegistrationBurst.statusText("Enabled"))
        assertTrue(KeyRegistrationBurst.markStarted())
    }

    // #409: the observer seam. The frozen-counter mechanism was that view
    // writes rode the launching activity instance; these pin the holder's
    // publish/subscribe contract instead.

    @Test
    fun subscribers_receive_every_progress_tick_and_the_completion_tally() {
        KeyRegistrationBurst.markStarted()
        val seen = mutableListOf<BurstEvent>()
        val listener: (BurstEvent) -> Unit = { seen.add(it) }
        KeyRegistrationBurst.subscribe(listener)
        KeyRegistrationBurst.begin(3)
        KeyRegistrationBurst.onItemStarted(1)
        KeyRegistrationBurst.onItemStarted(2)
        KeyRegistrationBurst.complete(2, 1, 3)
        KeyRegistrationBurst.unsubscribe(listener)
        assertEquals(
            listOf(
                BurstEvent.Progress(0, 3),
                BurstEvent.Progress(1, 3),
                BurstEvent.Progress(2, 3),
                BurstEvent.Finished(2, 1, 3)
            ),
            seen
        )
    }

    @Test
    fun a_mid_burst_subscriber_receives_the_current_count_immediately() {
        // The re-entry case at the heart of #409: a screen that appears
        // mid-burst must not wait for the NEXT tick to show a real count.
        KeyRegistrationBurst.markStarted()
        KeyRegistrationBurst.begin(19)
        KeyRegistrationBurst.onItemStarted(14)
        val seen = mutableListOf<BurstEvent>()
        val listener: (BurstEvent) -> Unit = { seen.add(it) }
        KeyRegistrationBurst.subscribe(listener)
        KeyRegistrationBurst.unsubscribe(listener)
        assertEquals(listOf(BurstEvent.Progress(14, 19)), seen)
    }

    @Test
    fun an_unsubscribed_listener_receives_nothing() {
        KeyRegistrationBurst.markStarted()
        val seen = mutableListOf<BurstEvent>()
        val listener: (BurstEvent) -> Unit = { seen.add(it) }
        KeyRegistrationBurst.subscribe(listener)
        KeyRegistrationBurst.unsubscribe(listener)
        KeyRegistrationBurst.begin(2)
        KeyRegistrationBurst.onItemStarted(1)
        KeyRegistrationBurst.complete(2, 0, 2)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun the_empty_wallet_silent_reset_publishes_nothing() {
        // #98's silence leg must survive the seam: no event, no snackbar
        // for an empty wallet's skipped burst.
        KeyRegistrationBurst.markStarted()
        val seen = mutableListOf<BurstEvent>()
        val listener: (BurstEvent) -> Unit = { seen.add(it) }
        KeyRegistrationBurst.subscribe(listener)
        KeyRegistrationBurst.markFinished()
        KeyRegistrationBurst.unsubscribe(listener)
        assertTrue(seen.isEmpty())
    }
}
