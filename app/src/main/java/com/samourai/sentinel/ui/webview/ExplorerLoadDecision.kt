package com.samourai.sentinel.ui.webview

import com.samourai.sentinel.data.txfacts.TxFactsResolver

/**
 * #472: how the explorer WebView may load, decided from routing state.
 *
 * The rule: when Tor is required (paired Dojo or the user's Tor toggle,
 * [com.samourai.sentinel.core.SentinelState.isTorRequired]), the explorer
 * loads only through a verified Tor proxy - never directly. Direct loading
 * stays available, as an explicit choice, only when Tor is optional.
 */
enum class ExplorerLoadDecision {
    /** Install the Tor proxy override, then load. */
    LOAD_VIA_PROXY,

    /** Tor is optional and off: load directly. */
    LOAD_DIRECT,

    /** Tor is starting: fetch nothing until it is ready. */
    WAIT_FOR_TOR,

    /** Tor is required but off: fetch nothing, explain, offer an exit. */
    BLOCK_TOR_OFF,

    /** Tor is required but this WebView cannot be proxied: explain and exit. */
    BLOCK_PROXY_UNSUPPORTED,

    /**
     * WebView cannot be proxied and Tor is optional: the user may choose
     * to continue without Tor.
     */
    OFFER_DIRECT_UNSUPPORTED,
}

fun explorerLoadDecision(
    proxyOverrideSupported: Boolean,
    torReady: Boolean,
    torOff: Boolean,
    torRequired: Boolean,
): ExplorerLoadDecision = when {
    !proxyOverrideSupported ->
        if (torRequired) ExplorerLoadDecision.BLOCK_PROXY_UNSUPPORTED
        else ExplorerLoadDecision.OFFER_DIRECT_UNSUPPORTED
    torReady -> ExplorerLoadDecision.LOAD_VIA_PROXY
    torOff ->
        if (torRequired) ExplorerLoadDecision.BLOCK_TOR_OFF
        else ExplorerLoadDecision.LOAD_DIRECT
    else -> ExplorerLoadDecision.WAIT_FOR_TOR
}

/**
 * Tor was required and this screen has not loaded a page yet. STARTING
 * still waits ([explorerLoadDecision] returns [ExplorerLoadDecision.WAIT_FOR_TOR]).
 * If Tor then reports off, that is the same block as opening while Tor
 * is already off. A page that already loaded is left as it is.
 */
fun explorerWhenTorStopsBeforeLoad(
    torRequired: Boolean,
    pageLoaded: Boolean,
): ExplorerLoadDecision? =
    if (torRequired && !pageLoaded) ExplorerLoadDecision.BLOCK_TOR_OFF else null

/**
 * What to do with one request after the WebView is already open.
 * The first URL is [explorerLoadDecision]. This is every later
 * main-frame navigation, redirect, and subresource.
 */
enum class ExplorerFollowup {
    /** Not an onion, or this WebView is already on a live Tor proxy. */
    ALLOW,

    /** Main-frame onion while this WebView is not on Tor. Cancel and explain. */
    BLOCK_AND_EXPLAIN,

    /** Subresource onion. Answer locally so the host is never resolved. */
    BLOCK_SILENT,
}

/**
 * True when [url]'s host, or [host] when one was supplied, is an
 * `.onion` name after percent-decoding and IDN mapping. A trailing
 * dot is still that host. Userinfo and path are not the host.
 * A value that was supplied and does not parse fails closed: it
 * is treated as requiring Tor. A null URL with no host does not.
 */
fun onionAuthority(url: String?, host: String? = null): Boolean {
    if (onionHost(host)) return true
    if (!url.isNullOrBlank() && TxFactsResolver.isOnionHost(url)) return true
    if (TxFactsResolver.normalizedHost(url) != null) return false
    if (!host.isNullOrBlank()) return false
    return !url.isNullOrBlank()
}

fun onionHost(host: String?): Boolean = TxFactsResolver.hostIsOnion(host)

/**
 * An onion target is allowed only when this WebView started on the Tor
 * proxy, has not taken a direct load, and Tor is still ready. Otherwise
 * a main-frame navigation or redirect is blocked and explained, and a
 * subresource is answered locally. There is no direct load of that host.
 * A session that started direct stays blocked even if the proxy is
 * applied later. A Tor session does not get a direct onion load when
 * Tor drops.
 */
fun explorerFollowup(
    url: String?,
    host: String?,
    mainFrame: Boolean,
    torSession: Boolean,
    directSession: Boolean,
    torReady: Boolean,
): ExplorerFollowup {
    if (!onionAuthority(url, host)) return ExplorerFollowup.ALLOW
    if (torSession && !directSession && torReady) return ExplorerFollowup.ALLOW
    return if (mainFrame) ExplorerFollowup.BLOCK_AND_EXPLAIN else ExplorerFollowup.BLOCK_SILENT
}
