package com.samourai.sentinel.ui.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
 *
 * The dump legs pin the envelope half of the card. They assert on
 * quoted keys and full value strings, never on "key": value
 * composites: the JVM org.json artifact and Android's bundled copy
 * disagree on the space after the colon, and a display helper's
 * tests must not hang on that.
 *
 * The dump's truncation marker is ASCII "..." for the same reason
 * the helper emits it: org.json escapes U+2026 in serialized string
 * values (the whole U+2000-U+20FF block), so the first local run
 * failed on exactly the three ellipsis composites while every key
 * and ASCII assertion in the same tests passed.
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

    // --- backupEnvelopeDump: the restored raw half of the card ---

    @Test
    fun `envelope dump truncates the payload blob to head and size`() {
        val blob = "A".repeat(1400)
        val dump = backupEnvelopeDump(
            JSONObject().put("time", time).put("payload", blob)
        )
        // 1400 base64 chars estimate 1050 bytes -> "1.0 KB"
        assertTrue(dump.contains("AAAAAAAAAAAAAAAA... (1.0 KB)"))
        assertFalse(dump.contains("A".repeat(64)))
    }

    @Test
    fun `a 32-char blob is the verbatim boundary - no truncation yet`() {
        val blob = "B".repeat(32)
        val dump = backupEnvelopeDump(JSONObject().put("payload", blob))
        assertTrue(dump.contains(blob))
        assertFalse(dump.contains("..."))
    }

    @Test
    fun `envelope dump carries every envelope field and nothing invented`() {
        val dump = backupEnvelopeDump(
            JSONObject()
                .put("format", 3)
                .put("version", 55)
                .put("time", time)
                .put("network", "mainnet")
                .put("payload", "C".repeat(1400))
        )
        assertTrue(dump.contains("\"format\""))
        assertTrue(dump.contains("\"version\""))
        assertTrue(dump.contains("\"network\""))
        assertTrue(dump.contains("\"mainnet\""))
        assertTrue(dump.contains("CCCCCCCCCCCCCCCC... (1.0 KB)"))
        assertFalse(dump.contains("\"kdf\""))
    }

    @Test
    fun `samourai shape dumps with its blob truncated to a byte note`() {
        // 72 base64 chars estimate 54 bytes
        val dump = backupEnvelopeDump(
            JSONObject().put("external", true).put("payload", "D".repeat(72))
        )
        assertTrue(dump.contains("\"external\""))
        assertTrue(dump.contains("true"))
        assertTrue(dump.contains("DDDDDDDDDDDDDDDD... (54 B)"))
    }
}
