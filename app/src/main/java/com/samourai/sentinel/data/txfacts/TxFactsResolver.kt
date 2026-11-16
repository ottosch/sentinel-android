package com.samourai.sentinel.data.txfacts

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * #119 Route A: destination cascade for the live tx-facts fetch.
 * Pure, JVM-testable - no Android, no network. Production lookups
 * do not call [factsUrl]; [com.samourai.sentinel.ui.webview.ExplorerBinding]
 * is the gate, and it does not use the PUBLIC URL as a fallback.
 *
 * Disclosure class is host-derived, NEVER type-string-derived.
 * Only an `.onion` host is the Dojo's own explorer (SELF) - SR-23:
 * a pairing payload that publishes any clearnet URL, mempool.space
 * or another host, publishes a third party (PUBLIC). Userinfo
 * tricks (`x.onion@evil.example`) and path tricks
 * (`https://evil.example/onion.onion`) are PUBLIC too: only the
 * authority's host decides. A blank, schemeless, or otherwise
 * host-less URL stays PUBLIC: not a usable destination.
 * [factsUrl] still builds the clearnet API URL for that PUBLIC
 * rung; callers that want "no silent fallback" must stop at
 * [classify] instead of requesting the URL.
 *
 * Testnet: the PUBLIC API base gains `/testnet`. SELF is used as
 * declared.
 */
object TxFactsResolver {

    const val MEMPOOL_DISPLAY_NAME = "mempool.space"
    const val MEMPOOL_CLEARNET_BASE = "https://$MEMPOOL_DISPLAY_NAME"
    const val MEMPOOL_TESTNET_BASE = "$MEMPOOL_CLEARNET_BASE/testnet"

    /**
     * mempool.space's onion service. http only. Any onion host,
     * including this one, still classifies as the Dojo's own
     * explorer when a pairing publishes it.
     */
    const val MEMPOOL_ONION_HOST =
        "mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion"

    /** Page root for the explicit mempool.onion choice. Never https. */
    const val MEMPOOL_ONION_BASE = "http://$MEMPOOL_ONION_HOST"

    /**
     * Same `/testnet` segment the clearnet page uses, so a testnet
     * lookup hits the testnet explorer.
     */
    const val MEMPOOL_ONION_TESTNET_BASE = "$MEMPOOL_ONION_BASE/testnet"

    fun mempoolClearnetBase(testnet: Boolean): String =
        if (testnet) MEMPOOL_TESTNET_BASE else MEMPOOL_CLEARNET_BASE

    fun mempoolOnionBase(testnet: Boolean): String =
        if (testnet) MEMPOOL_ONION_TESTNET_BASE else MEMPOOL_ONION_BASE

    /** True when the URL's host is mempool.space's onion service. */
    fun isMempoolOnionHost(url: String?): Boolean {
        val host = normalizedHost(url)?.trimEnd('.') ?: return false
        return host.equals(MEMPOOL_ONION_HOST, ignoreCase = true)
    }

    enum class Destination { SELF, PUBLIC }

    data class Resolved(val destination: Destination, val url: String)

    fun classify(explorerUrl: String?): Destination =
        if (isOnionHost(explorerUrl)) {
            Destination.SELF
        } else {
            Destination.PUBLIC
        }

    /**
     * Host Chromium would use, or null when [url] is not an http(s)
     * URL. Tab, CR, and LF are removed first, the way the URL
     * parser removes them. OkHttp percent-decodes the host and
     * maps it with IDN. A scheme-less string has no host.
     */
    fun normalizedHost(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val stripped = stripUrlSpace(url.trim())
        if (stripped.isEmpty()) return null
        return stripped.toHttpUrlOrNull()?.host?.takeIf { it.isNotEmpty() }
    }

    /**
     * True when [host] is an `.onion` name after the same
     * percent-decoding and IDN mapping [normalizedHost] applies.
     * A trailing dot is still that host. Null and a name that
     * does not parse are not onion hosts.
     */
    fun hostIsOnion(host: String?): Boolean {
        val name = canonicalHost(host)?.trimEnd('.')?.lowercase() ?: return false
        return name.endsWith(".onion") && name.length > ".onion".length
    }

    /**
     * True only when the URL's host is an `.onion` name. Not the
     * userinfo, not a path segment: `x.onion@evil.example` and
     * `https://evil.example/x.onion` are not onion hosts. An
     * encoded `x.oni%6fn`, a full-width `ｘ.ｏｎｉｏｎ`, and
     * `x.onion。` are. The scheme is not consulted beyond http(s):
     * an onion explorer published with `https` stays the Dojo's own.
     * A URL that does not parse is not an onion host.
     */
    fun isOnionHost(url: String?): Boolean {
        val host = normalizedHost(url) ?: return false
        val name = host.trimEnd('.').lowercase()
        return name.endsWith(".onion") && name.length > ".onion".length
    }

    private fun canonicalHost(host: String?): String? {
        if (host.isNullOrBlank()) return null
        val cleaned = stripUrlSpace(host.trim()).trim('/')
        if (cleaned.isEmpty()) return null
        return "http://$cleaned/".toHttpUrlOrNull()?.host?.takeIf { it.isNotEmpty() }
    }

    private fun stripUrlSpace(value: String): String = buildString(value.length) {
        for (c in value) {
            if (c == '\t' || c == '\n' || c == '\r') continue
            append(c)
        }
    }

    /**
     * /api/tx/{txid} against the resolved base. Self bases are used
     * as-is (directory payloads carry bare onion roots). A blank,
     * schemeless, or otherwise host-less URL never reaches the
     * SELF rung (pinned by "garbage declared url is public not
     * crash" and "schemeless declared url is public not self"):
     * [isOnionHost] sees no host at all.
     */
    fun factsUrl(txid: String, selfExplorerUrl: String?, testnet: Boolean): Resolved {
        return when (classify(selfExplorerUrl)) {
            Destination.SELF -> Resolved(
                Destination.SELF,
                selfExplorerUrl!!.trim().trimEnd('/') + "/api/tx/" + txid,
            )
            Destination.PUBLIC -> Resolved(
                Destination.PUBLIC,
                (if (testnet) MEMPOOL_TESTNET_BASE else MEMPOOL_CLEARNET_BASE) +
                    "/api/tx/" + txid,
            )
        }
    }
}
