package com.samourai.sentinel.ui.dojo

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Looper
import android.webkit.WebView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.R
import com.samourai.sentinel.ui.webview.ExplorerFollowup
import com.samourai.sentinel.ui.webview.ExplorerLoadDecision
import com.samourai.sentinel.ui.webview.ExplorerWebViewActivity
import com.samourai.sentinel.ui.webview.explorerFollowup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * "View on PayNym" opens the signer's page in the app's WebView screen, only
 * through the Tor proxy: the decision, the URL, and what the screen does when
 * it can't be proxied.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PayNymPageTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val qa = "PM8TJUJHkfdjkb5g5XaJuvnC63rUz7LXHnVqsnCd17dhDRupn9gW3QrgJJdNNGxsR4LSe4kFBMwarLbJVRQur9ZQfY4rtTHWWgdjLeE1svskeNoCEoum"
    private val nym = "+91xTx93x3"
    private val page = "http://paynym25chftmsywv4v2r67agbrr62lcxagsf4tymbzpeeucucy2ivad.onion/$nym"

    @Test
    fun `Tor ready and the proxy supported loads through the proxy`() {
        assertEquals(ExplorerLoadDecision.LOAD_VIA_PROXY, PayNymPage.decision(proxyOverrideSupported = true, torReady = true, torOff = false))
    }

    @Test
    fun `Tor off blocks, Tor starting waits`() {
        assertEquals(ExplorerLoadDecision.BLOCK_TOR_OFF, PayNymPage.decision(proxyOverrideSupported = true, torReady = false, torOff = true))
        assertEquals(ExplorerLoadDecision.WAIT_FOR_TOR, PayNymPage.decision(proxyOverrideSupported = true, torReady = false, torOff = false))
    }

    @Test
    fun `a WebView that can't be proxied blocks, whatever Tor does`() {
        for (ready in listOf(true, false)) for (off in listOf(true, false)) {
            assertEquals(ExplorerLoadDecision.BLOCK_PROXY_UNSUPPORTED, PayNymPage.decision(false, ready, off))
        }
    }

    @Test
    fun `no state ever loads directly`() {
        val direct = setOf(ExplorerLoadDecision.LOAD_DIRECT, ExplorerLoadDecision.OFFER_DIRECT_UNSUPPORTED)
        for (proxy in listOf(true, false)) for (ready in listOf(true, false)) for (off in listOf(true, false)) {
            assertFalse("$proxy $ready $off", PayNymPage.decision(proxy, ready, off) in direct)
        }
    }

    @Test
    fun `a page opened off Tor is never fetched later, and a Tor session doesn't fall back`() {
        val host = PayNymLinks.ONION_HOST
        // Started direct (never for PayNym, but the screen's rule holds anyway).
        assertEquals(ExplorerFollowup.BLOCK_AND_EXPLAIN, explorerFollowup(page, host, mainFrame = true, torSession = false, directSession = true, torReady = true))
        assertEquals(ExplorerFollowup.BLOCK_SILENT, explorerFollowup(page, host, mainFrame = false, torSession = false, directSession = true, torReady = true))
        // On Tor, then Tor drops: blocked, not loaded directly.
        assertEquals(ExplorerFollowup.BLOCK_AND_EXPLAIN, explorerFollowup(page, host, mainFrame = true, torSession = true, directSession = false, torReady = false))
        assertEquals(ExplorerFollowup.ALLOW, explorerFollowup(page, host, mainFrame = true, torSession = true, directSession = false, torReady = true))
    }

    @Test
    fun `the URL is the signer's +name on the PayNym onion, never the code`() {
        assertEquals(page, PayNymPage.urlFor(nym))
        assertEquals(nym, PayNymPage.nymOf(page))
        assertNull(PayNymPage.urlFor(null))
        assertNull(PayNymPage.urlFor("not a nym"))
        assertNull(PayNymPage.urlFor(qa))
        assertNull(PayNymPage.urlFor("91xTx93x3"))
        assertNull(PayNymPage.urlFor("$nym/avatar"))
        for (other in listOf(
            null, "", page + "/", "$page/avatar", "$page?x=1", "$page#f",
            page.replace("http://", "https://"),
            page.replace(".onion/", ".onion:8080/"),
            page.replace("paynym25", "paynym26"),
            page.replace("/+", "/"),
            page.replace(nym, qa),
            "http://example.com/$nym",
            "http://evil.${PayNymLinks.ONION_HOST}/$nym",
            "https://paynym.rs/$nym",
            page + "x".repeat(64),
        )) {
            assertNull(other, PayNymPage.nymOf(other))
            assertNull(other, PayNymPage.intent(app, other))
        }
    }

    @Test
    fun `the intent opens the WebView screen titled PayNym with the rebuilt URL`() {
        val intent = PayNymPage.intent(app, page)!!
        assertEquals(ExplorerWebViewActivity::class.java.name, intent.component?.className)
        assertEquals(page, intent.getStringExtra(ExplorerWebViewActivity.EXTRA_URL))
        assertEquals("PayNym", intent.getStringExtra(ExplorerWebViewActivity.EXTRA_TITLE))
        assertEquals(PayNymPage.PAGE_KIND, intent.getStringExtra(ExplorerWebViewActivity.EXTRA_PAGE))
    }

    @Test
    fun `the screen loads nothing without the Tor proxy, and offers to copy the link`() {
        val intent = PayNymPage.intent(app, page)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = Robolectric.buildActivity(ExplorerWebViewActivity::class.java, intent).setup().get()
        assertEquals("PayNym", activity.title.toString())
        val web = activity.findViewById<WebView>(R.id.webView)
        // Nothing was handed to the WebView.
        assertNull(shadowOf(web).lastLoadedUrl)
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        val message = dialog.findViewById<android.widget.TextView>(android.R.id.message)?.text.toString()
        assertTrue(message, message.startsWith("PayNym is an onion site, so it opens only through Tor"))
        val copy = dialog.getButton(DialogInterface.BUTTON_NEUTRAL)
        assertNotNull(copy)
        assertEquals("Copy link", copy.text.toString())
        copy.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val clip = (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        assertEquals(page, clip?.getItemAt(0)?.text?.toString())
        assertNull(shadowOf(web).lastLoadedUrl)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `the screen closes on a link this app didn't build`() {
        val intent = Intent(app, ExplorerWebViewActivity::class.java)
            .putExtra(ExplorerWebViewActivity.EXTRA_URL, "http://example.com/$nym")
            .putExtra(ExplorerWebViewActivity.EXTRA_TITLE, "PayNym")
            .putExtra(ExplorerWebViewActivity.EXTRA_PAGE, PayNymPage.PAGE_KIND)
        val activity = Robolectric.buildActivity(ExplorerWebViewActivity::class.java, intent).setup().get()
        assertTrue(activity.isFinishing)
        assertNull(shadowOf(activity.findViewById<WebView>(R.id.webView)).lastLoadedUrl)
    }
}
