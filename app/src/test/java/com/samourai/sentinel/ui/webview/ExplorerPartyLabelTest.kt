package com.samourai.sentinel.ui.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #524: the third-party dialog names the party it opens and points
 * to the private alternative that actually exists.
 *
 *  1. A Custom template carries a literal {txid} placeholder. The
 *     dialog names the host of the page that will load, never the
 *     raw template. An onion host shortens like the Network row; a
 *     host-less URL falls back to the truncated label.
 *  2. With a paired Dojo explorer bound, the guidance line points to
 *     switching back to it, not to pairing one. The unpaired wording
 *     is untouched.
 */
class ExplorerPartyLabelTest {

    @Test
    fun `the custom party is the host never the raw template`() {
        val template = "https://explorer.example/tx/{txid}"
        assertEquals("explorer.example", ExplorerOpener.partyLabelOf(template))

        // The substituted page URL has the same host.
        assertEquals(
            "explorer.example",
            ExplorerOpener.partyLabelOf("https://explorer.example/tx/abc123"),
        )
        assertFalse("the placeholder must not reach the user", template.let {
            ExplorerOpener.partyLabelOf(it).contains("{txid}")
        })

        assertEquals(
            "mempool.space",
            ExplorerOpener.partyLabelOf("https://mempool.space/tx/abc"),
        )
        assertEquals("blockstream.info", ExplorerOpener.partyLabelOf("https://blockstream.info"))
        // Padding a fragment keeps the host label.
        assertEquals(
            "explorer.example",
            ExplorerOpener.partyLabelOf("https://explorer.example/tx/{txid}#x"),
        )
    }

    @Test
    fun `an onion host shortens like the network row and a hostless url truncates`() {
        val onion =
            "abcdefghij01234567890123456789012345678901234567890123.onion/tx/{txid}"
        val label = ExplorerOpener.partyLabelOf("http://$onion")
        assertTrue(label.endsWith(".onion"))
        assertFalse(label.contains("{txid}"))
        // The same formatter as the row.
        assertEquals(ExplorerBinding.shortenOnion("http://$onion"), label)

        // A bare template still names its host; the truncation below
        // is only for hosts longer than the label cap.

        assertEquals("explorer.example", ExplorerOpener.partyLabelOf("explorer.example/tx/{txid}"))
        assertFalse(ExplorerOpener.partyLabelOf("explorer.example/tx/{txid}").contains("{txid}"))

        val longHost = "https://" + "a".repeat(80) + ".example/tx/{txid}"
        assertFalse(ExplorerOpener.partyLabelOf(longHost).contains("{txid}"))
    }

    @Test
    fun `the guidance line points to the paired explorer when one is bound`() {
        val opener = source(
            "src/main/java/com/samourai/sentinel/ui/webview/ExplorerOpener.kt",
            "app/src/main/java/com/samourai/sentinel/ui/webview/ExplorerOpener.kt",
        )
        val guidance = opener.substringAfter("private fun thirdPartyGuidance()")
            .substringBefore("private fun confirmAnalysis")
        assertTrue(guidance.contains("ExplorerBinding.ExplorerKind.PAIRED"))
        assertTrue(guidance.contains("R.string.explorer_third_party_guidance_paired"))
        assertTrue(guidance.contains("R.string.explorer_third_party_guidance"))
        // confirmAnalysis already held the pattern; the new helper
        // reads it the same way: three reads, all the same shape.
        assertEquals(3, Regex("repository.resolution\\(\\)").findAll(opener).count())
        val strings = source(
            "src/main/res/values/strings.xml",
            "app/src/main/res/values/strings.xml",
        )
        val paired = valueOf(strings, "explorer_third_party_guidance_paired")
        assertTrue(paired.contains("your paired Dojo explorer"))
        assertTrue(paired.contains("Settings > Explorer"))
        val unpaired = valueOf(strings, "explorer_third_party_guidance")
        assertFalse(unpaired.contains("your paired"))
    }

    private fun valueOf(xml: String, key: String): String {
        val at = xml.indexOf("name=\"$key\"")
        assertTrue("missing string $key", at >= 0)
        val open = xml.indexOf('>', at)
        val close = xml.indexOf("</string>", open)
        assertTrue(open > at && close > open)
        return xml.substring(open + 1, close)
    }

    private fun source(vararg relative: String): String {
        val file = relative.map { File(it) }.firstOrNull { it.isFile }
            ?: error("source not found from ${File(".").absolutePath}: ${relative.toList()}")
        return file.readText()
    }
}