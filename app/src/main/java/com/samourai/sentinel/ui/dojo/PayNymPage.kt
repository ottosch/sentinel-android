package com.samourai.sentinel.ui.dojo

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.samourai.sentinel.R
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.ui.webview.ExplorerLoadDecision
import com.samourai.sentinel.ui.webview.ExplorerOpener
import com.samourai.sentinel.ui.webview.explorerLoadDecision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * "View on PayNym": the signer's page on PayNym's onion, opened in the
 * app's own explorer WebView screen ([ExplorerOpener.pageIntent]) under the
 * same Tor rules as an onion block explorer. The page loads only through the verified Tor
 * proxy. Tor off, or a WebView that can't be proxied, gets the screen's
 * explanation with the link to copy. It never loads directly.
 *
 * There is no third-party advisory. The link names PayNym, the page is an
 * onion reached only over Tor (like the paired Dojo's onion explorer, which
 * opens without one), and all it carries is the signer's public +name.
 *
 * PayNym's pages are at /+name, so the link needs the name PayNym gives for
 * the signed code ([PayNymLookup.name]: cached from the dialog's lookup, or
 * asked over Tor on the tap). No name, no page: the user is told instead.
 */
object PayNymPage {

    /** The WebView screen's title. */
    const val TITLE = "PayNym"

    /** The screen's page kind for a PayNym page. */
    const val PAGE_KIND = "paynym"

    /** The page for [nymName], or null when it isn't shaped like a +name. */
    fun urlFor(nymName: String?): String? = PayNymLinks.profileUrl(nymName)

    /**
     * The +name in a link this app built ([urlFor]), or null for anything
     * else: another host, scheme or port, a further path, a query or a
     * fragment, or a segment that isn't a +name.
     */
    fun nymOf(url: String?): String? {
        val prefix = PayNymLinks.BASE_URL + "/"
        if (url == null || !url.startsWith(prefix)) return null
        val nym = url.substring(prefix.length)
        return nym.takeIf { urlFor(it) == url }
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
     * The intent for the WebView screen, rebuilt from the +name in [url], or
     * null when [url] isn't a link this app built.
     */
    fun intent(context: Context, url: String?): Intent? {
        val target = urlFor(nymOf(url)) ?: return null
        return ExplorerOpener.pageIntent(context, target, TITLE, PAGE_KIND)
    }

    fun open(context: Context, url: String?) {
        intent(context, url)?.let(context::startActivity)
    }

    /** The page for [signedCode]'s PayNym, or null when PayNym gives no name for it. */
    suspend fun urlForSigner(lookup: PayNymLookup, signedCode: String): String? =
        urlFor(lookup.name(signedCode))

    /**
     * "View on PayNym" for a signed code: finds its +name ([urlForSigner]),
     * then opens the page, or says there is none to open. [onResult] is
     * invoked on the same scope when the lookup finishes (true if the
     * page was opened). When it is set, the caller shows the failure;
     * otherwise a toast is used. Cancelling the job stops the lookup.
     */
    fun openSigner(
        context: Context,
        scope: CoroutineScope,
        signedCode: String,
        lookup: PayNymLookup? = null,
        onResult: ((opened: Boolean) -> Unit)? = null,
    ): Job {
        val app = context.applicationContext
        val used = lookup ?: PayNymLookup(
            PayNymCache.forDirectory(app.noBackupFilesDir),
            torProxy = { SentinelTorManager.getProxy() },
        )
        return scope.launch {
            val url = try {
                urlForSigner(used, signedCode)
            } catch (e: CancellationException) {
                throw e
            }
            if (url != null) {
                open(context, url)
                onResult?.invoke(true)
            } else if (onResult != null) {
                onResult(false)
            } else {
                // Callers with no callback (the list-row link) keep the toast.
                Toast.makeText(app, R.string.paynym_page_not_found, Toast.LENGTH_LONG).show()
            }
        }
    }
}
