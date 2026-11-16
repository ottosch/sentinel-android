package com.samourai.sentinel.ui.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * One template check for Settings, backup import, and load.
 * http(s) only. A backslash, a control character, userinfo, a
 * placeholder in the host, or a non-canonical IPv4 host is refused.
 * A refused Custom choice stays unbound.
 */
class ExplorerTemplateTest {

    @Test
    fun `only an http or https template with no userinfo is saved`() {
        assertFalse(rejected("https://explorer.example/tx/{txid}"))
        assertFalse(rejected("HTTP://Explorer.Example/tx/{txid}"))
        assertFalse(rejected("http://xyz.onion/tx/{address}"))
        assertFalse(rejected("https://explorer.example/tx/{txid}?q=a@b"))
        assertFalse(rejected("https://explorer.example/tx/{txid}#a@b"))
        assertFalse(rejected("https://explorer.example:8443/tx/{txid}"))
        assertFalse(rejected("http://127.0.0.1/tx/{txid}"))
        assertFalse(rejected("  "))

        assertTrue(rejected("javascript:alert(1)"))
        assertTrue(rejected("file:///etc/passwd"))
        assertTrue(rejected("intent://evil.example#Intent;end"))
        assertTrue(rejected("explorer.example/tx/{txid}"))
        assertTrue(rejected("ws://explorer.example/tx/{txid}"))

        assertTrue(rejected("https://evil.example\\@mempool.space/tx/{txid}"))
        assertTrue(rejected("https://user:pass@evil.example/tx/{txid}"))
        assertTrue(rejected("https://mempool.space@evil.example/tx/{txid}"))
        assertTrue(rejected("https:/mempool.space@evil.example/tx/{txid}"))
        assertTrue(rejected("https:user@evil.example/tx/{txid}"))
        assertTrue(rejected("https://@evil.example/tx/{txid}"))
        assertTrue(rejected("https://:pass@evil.example/tx/{txid}"))
        // A link in the query is not a second scheme. One in the path is.
        assertFalse(rejected("https://explorer.example/tx/{txid}?next=https://evil.example"))
        assertFalse(rejected("https://explorer.example/tx/{txid}#https://evil.example"))
        assertTrue(rejected("https://evil.example/https://attacker.example/tx/{txid}"))

        assertTrue(rejected("https://{txid}.example/tx/{txid}"))
        assertTrue(rejected("https:{txid}"))
        assertTrue(rejected("https://explorer.{address}/tx/1"))
        assertFalse(rejected("https://explorer.example/tx/{txid}"))
        assertFalse(rejected("https://explorer.example/{address}"))

        assertTrue(rejected("http://2130706433/tx/{txid}"))
        assertTrue(rejected("http://0177.0.0.1/tx/{txid}"))
        assertTrue(rejected("http://0x7f.0.0.1/"))
        assertTrue(rejected("http://0x7f000001/"))
        assertTrue(rejected("http://127.1/tx/{txid}"))
        assertTrue(rejected("http://127.0.0.01/"))
        // A name that contains the letters 0x is not a hex IPv4 address.
        assertFalse(rejected("https://my0xnode.example/tx/{txid}"))
        assertFalse(rejected("https://0xdead.example/tx/{txid}"))
    }

    @Test
    fun `a non ascii host is refused and punycode is kept`() {
        assertTrue(rejected("https://fa\u00df.de/tx/{txid}"))
        assertTrue(rejected("http://\uFF58.\uFF4F\uFF4E\uFF49\uFF4F\uFF4E/tx/{txid}"))
        assertFalse(rejected("https://xn--fa-hia.de/tx/{txid}"))
    }

    @Test
    fun `a txid placeholder is filled only with 64 hex characters`() {
        val template = "https://explorer.example/tx/{txid}"
        val bound = ExplorerBinding.resolve(
            ExplorerBinding.ExplorerFacts(
                savedSelection = ExplorerBinding.CHOICE_CUSTOM,
                customUrl = template,
                publishedUrl = null,
                appIsTestnet = false,
                dojoDeclaredNetwork = null,
                dojoApiUrl = null,
            ),
        )
        val txid = "ab".repeat(32)
        assertEquals(
            "https://explorer.example/tx/$txid",
            ExplorerBinding.pageUrl(bound, ExplorerBinding.ExplorerPage.TX, txid),
        )
        assertEquals(null, ExplorerBinding.pageUrl(bound, ExplorerBinding.ExplorerPage.TX, "not-a-txid"))
        assertEquals(null, ExplorerBinding.pageUrl(bound, ExplorerBinding.ExplorerPage.TX, "ab".repeat(31)))
        assertTrue(ExplorerBinding.isHexTxid(txid))
        assertFalse(ExplorerBinding.isHexTxid("evil.example"))
    }

    @Test
    fun `a tab or newline is refused and the label still names the chromium host`() {
        val tabbed = "https:/\t/evil.example/tx/{txid}"
        assertTrue(rejected(tabbed))
        assertTrue(rejected("https:/\n/evil.example"))
        assertTrue(rejected("https:/\r/evil.example"))
        assertTrue(rejected("https://evil.example/tx/\u0000{txid}"))
        assertEquals("evil.example", ExplorerOpener.partyLabelOf(tabbed))
        assertFalse(ExplorerOpener.partyLabelOf(tabbed) == "https")
    }

    @Test
    fun `the icon and the advisory key use the parsed onion host`() {
        assertEquals(ExplorerOptions.Icon.ONION, iconOf("http://x.oni%6fn/tx/{txid}"))
        assertEquals(ExplorerOptions.Icon.ONION, iconOf("http://\uFF58.\uFF4F\uFF4E\uFF49\uFF4F\uFF4E/tx/{txid}"))
        assertEquals(ExplorerOptions.Icon.ONION, iconOf("http://x.onion\u3002/tx/{txid}"))
        assertEquals(ExplorerOptions.Icon.GLOBE, iconOf("https://explorer.example/tx/{txid}"))
        assertEquals(ExplorerOptions.Icon.GLOBE, iconOf("javascript:alert(1)"))

        assertEquals(null, ExplorerOptions.advisoryKey(ExplorerOptions.Id.CUSTOM, "http://x.oni%6fn/tx/{txid}"))
        assertEquals(
            null,
            ExplorerOptions.advisoryKey(
                ExplorerOptions.Id.CUSTOM,
                "http://\uFF58.\uFF4F\uFF4E\uFF49\uFF4F\uFF4E/tx/{txid}",
            ),
        )
        assertEquals(
            "custom:explorer.example",
            ExplorerOptions.advisoryKey(ExplorerOptions.Id.CUSTOM, "https://explorer.example/tx/{txid}"),
        )
        assertEquals(
            "custom:evil.example",
            ExplorerOptions.advisoryKey(
                ExplorerOptions.Id.CUSTOM,
                "https://evil.example\\@mempool.space/tx/{txid}",
            ),
        )
    }

    @Test
    fun `import keeps a rejected custom choice unbound and drops it from any other choice`() {
        val kept = ExplorerBinding.scrubImportedExplorer(
            "javascript:alert(1)",
            ExplorerBinding.CHOICE_CUSTOM,
        )
        assertTrue(kept.rejected)
        assertEquals("javascript:alert(1)", kept.customUrl)
        assertEquals(ExplorerBinding.CHOICE_CUSTOM, kept.selection)
        assertTrue(
            ExplorerBinding.resolve(
                facts(saved = kept.selection, custom = kept.customUrl, published = "http://synthetic-self-explorer.onion"),
            ) is ExplorerBinding.ExplorerResolution.Unbound,
        )

        val dropped = ExplorerBinding.scrubImportedExplorer(
            "file:///etc/passwd",
            "mempool.space",
        )
        assertTrue(dropped.rejected)
        assertEquals("", dropped.customUrl)
        assertEquals("mempool.space", dropped.selection)

        val fine = ExplorerBinding.scrubImportedExplorer(
            "https://explorer.example/tx/{txid}",
            ExplorerBinding.CHOICE_CUSTOM,
        )
        assertFalse(fine.rejected)
        assertEquals("https://explorer.example/tx/{txid}", fine.customUrl)
    }

    @Test
    fun `settings import and load all use the same check`() {
        val settings = source(
            "src/main/java/com/samourai/sentinel/ui/settings/MainSettingsFragment.kt",
            "app/src/main/java/com/samourai/sentinel/ui/settings/MainSettingsFragment.kt",
        )
        val prompt = settings.substringAfter("private fun promptForCustomExplorerUrl()")
            .substringBefore("private fun setExchangeSettings()")
        assertTrue(prompt.contains("ExplorerOptions.customTemplateRejected"))
        assertTrue(
            prompt.indexOf("customTemplateRejected") < prompt.indexOf("customExplorerUrl = url"),
        )

        val prefs = source(
            "src/main/java/com/samourai/sentinel/ui/utils/PrefsUtil.kt",
            "app/src/main/java/com/samourai/sentinel/ui/utils/PrefsUtil.kt",
        )
        val importer = prefs.substringAfter("fun import(payload: JSONObject)")
            .substringBefore("abstract class Preferences")
        assertTrue(importer.contains("scrubImportedExplorer"))

        val export = source(
            "src/main/java/com/samourai/sentinel/util/ExportImportUtil.kt",
            "app/src/main/java/com/samourai/sentinel/util/ExportImportUtil.kt",
        )
        val importPrefs = export.substringAfter("fun importPrefs(")
            .substringBefore("private fun validate")
        assertTrue(importPrefs.contains("prefsUtil.import"))
        assertTrue(importPrefs.contains("importRejectedCustomExplorer"))

        val activity = source(
            "src/main/java/com/samourai/sentinel/ui/settings/ImportBackUpActivity.kt",
            "app/src/main/java/com/samourai/sentinel/ui/settings/ImportBackUpActivity.kt",
        )
        assertTrue(activity.contains("customExplorerRejected = ExportImportUtil().importPrefs"))
        assertTrue(activity.contains("The custom block explorer was not used. Choose one in Settings."))

        val binding = source(
            "src/main/java/com/samourai/sentinel/ui/webview/ExplorerBinding.kt",
            "app/src/main/java/com/samourai/sentinel/ui/webview/ExplorerBinding.kt",
        )
        val resolve = binding.substringAfter("if (selection == CHOICE_CUSTOM)")
            .substringBefore("return pairedExplorer")
        assertTrue(resolve.contains("customTemplateRejected"))
        assertTrue(resolve.contains("ExplorerResolution.Unbound"))
    }

    private fun rejected(url: String): Boolean = ExplorerOptions.customTemplateRejected(url)

    private fun iconOf(url: String): ExplorerOptions.Icon {
        val rows = ExplorerOptions.rows(facts(saved = ExplorerBinding.CHOICE_CUSTOM, custom = url), torReady = true)
        return rows.first { it.id == ExplorerOptions.Id.CUSTOM }.icon
    }

    private fun facts(
        saved: String?,
        custom: String?,
        published: String? = null,
    ) = ExplorerBinding.ExplorerFacts(
        savedSelection = saved,
        customUrl = custom,
        publishedUrl = published,
        appIsTestnet = false,
        dojoDeclaredNetwork = if (published == null) null else "mainnet",
        dojoApiUrl = null,
    )

    private fun source(vararg relative: String): String {
        val file = relative.map { File(it) }.firstOrNull { it.isFile }
            ?: error("source not found from ${File(".").absolutePath}: ${relative.toList()}")
        return file.readText()
    }
}
