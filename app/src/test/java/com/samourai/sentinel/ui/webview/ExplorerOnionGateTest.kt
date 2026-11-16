package com.samourai.sentinel.ui.webview

import com.samourai.sentinel.data.txfacts.TxFactsResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Any `.onion` host needs Tor, including a Custom URL. A WebView that
 * is already open checks the target of each later main-frame load,
 * redirect, and subresource. A direct session does not fetch an onion.
 * A Tor session does not drop that host onto a direct load.
 */
class ExplorerOnionGateTest {

    private val txid = "ab".repeat(32)
    private val customOnion = "http://custom.onion/tx/$txid"
    private val customClear = "https://explorer.example/tx/$txid"
    private val mempoolOnion = TxFactsResolver.MEMPOOL_ONION_BASE + "/tx/$txid"

    @Test
    fun `a custom onion is blocked when Tor is off and mempool onion stays the same`() {
        assertTrue(ExplorerBinding.lookupRequiresTor(customOnion, walletRequiresTor = false))
        assertTrue(ExplorerBinding.lookupRequiresTor(mempoolOnion, walletRequiresTor = false))
        assertEquals(
            ExplorerLoadDecision.BLOCK_TOR_OFF,
            decision(url = customOnion, torReady = false, torOff = true),
        )
        assertEquals(
            ExplorerLoadDecision.BLOCK_TOR_OFF,
            decision(url = mempoolOnion, torReady = false, torOff = true),
        )
        // Tor ready: both go through the proxy. Neither falls back to direct.
        assertEquals(
            ExplorerLoadDecision.LOAD_VIA_PROXY,
            decision(url = customOnion, torReady = true, torOff = false),
        )
        assertEquals(
            ExplorerLoadDecision.LOAD_VIA_PROXY,
            decision(url = mempoolOnion, torReady = true, torOff = false),
        )
        assertEquals(
            ExplorerLoadDecision.BLOCK_PROXY_UNSUPPORTED,
            explorerLoadDecision(
                proxyOverrideSupported = false,
                torReady = false,
                torOff = true,
                torRequired = ExplorerBinding.lookupRequiresTor(customOnion, walletRequiresTor = false),
            ),
        )
    }

    @Test
    fun `clearnet custom and mempool space still follow the wallet`() {
        assertFalse(ExplorerBinding.lookupRequiresTor(customClear, walletRequiresTor = false))
        assertFalse(
            ExplorerBinding.lookupRequiresTor(TxFactsResolver.MEMPOOL_CLEARNET_BASE, walletRequiresTor = false),
        )
        assertEquals(
            ExplorerLoadDecision.LOAD_DIRECT,
            decision(url = customClear, torReady = false, torOff = true),
        )
        assertEquals(
            ExplorerLoadDecision.LOAD_DIRECT,
            decision(url = TxFactsResolver.MEMPOOL_CLEARNET_BASE, torReady = false, torOff = true),
        )
        assertTrue(ExplorerBinding.lookupRequiresTor(customClear, walletRequiresTor = true))
        assertEquals(
            ExplorerLoadDecision.BLOCK_TOR_OFF,
            explorerLoadDecision(
                proxyOverrideSupported = true,
                torReady = false,
                torOff = true,
                torRequired = true,
            ),
        )
    }

    @Test
    fun `the host check is case insensitive and ignores path and userinfo tricks`() {
        assertTrue(ExplorerBinding.lookupRequiresTor("HTTP://Custom.ONION/tx/$txid", walletRequiresTor = false))
        assertTrue(ExplorerBinding.lookupRequiresTor("http://custom.onion:80/tx/$txid", walletRequiresTor = false))
        assertTrue(onionAuthority("http://custom.onion./tx/$txid"))
        assertTrue(onionAuthority(null, "Custom.ONION"))
        assertFalse(ExplorerBinding.lookupRequiresTor("https://evil.example/custom.onion", walletRequiresTor = false))
        assertFalse(
            ExplorerBinding.lookupRequiresTor("http://custom.onion@evil.example/tx/$txid", walletRequiresTor = false),
        )
        assertFalse(onionAuthority("https://evil.example/custom.onion", "evil.example"))
        assertTrue(onionAuthority("not a uri", "custom.onion"))
        assertFalse(onionHost("evil.example"))
        assertFalse(onionHost("not.onion.example"))
    }

    @Test
    fun `an encoded full width or dotted onion requires tor and an unparsed url fails closed`() {
        val encoded = "http://x.oni%6fn/tx/$txid"
        val fullWidth = "http://\uFF58.\uFF4F\uFF4E\uFF49\uFF4F\uFF4E/tx/$txid"
        val ideographicDot = "http://x.onion\u3002/tx/$txid"
        assertTrue(ExplorerBinding.lookupRequiresTor(encoded, walletRequiresTor = false))
        assertTrue(ExplorerBinding.lookupRequiresTor(fullWidth, walletRequiresTor = false))
        assertTrue(ExplorerBinding.lookupRequiresTor(ideographicDot, walletRequiresTor = false))
        assertTrue(onionHost("x.oni%6fn"))
        assertTrue(onionHost("\uFF58.\uFF4F\uFF4E\uFF49\uFF4F\uFF4E"))
        assertTrue(onionHost("x.onion\u3002"))
        // The percent-encoding is in the path, not the host.
        assertFalse(
            ExplorerBinding.lookupRequiresTor("https://evil.example/x.oni%6fn", walletRequiresTor = false),
        )
        // A network URL with no host fails closed. Hostless schemes do not:
        // data: images and mailto: links on a clearnet page must still load.
        assertTrue(ExplorerBinding.lookupRequiresTor("https://", walletRequiresTor = false))
        assertTrue(ExplorerBinding.lookupRequiresTor("https://bad host/x", walletRequiresTor = false))
        assertTrue(ExplorerBinding.lookupRequiresTor("ws://", walletRequiresTor = false))
        assertTrue(ExplorerBinding.lookupRequiresTor("wss://custom.onion/socket", walletRequiresTor = false))
        assertFalse(ExplorerBinding.lookupRequiresTor("ws://explorer.example/ws", walletRequiresTor = false))
        assertFalse(ExplorerBinding.lookupRequiresTor("data:image/png,aaaa", walletRequiresTor = false))
        assertFalse(ExplorerBinding.lookupRequiresTor("about:blank", walletRequiresTor = false))
        assertFalse(ExplorerBinding.lookupRequiresTor("mailto:user@example.com", walletRequiresTor = false))
        assertFalse(ExplorerBinding.lookupRequiresTor("intent://scan/#Intent;end", walletRequiresTor = false))
        assertFalse(ExplorerBinding.lookupRequiresTor("javascript:alert(1)", walletRequiresTor = false))
        assertFalse(ExplorerBinding.lookupRequiresTor("not a uri", walletRequiresTor = false))
        assertFalse(ExplorerBinding.lookupRequiresTor(null, walletRequiresTor = false))
        assertFalse(ExplorerBinding.lookupRequiresTor("   ", walletRequiresTor = false))
        assertFalse(ExplorerBinding.lookupRequiresTor("https://evil.example/tx/$txid", walletRequiresTor = false))
    }

    @Test
    fun `a direct session blocks a later onion navigation redirect and subresource`() {
        // Tor off, or Tor up somewhere else but this WebView loaded direct.
        for (ready in listOf(false, true)) {
            assertEquals(
                ExplorerFollowup.BLOCK_AND_EXPLAIN,
                follow(customOnion, mainFrame = true, torSession = false, directSession = true, torReady = ready),
            )
            assertEquals(
                ExplorerFollowup.BLOCK_AND_EXPLAIN,
                follow(mempoolOnion, mainFrame = true, torSession = false, directSession = true, torReady = ready),
            )
            assertEquals(
                ExplorerFollowup.BLOCK_SILENT,
                follow(
                    "http://custom.onion/pixel.png",
                    mainFrame = false,
                    torSession = false,
                    directSession = true,
                    torReady = ready,
                ),
            )
        }
        // Proxy applied later does not unlock onion loads for a session that started direct.
        assertEquals(
            ExplorerFollowup.BLOCK_AND_EXPLAIN,
            follow(customOnion, mainFrame = true, torSession = true, directSession = true, torReady = true),
        )
        assertEquals(
            ExplorerFollowup.BLOCK_SILENT,
            follow(
                "http://custom.onion/pixel.png",
                mainFrame = false,
                torSession = true,
                directSession = true,
                torReady = true,
            ),
        )
        // The clearnet page itself, and its clearnet subresources, still load.
        assertEquals(
            ExplorerFollowup.ALLOW,
            follow(customClear, mainFrame = true, torSession = false, directSession = true, torReady = false),
        )
        assertEquals(
            ExplorerFollowup.ALLOW,
            follow(
                "https://explorer.example/static/app.js",
                mainFrame = false,
                torSession = false,
                directSession = true,
                torReady = false,
            ),
        )
    }

    @Test
    fun `a tor session allows an onion only while the proxy is live`() {
        assertEquals(
            ExplorerFollowup.ALLOW,
            follow(customOnion, mainFrame = true, torSession = true, directSession = false, torReady = true),
        )
        assertEquals(
            ExplorerFollowup.ALLOW,
            follow(mempoolOnion, mainFrame = false, torSession = true, directSession = false, torReady = true),
        )
        // Tor dropped. Do not send the onion host out in the clear.
        assertEquals(
            ExplorerFollowup.BLOCK_AND_EXPLAIN,
            follow(customOnion, mainFrame = true, torSession = true, directSession = false, torReady = false),
        )
        assertEquals(
            ExplorerFollowup.BLOCK_SILENT,
            follow(
                "http://custom.onion/pixel.png",
                mainFrame = false,
                torSession = true,
                directSession = false,
                torReady = false,
            ),
        )
        // A clearnet navigation stays on whatever route the WebView already has.
        assertEquals(
            ExplorerFollowup.ALLOW,
            follow(customClear, mainFrame = true, torSession = true, directSession = false, torReady = true),
        )
    }

    @Test
    fun `the webview client rechecks navigations redirects and subresources`() {
        val web = source(
            "app/src/main/java/com/samourai/sentinel/ui/webview/ExplorerWebViewActivity.kt",
            "src/main/java/com/samourai/sentinel/ui/webview/ExplorerWebViewActivity.kt",
        )
        val client = web.substringAfter("binding.webView.webViewClient = object : WebViewClient()")
            .substringBefore("binding.webView.webChromeClient")
        assertTrue(client.contains("shouldOverrideUrlLoading"))
        assertTrue(client.contains("shouldInterceptRequest"))
        assertTrue(client.contains("followupFor(request)"))
        assertTrue(client.contains("explainOnionBlocked()"))
        assertTrue(client.contains("blockedOnionResponse()"))
        assertFalse(client.contains("loadUrl("))
        assertFalse(client.contains("markDirectAndLoad"))

        val follow = web.substringAfter("private fun followupFor(")
            .substringBefore("private fun blockedOnionResponse()")
        assertTrue(follow.contains("request.isForMainFrame || request.isRedirect"))
        assertTrue(follow.contains("explorerFollowup"))
        assertTrue(follow.contains("torSession = torSession"))
        assertTrue(follow.contains("directSession = directSession"))

        val blocked = web.substringAfter("private fun blockedOnionResponse()")
            .substringBefore("@SuppressLint")
        assertTrue(blocked.contains("WebResourceResponse"))
        assertTrue(blocked.contains("ByteArrayInputStream(ByteArray(0))"))
        assertFalse(blocked.contains("request"))

        val direct = web.substringAfter("private fun markDirectAndLoad()")
            .substringBefore("private fun markProxyAndLoad()")
        assertTrue(direct.contains("if (torSession) return"))
        assertTrue(direct.contains("directSession = true"))
        val proxy = web.substringAfter("private fun markProxyAndLoad()")
            .substringBefore("private fun explainOnionBlocked()")
        assertTrue(proxy.contains("torSession = true"))
        assertTrue(proxy.contains("load()"))
        assertFalse(proxy.contains("directSession = false"))

        val routing = web.substringAfter("private fun beginRouting()")
            .substringBefore("private fun showBlocked")
        assertTrue(routing.contains("markDirectAndLoad()"))
        assertFalse(routing.contains("-> load()"))
        assertTrue(web.contains("markProxyAndLoad()"))
        assertTrue(web.contains("explorer_tor_required_off"))
        assertTrue(web.contains("explorer_tor_required_unsupported"))
        assertFalse(web.contains("clearProxyOverride"))
    }

    private fun decision(url: String, torReady: Boolean, torOff: Boolean): ExplorerLoadDecision =
        explorerLoadDecision(
            proxyOverrideSupported = true,
            torReady = torReady,
            torOff = torOff,
            torRequired = ExplorerBinding.lookupRequiresTor(url, walletRequiresTor = false),
        )

    private fun follow(
        url: String,
        mainFrame: Boolean,
        torSession: Boolean,
        directSession: Boolean,
        torReady: Boolean,
    ): ExplorerFollowup = explorerFollowup(
        url = url,
        host = null,
        mainFrame = mainFrame,
        torSession = torSession,
        directSession = directSession,
        torReady = torReady,
    )

    private fun source(vararg relative: String): String {
        val file = relative.map { File(it) }.firstOrNull { it.isFile }
            ?: error("source not found from ${File(".").absolutePath}: ${relative.toList()}")
        return file.readText()
    }
}
