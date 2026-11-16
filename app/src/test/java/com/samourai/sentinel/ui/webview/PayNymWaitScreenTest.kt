package com.samourai.sentinel.ui.webview

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.TextView
import androidx.lifecycle.MutableLiveData
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.R
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.tor.TorState
import com.samourai.sentinel.ui.dojo.PayNymPage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import androidx.webkit.WebViewFeature
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowDialog
import java.time.Duration

/** This WebView "supports" the proxy override, as on a device, so Tor starting means wait. */
@Implements(WebViewFeature::class)
class ShadowWebViewFeatureProxySupported {
    companion object {
        @JvmStatic
        @Implementation
        fun isFeatureSupported(feature: String): Boolean = feature == WebViewFeature.PROXY_OVERRIDE
    }
}

/**
 * A PayNym page opened while Tor is starting: the "Connecting over Tor…"
 * screen, nothing fetched, the time limit, and the app's own error screen
 * with Retry and Copy link, which never shows the URL.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ShadowWebViewFeatureProxySupported::class], instrumentedPackages = ["androidx.webkit"])
class PayNymWaitScreenTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val nym = "+91xTx93x3"
    private val page = PayNymPage.urlFor(nym)!!

    private fun setTor(state: EnumTorState, progress: Int) {
        val f = SentinelTorManager::class.java.getDeclaredField("torStateLiveDataPrivate").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (f.get(SentinelTorManager) as MutableLiveData<TorState>).value =
            TorState().apply { this.state = state; progressIndicator = progress }
    }

    @After
    fun torOff() = setTor(EnumTorState.OFF, 0)

    private fun looper() = shadowOf(Looper.getMainLooper())

    /** The test looper's clock moves a few ms while the screen starts up. */
    private val slackMs = 20L

    private fun open(): ExplorerWebViewActivity {
        setTor(EnumTorState.STARTING, 40)
        val intent = PayNymPage.intent(app, page)!!
        val activity = Robolectric.buildActivity(ExplorerWebViewActivity::class.java, intent).setup().get()
        looper().idle()
        return activity
    }

    private fun ExplorerWebViewActivity.status(): View = findViewById(R.id.payNymStatus)
    private fun ExplorerWebViewActivity.text(id: Int) = findViewById<TextView>(id).text.toString()
    private fun ExplorerWebViewActivity.web(): WebView = findViewById(R.id.webView)

    /** Every piece of text on the status screen, buttons included. */
    private fun allText(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { allText(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Test
    fun `Tor starting shows Connecting over Tor and fetches nothing`() {
        val a = open()
        assertEquals(PayNymLoadPhase.WAITING_FOR_TOR, a.payNymPhase)
        assertEquals(View.VISIBLE, a.status().visibility)
        assertEquals("Connecting over Tor…", a.text(R.id.payNymStatusTitle))
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.payNymStatusProgress).visibility)
        assertEquals(View.GONE, a.findViewById<View>(R.id.payNymStatusActions).visibility)
        assertEquals(View.INVISIBLE, a.web().visibility)
        assertNull(shadowOf(a.web()).lastLoadedUrl)
        assertNull(ShadowDialog.getLatestDialog())
    }

    @Test
    fun `after 90 s without Tor the error screen shows, without the URL, and nothing was loaded`() {
        val a = open()
        looper().idleFor(Duration.ofMillis(PayNymLoad.TOR_WAIT_LIMIT_MS - slackMs))
        assertEquals(PayNymLoadPhase.WAITING_FOR_TOR, a.payNymPhase)
        looper().idleFor(Duration.ofMillis(2 * slackMs))
        assertEquals(PayNymLoadPhase.FAILED, a.payNymPhase)
        assertEquals("Couldn't reach PayNym over Tor", a.text(R.id.payNymStatusTitle))
        assertEquals(View.GONE, a.findViewById<View>(R.id.payNymStatusProgress).visibility)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.payNymStatusActions).visibility)
        assertEquals("Retry", a.text(R.id.payNymRetry))
        assertEquals("Copy link", a.text(R.id.payNymCopyLink))
        val texts = allText(a.status())
        for (t in texts) {
            for (bit in listOf(page, nym, "91xTx93x3", "http", ".onion", "paynym25")) {
                assertFalse("'$t' shows '$bit'", t.contains(bit, ignoreCase = true))
            }
        }
        assertEquals(View.INVISIBLE, a.web().visibility)
        assertNull(shadowOf(a.web()).lastLoadedUrl)
    }

    @Test
    fun `Copy link copies the page and stays, Retry waits for Tor again with a fresh limit`() {
        val a = open()
        looper().idleFor(Duration.ofMillis(PayNymLoad.TOR_WAIT_LIMIT_MS + 1))
        a.findViewById<View>(R.id.payNymCopyLink).performClick()
        val clip = (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        assertEquals(page, clip?.getItemAt(0)?.text?.toString())
        assertFalse(a.isFinishing)

        a.findViewById<View>(R.id.payNymRetry).performClick()
        looper().idle()
        assertEquals(PayNymLoadPhase.WAITING_FOR_TOR, a.payNymPhase)
        assertEquals("Connecting over Tor…", a.text(R.id.payNymStatusTitle))
        looper().idleFor(Duration.ofMillis(PayNymLoad.TOR_WAIT_LIMIT_MS - slackMs))
        assertEquals(PayNymLoadPhase.WAITING_FOR_TOR, a.payNymPhase)
        looper().idleFor(Duration.ofMillis(2 * slackMs))
        assertEquals(PayNymLoadPhase.FAILED, a.payNymPhase)
        assertNull(shadowOf(a.web()).lastLoadedUrl)
    }

    @Test
    fun `a page that doesn't load within 60 s, or a main-frame error, gets the error screen`() {
        val a = open()
        a.onPayNymEvent(PayNymLoadEvent.LOAD_STARTED)
        assertEquals("Loading PayNym over Tor…", a.text(R.id.payNymStatusTitle))
        assertEquals(View.INVISIBLE, a.web().visibility)
        // The Tor wait limit no longer applies; the page limit does.
        looper().idleFor(Duration.ofMillis(PayNymLoad.PAGE_LOAD_LIMIT_MS - slackMs))
        assertEquals(PayNymLoadPhase.LOADING, a.payNymPhase)
        looper().idleFor(Duration.ofMillis(2 * slackMs))
        assertEquals(PayNymLoadPhase.FAILED, a.payNymPhase)
        assertEquals("Couldn't reach PayNym over Tor", a.text(R.id.payNymStatusTitle))

        a.onPayNymEvent(PayNymLoadEvent.LOAD_STARTED)
        a.onPayNymEvent(PayNymLoadEvent.MAIN_FRAME_ERROR)
        // Chromium's error page commits afterwards: it stays hidden.
        a.onPayNymEvent(PayNymLoadEvent.PAGE_SHOWN)
        assertEquals(PayNymLoadPhase.FAILED, a.payNymPhase)
        assertEquals(View.INVISIBLE, a.web().visibility)
        assertEquals(View.VISIBLE, a.status().visibility)
    }

    @Test
    fun `a page that draws shows the WebView and no time limit is left running`() {
        val a = open()
        a.onPayNymEvent(PayNymLoadEvent.LOAD_STARTED)
        a.onPayNymEvent(PayNymLoadEvent.PAGE_SHOWN)
        assertEquals(PayNymLoadPhase.LOADED, a.payNymPhase)
        assertEquals(View.GONE, a.status().visibility)
        assertEquals(View.VISIBLE, a.web().visibility)
        looper().idleFor(Duration.ofMillis(PayNymLoad.TOR_WAIT_LIMIT_MS * 2))
        assertEquals(PayNymLoadPhase.LOADED, a.payNymPhase)
    }

    @Test
    fun `Tor stopping while waiting gives the Tor block dialog, not the error screen`() {
        val a = open()
        setTor(EnumTorState.OFF, 0)
        looper().idle()
        assertTrue(ShadowDialog.getLatestDialog()?.isShowing == true)
        assertEquals(PayNymLoadPhase.IDLE, a.payNymPhase)
        assertEquals(View.GONE, a.status().visibility)
        looper().idleFor(Duration.ofMillis(PayNymLoad.TOR_WAIT_LIMIT_MS * 2))
        assertEquals(PayNymLoadPhase.IDLE, a.payNymPhase)
        assertNull(shadowOf(a.web()).lastLoadedUrl)
    }
}
