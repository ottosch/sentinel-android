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

private val NETWORK_SCHEMES = setOf("http", "https", "ws", "wss")

/**
 * True when [url]'s host, or [host] when one was supplied, is an
 * `.onion` name after percent-decoding and IDN mapping. A trailing
 * dot is still that host. Userinfo and path are not the host.
 *
 * Fail closed only for http, https, ws, and wss when that host
 * cannot be parsed. `data:`, `about:`, `mailto:`, `intent:`, and
 * any other hostless scheme are not onion loads.
 */
fun onionAuthority(url: String?, host: String? = null): Boolean {
    if (onionHost(host)) return true
    if (url.isNullOrBlank()) return false
    val parsed = networkHost(url)
    if (parsed != null) return TxFactsResolver.hostIsOnion(parsed)
    if (!host.isNullOrBlank()) return false
    val scheme = urlScheme(url)
    return scheme != null && scheme in NETWORK_SCHEMES
}

/** Scheme of [url], or null when it does not start with one. */
internal fun urlScheme(url: String?): String? {
    if (url.isNullOrBlank()) return null
    val trimmed = url.trim()
    val colon = trimmed.indexOf(':')
    if (colon <= 0) return null
    val scheme = trimmed.substring(0, colon)
    if (!scheme[0].isLetter()) return null
    if (scheme.any { !(it.isLetterOrDigit() || it == '+' || it == '-' || it == '.') }) return null
    return scheme.lowercase()
}

/**
 * Host of an http(s)/ws(s) URL. ws and wss are read as http and
 * https so the host check does not depend on OkHttp refusing them.
 * Null for any other scheme, and for a network URL with no host.
 */
private fun networkHost(url: String): String? {
    val scheme = urlScheme(url) ?: return null
    if (scheme !in NETWORK_SCHEMES) return null
    val trimmed = url.trim()
    val asHttp = when (scheme) {
        "ws" -> "http" + trimmed.substring(scheme.length)
        "wss" -> "https" + trimmed.substring(scheme.length)
        else -> trimmed
    }
    return TxFactsResolver.normalizedHost(asHttp)
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
