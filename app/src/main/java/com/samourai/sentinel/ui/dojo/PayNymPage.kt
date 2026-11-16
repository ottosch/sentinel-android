package com.samourai.sentinel.ui.dojo

import android.content.Context
import android.content.Intent
import com.samourai.sentinel.ui.webview.ExplorerLoadDecision
import com.samourai.sentinel.ui.webview.ExplorerOpener
import com.samourai.sentinel.ui.webview.explorerLoadDecision

/**
 * "View on PayNym": the signer's page on PayNym's onion, opened in the
 * app's own explorer WebView screen ([ExplorerOpener.pageIntent]) under the
 * same Tor rules as an onion block explorer. The page loads only through the verified Tor
 * proxy. Tor off, or a WebView that can't be proxied, gets the screen's
 * explanation with the link to copy. It never loads directly.
 *
 * There is no third-party advisory. The link names PayNym, the page is an
 * onion reached only over Tor (like the paired Dojo's onion explorer, which
 * opens without one), and all it carries is the signed code, which is public.
 */
object PayNymPage {

    /** The WebView screen's title. */
    const val TITLE = "PayNym"

    /** The screen's page kind for a PayNym page. */
    const val PAGE_KIND = "paynym"

    /** The page for [signedCode], or null when it isn't shaped like a payment code. */
    fun urlFor(signedCode: String?): String? = PayNymLinks.profileUrl(signedCode)

    /**
     * The code in a link this app built ([urlFor]), or null for anything
     * else: another host, scheme or port, a further path, a query or a
     * fragment, or a segment that isn't a payment code.
     */
    fun signedCodeOf(url: String?): String? {
        val prefix = PayNymLinks.BASE_URL + "/"
        if (url == null || !url.startsWith(prefix)) return null
        val code = url.substring(prefix.length)
        return code.takeIf { urlFor(it) == url }
    }

    /**
     * How the screen may load a PayNym page: [explorerLoadDecision] with Tor
     * always required, so the answer is never a direct load.
     */
    fun decision(
        proxyOverrideSupported: Boolean,
        torReady: Boolean,
        torOff: Boolean,
    ): ExplorerLoadDecision = explorerLoadDecision(
        proxyOverrideSupported = proxyOverrideSupported,
        torReady = torReady,
        torOff = torOff,
        torRequired = true,
    )

    /**
     * The intent for the WebView screen, rebuilt from the code in [url], or
     * null when [url] isn't a link this app built.
     */
    fun intent(context: Context, url: String?): Intent? {
        val target = urlFor(signedCodeOf(url)) ?: return null
        return ExplorerOpener.pageIntent(context, target, TITLE, PAGE_KIND)
    }

    fun open(context: Context, url: String?) {
        intent(context, url)?.let(context::startActivity)
    }
}
