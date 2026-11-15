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
 *     dialog names the host Chromium will load, never the raw
 *     template and never a host guessed from the authority. An
 *     onion host shortens like the Network row. A URL that does
 *     not parse falls back to the truncated URL.
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

        // A bare template still names its host.
        assertEquals("explorer.example", ExplorerOpener.partyLabelOf("explorer.example/tx/{txid}"))
        assertFalse(ExplorerOpener.partyLabelOf("explorer.example/tx/{txid}").contains("{txid}"))

        // A host past the label cap, still a legal DNS label, truncates
        // itself. A label DNS will not accept does not parse, so the
        // dialog shows the shortened URL and still not the placeholder.
        val longLabel = "a".repeat(50) + ".example"
        assertEquals(
            longLabel.take(14) + "…" + longLabel.takeLast(6),
            ExplorerOpener.partyLabelOf("https://$longLabel/tx/{txid}"),
        )
        val unparsableHost = "https://" + "a".repeat(80) + ".example/tx/{txid}"
        assertFalse(ExplorerOpener.partyLabelOf(unparsableHost).contains("{txid}"))
    }

    @Test
    fun `the named host is the one chromium loads`() {
        // \ is a path separator, so the @ is in the path and the
        // host is evil.example.
        val spoof = "https://evil.example\\@mempool.space/tx/{txid}"
        assertEquals("evil.example", ExplorerOpener.partyLabelOf(spoof))
        assertEquals(
            "evil.example",
            ExplorerOpener.partyLabelOf("https://evil.example\\@mempool.space/tx/abc123"),
        )
        assertEquals(
            "evil.example",
            ExplorerOpener.partyLabelOf("evil.example\\@mempool.space/tx/{txid}"),
        )
        assertFalse(ExplorerOpener.partyLabelOf(spoof).contains("mempool.space"))
        assertFalse(ExplorerOpener.partyLabelOf(spoof).contains("{txid}"))

        // Userinfo is everything before the last @.
        assertEquals(
            "evil.example",
            ExplorerOpener.partyLabelOf("https://user:pass@evil.example/tx/{txid}"),
        )
        assertEquals(
            "evil.example",
            ExplorerOpener.partyLabelOf("https://mempool.space@evil.example/tx/{txid}"),
        )
        assertEquals(
            "evil.example",
            ExplorerOpener.partyLabelOf("https://user@name@evil.example/tx/{address}"),
        )

        // A normal template, including an @ that is not userinfo.
        assertEquals(
            "explorer.example",
            ExplorerOpener.partyLabelOf("https://explorer.example/tx/{txid}?q=a@b"),
        )
        assertEquals(
            "explorer.example",
            ExplorerOpener.partyLabelOf("https://explorer.example:8443/tx/{txid}"),
        )
        assertEquals("blockstream.info", ExplorerOpener.partyLabelOf("https://blockstream.info/tx/{txid}"))

        // `\` ends the authority before the @, and `pass` is not a
        // port, so Chromium does not load this. Do not guess the
        // host that follows the @.
        val broken = "https://user:pass\\@evil.example/tx/{txid}"
        assertEquals("https://user:p…e/tx/x", ExplorerOpener.partyLabelOf(broken))
        assertEquals("https://", ExplorerOpener.partyLabelOf("https://"))
    }

    @Test
    fun `settings refuses a backslash or a username before saving`() {
        assertTrue(ExplorerOptions.customTemplateRejected("https://evil.example\\@mempool.space/tx/{txid}"))
        assertTrue(ExplorerOptions.customTemplateRejected("https://user:pass@evil.example/tx/{txid}"))
        assertTrue(ExplorerOptions.customTemplateRejected("https://mempool.space@evil.example/tx/{txid}"))
        assertTrue(ExplorerOptions.customTemplateRejected("https://user@name@evil.example/tx/{txid}"))
        assertTrue(ExplorerOptions.customTemplateRejected("user@evil.example/tx/{txid}"))

        assertFalse(ExplorerOptions.customTemplateRejected("https://explorer.example/tx/{txid}"))
        assertFalse(ExplorerOptions.customTemplateRejected("http://xyz.onion/tx/{txid}"))
        assertFalse(ExplorerOptions.customTemplateRejected("explorer.example/tx/{address}"))
        assertFalse(ExplorerOptions.customTemplateRejected("https://explorer.example/tx/{txid}?q=a@b"))
        assertFalse(ExplorerOptions.customTemplateRejected("https://explorer.example/tx/{txid}#a@b"))
        assertFalse(ExplorerOptions.customTemplateRejected("  "))

        val settings = source(
            "src/main/java/com/samourai/sentinel/ui/settings/MainSettingsFragment.kt",
            "app/src/main/java/com/samourai/sentinel/ui/settings/MainSettingsFragment.kt",
        )
        val prompt = settings.substringAfter("private fun promptForCustomExplorerUrl()")
            .substringBefore("private fun setExchangeSettings()")
        val rejectAt = prompt.indexOf("ExplorerOptions.customTemplateRejected")
        val saveAt = prompt.indexOf("customExplorerUrl = url")
        assertTrue("rejection must run before the URL is saved", rejectAt >= 0 && saveAt > rejectAt)
        assertTrue(prompt.contains("R.string.explorer_custom_rejected"))
        val message = valueOf(
            source(
                "src/main/res/values/strings.xml",
                "app/src/main/res/values/strings.xml",
            ),
            "explorer_custom_rejected",
        )
        assertTrue(message.contains("backslash"))
        assertTrue(message.contains("username"))
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