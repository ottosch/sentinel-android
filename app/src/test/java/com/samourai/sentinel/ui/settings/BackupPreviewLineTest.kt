package com.samourai.sentinel.ui.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * #404 (decision 7): the preview line's contract, table-driven like
 * BackupImportNetworkPlanTest. The envelopes are inline JSON shapes,
 * not payloads - no key material of any kind appears here (house
 * rule: none in any public artifact, even testnet).
 *
 * The date format is UTC-pinned so the millis-to-date half cannot
 * flake with the CI runner's timezone.
 */
class BackupPreviewLineTest {

    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /** Envelope millis pinned to 2026-05-12T00:00:00Z. */
    private val time = 1_778_544_000_000L

    @Test
    fun `current backup with a declared network shows network then date`() {
        val line = backupPreviewLine(
            JSONObject()
                .put("format", 3)
                .put("version", 55)
                .put("time", time)
                .put("network", "mainnet")
                .put("payload", "AAECAw=="),
            dateFormat
        )
        assertEquals("Mainnet \u00b7 May 12, 2026", line)
    }

    @Test
    fun `testnet declaration renders capitalized testnet`() {
        val line = backupPreviewLine(
            JSONObject().put("time", time).put("network", "testnet"),
            dateFormat
        )
        assertEquals("Testnet \u00b7 May 12, 2026", line)
    }

    @Test
    fun `declaration is case-insensitive`() {
        val line = backupPreviewLine(
            JSONObject().put("time", time).put("network", "MAINNET"),
            dateFormat
        )
        assertEquals("Mainnet \u00b7 May 12, 2026", line)
    }

    @Test
    fun `old backup with no network field shows the date alone`() {
        val line = backupPreviewLine(
            JSONObject().put("version", 2).put("time", time),
            dateFormat
        )
        assertEquals("May 12, 2026", line)
    }

    @Test
    fun `garbage network value is no declaration - fail closed, never a guess`() {
        val line = backupPreviewLine(
            JSONObject().put("time", time).put("network", "signet"),
            dateFormat
        )
        assertEquals("May 12, 2026", line)
    }

    @Test
    fun `samourai ashigaru envelope with neither field is the valid-backup fallback`() {
        val line = backupPreviewLine(
            JSONObject().put("external", true).put("payload", "AAECAw=="),
            dateFormat
        )
        assertEquals("Valid backup file", line)
    }

    @Test
    fun `epoch-zero time is not a creation date - segment skipped`() {
        val line = backupPreviewLine(
            JSONObject().put("network", "mainnet").put("time", 0L),
            dateFormat
        )
        assertEquals("Mainnet", line)
    }

    @Test
    fun `envelope with no fields at all is the valid-backup fallback`() {
        val line = backupPreviewLine(JSONObject(), dateFormat)
        assertEquals("Valid backup file", line)
    }
}
