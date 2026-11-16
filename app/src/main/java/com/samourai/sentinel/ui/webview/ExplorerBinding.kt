package com.samourai.sentinel.ui.webview

import com.samourai.sentinel.core.DerivedNetwork
import com.samourai.sentinel.core.NetworkClassifier
import com.samourai.sentinel.data.txfacts.TxFactsResolver
import com.samourai.sentinel.ui.dojo.normalizeDeclaredNetwork
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

/**
 * Which explorer a lookup may use, decided before any request.
 *
 * An explicit Custom URL, the onion service, or the clearnet mempool
 * site wins over the paired Dojo and is kept across unpairing. The
 * onion service is http and never loads except through Tor. An
 * existing mempool choice stays the clearnet site. Otherwise the paired Dojo's
 * published explorer is used when it is the Dojo's own explorer -
 * an onion host (SR-23: any clearnet URL, the public mempool host
 * or another host, is a third party and never binds as the paired
 * explorer) - and its network matches the active network. Anything
 * else stays unbound: no silent third-party URL.
 *
 * Network, both directions: a testnet explorer is not used on
 * mainnet, and a mainnet explorer is not used on testnet. The Dojo's
 * declared network is the contract. With no declaration, a `/test`
 * segment on the Dojo API (the #250 whisper) or a testnet segment on
 * the explorer URL means testnet; a signet path matches neither
 * network. No testnet evidence means mainnet, which is what a manual
 * mainnet pairing looks like (`…/v2`, bare explorer root). A
 * declaration that conflicts with a positive testnet or signet path
 * matches nothing.
 */
object ExplorerBinding {

    const val CHOICE_PAIRED = "Paired"
    const val CHOICE_CUSTOM = "Custom"
    const val TXID_PLACEHOLDER = "{txid}"
    const val ADDRESS_PLACEHOLDER = "{address}"

    /** A non-onion label this short is shown whole. */
    const val LABEL_KEEP_WHOLE = 21

    /** Characters kept from the start of an onion host, before ".onion". */
    const val ONION_LABEL_HEAD = 7

    /** Characters kept from the end of an onion host, before ".onion". */
    const val ONION_LABEL_TAIL = 4

    /** Saved value and display label for the mempool onion service. */
    const val CHOICE_MEMPOOL_ONION = "mempool.onion"

    enum class ExplorerKind { PAIRED, CUSTOM, MEMPOOL, MEMPOOL_ONION }

    enum class ExplorerPage { TX, ADDRESS }

    enum class ExplorerChain { MAINNET, TESTNET }

    data class ExplorerFacts(
        val savedSelection: String?,
        val customUrl: String?,
        val publishedUrl: String?,
        val appIsTestnet: Boolean,
        val dojoDeclaredNetwork: String?,
        val dojoApiUrl: String?,
    )

    sealed class ExplorerResolution {
        data class Bound(
            val kind: ExplorerKind,
            val baseUrl: String,
            val displayUrl: String,
        ) : ExplorerResolution()

        object Unbound : ExplorerResolution()
    }

    /**
     * Blockstream, Blockchair, empty, unknown, and Custom with no URL
     * become the automatic paired choice. A saved Custom with a URL
     * stays Custom. "mempool" and the clearnet display name stay the
     * clearnet site. [CHOICE_MEMPOOL_ONION] stays the onion service.
     */
    fun migratedSelection(saved: String?, customUrl: String?): String {
        val value = saved?.trim().orEmpty()
        if (isMempoolOnion(value)) return CHOICE_MEMPOOL_ONION
        if (isExplicitMempool(value)) return TxFactsResolver.MEMPOOL_DISPLAY_NAME
        if (value.equals(CHOICE_CUSTOM, ignoreCase = true) && !customUrl.isNullOrBlank()) {
            return CHOICE_CUSTOM
        }
        return CHOICE_PAIRED
    }

    fun isMempoolOnion(saved: String?): Boolean {
        val value = saved?.trim()?.lowercase() ?: return false
        return value == CHOICE_MEMPOOL_ONION.lowercase()
    }

    fun isExplicitMempool(saved: String?): Boolean {
        val value = saved?.trim()?.lowercase() ?: return false
        return value == TxFactsResolver.MEMPOOL_DISPLAY_NAME.lowercase() || value == "mempool"
    }

    /**
     * Any host ending in `.onion` is reachable only through Tor,
     * whatever the saved choice. mempool.onion is that rule, not a
     * special case. Every other page follows the wallet's own Tor
     * requirement. A null URL does not add a requirement. An http,
     * https, ws, or wss URL whose host does not parse does: fail
     * closed. A hostless scheme such as data: or mailto: does not.
     * The host is the authority only: a path or a userinfo trick
     * is not an onion (SR-23).
     */
    fun lookupRequiresTor(url: String?, walletRequiresTor: Boolean): Boolean =
        walletRequiresTor || onionAuthority(url)

    fun resolve(facts: ExplorerFacts): ExplorerResolution {
        val selection = migratedSelection(facts.savedSelection, facts.customUrl)
        if (selection == CHOICE_MEMPOOL_ONION) {
            val base = TxFactsResolver.mempoolOnionBase(facts.appIsTestnet)
            return ExplorerResolution.Bound(
                kind = ExplorerKind.MEMPOOL_ONION,
                baseUrl = base,
                displayUrl = base,
            )
        }
        if (selection == TxFactsResolver.MEMPOOL_DISPLAY_NAME) {
            val base = TxFactsResolver.mempoolClearnetBase(facts.appIsTestnet)
            return ExplorerResolution.Bound(
                kind = ExplorerKind.MEMPOOL,
                baseUrl = base,
                displayUrl = base,
            )
        }
        if (selection == CHOICE_CUSTOM) {
            val template = facts.customUrl?.trim().orEmpty()
            if (template.isNotEmpty()) {
                // A stored template that no longer passes the check
                // is not a bound explorer, and it does not fall
                // through to the paired one. The lookup stays here
                // until the user chooses again.
                if (customTemplateRejected(template)) return ExplorerResolution.Unbound
                return ExplorerResolution.Bound(
                    kind = ExplorerKind.CUSTOM,
                    baseUrl = template,
                    displayUrl = template,
                )
            }
        }
        return pairedExplorer(facts) ?: ExplorerResolution.Unbound
    }

    fun pageUrl(resolution: ExplorerResolution, page: ExplorerPage, id: String): String? {
        val bound = resolution as? ExplorerResolution.Bound ?: return null
        val clean = id.trim()
        if (clean.isEmpty()) return null
        return when (bound.kind) {
            ExplorerKind.CUSTOM -> customPage(bound.baseUrl, page, clean) ?: return null
            else -> joinBase(bound.baseUrl, page, clean)
        }
    }

    /** Root used by the Tor health check. Null when there is nothing to probe. */
    fun probeBase(resolution: ExplorerResolution): String? {
        val bound = resolution as? ExplorerResolution.Bound ?: return null
        val root = when (bound.kind) {
            ExplorerKind.CUSTOM -> customRoot(bound.baseUrl)
            else -> bound.baseUrl
        }.trim().trimEnd('/')
        if (!root.startsWith("http://") && !root.startsWith("https://")) return null
        return root
    }

    /**
     * Tip height first; the explorer root if that path is not served.
     * Empty when [baseUrl] is not an absolute http(s) URL.
     */
    fun healthRequestUrls(baseUrl: String): List<String> {
        val root = baseUrl.trim().trimEnd('/')
        if (!root.startsWith("http://") && !root.startsWith("https://")) return emptyList()
        return listOf("$root/api/blocks/tip/height", root)
    }

    /**
     * Short label for an explorer URL.
     *
     * An onion drops its scheme and path and keeps the first
     * [ONION_LABEL_HEAD] and last [ONION_LABEL_TAIL] characters of the
     * host before ".onion" (`mempool…r6ad.onion`). Any other value
     * longer than [LABEL_KEEP_WHOLE] keeps its first 14 and last 6
     * characters. A short value is shown whole.
     */
    fun truncateExplorerLabel(url: String): String {
        shortenOnion(url)?.let { return it }
        if (url.length > LABEL_KEEP_WHOLE) return "${url.take(14)}…${url.takeLast(6)}"
        return url
    }

    /**
     * `mempool…r6ad.onion` when [value] contains an onion host. Scheme,
     * port, and path are ignored. Null when there is no onion host.
     * A host of [ONION_LABEL_HEAD] + [ONION_LABEL_TAIL] characters or
     * fewer is shown whole.
     */
    internal fun shortenOnion(value: String): String? {
        val match = ONION_HOST.find(value.trim()) ?: return null
        val label = match.groupValues[1].lowercase()
        if (label.isEmpty()) return null
        val suffix = ".onion"
        if (label.length <= ONION_LABEL_HEAD + ONION_LABEL_TAIL) return label + suffix
        return label.take(ONION_LABEL_HEAD) + "…" + label.takeLast(ONION_LABEL_TAIL) + suffix
    }

    fun hostLabel(resolution: ExplorerResolution): String? {
        val bound = resolution as? ExplorerResolution.Bound ?: return null
        return when (bound.kind) {
            ExplorerKind.MEMPOOL -> TxFactsResolver.MEMPOOL_DISPLAY_NAME
            ExplorerKind.MEMPOOL_ONION -> CHOICE_MEMPOOL_ONION
            else -> truncateExplorerLabel(bound.displayUrl)
        }
    }

    /**
     * True when a load to [url] goes to a party that is not the
     * paired Dojo's own explorer, so the third-party advisory is
     * owed before the launch. That is either mempool choice and any
     * Custom page URL whose host is not an onion. mempool.onion is
     * still a third party: the onion only changes the route. A bound
     * PAIRED explorer is always an onion host, so it owes no
     * advisory. The page URL decides, not the saved template: a
     * Custom template with placeholders does not parse as a URI, but
     * the substituted page URL does.
     */
    fun requiresAdvisory(resolution: ExplorerResolution, url: String): Boolean {
        val bound = resolution as? ExplorerResolution.Bound ?: return false
        return when (bound.kind) {
            ExplorerKind.PAIRED -> false
            ExplorerKind.MEMPOOL, ExplorerKind.MEMPOOL_ONION -> true
            ExplorerKind.CUSTOM -> !TxFactsResolver.isOnionHost(url)
        }
    }

    private fun pairedExplorer(facts: ExplorerFacts): ExplorerResolution.Bound? {
        val raw = facts.publishedUrl ?: return null
        if (raw.isBlank()) return null
        val trimmed = raw.trim()
        if (TxFactsResolver.classify(trimmed) != TxFactsResolver.Destination.SELF) return null
        val chain = publishedChain(trimmed, facts.dojoDeclaredNetwork, facts.dojoApiUrl) ?: return null
        val app = if (facts.appIsTestnet) ExplorerChain.TESTNET else ExplorerChain.MAINNET
        if (chain != app) return null
        return ExplorerResolution.Bound(
            kind = ExplorerKind.PAIRED,
            baseUrl = trimmed.trimEnd('/'),
            displayUrl = raw,
        )
    }

    /**
     * Chain of a URL that already classified as the Dojo's own explorer.
     * Null means it matches neither active network.
     */
    fun publishedChain(url: String, declaredRaw: String?, apiUrl: String?): ExplorerChain? {
        val hint = pathHint(url)
        if (hint == PathHint.SIGNET) return null
        val declared = normalizeDeclaredNetwork(declaredRaw)
        val apiTest = NetworkClassifier.fromUrl(apiUrl) == DerivedNetwork.TESTNET
        if (declared == DerivedNetwork.MAINNET && (apiTest || hint == PathHint.TESTNET)) return null
        if (declared == DerivedNetwork.MAINNET) return ExplorerChain.MAINNET
        if (declared == DerivedNetwork.TESTNET) return ExplorerChain.TESTNET
        if (apiTest || hint == PathHint.TESTNET) return ExplorerChain.TESTNET
        return ExplorerChain.MAINNET
    }

    private enum class PathHint { TESTNET, SIGNET, UNSPECIFIED }

    private val TESTNET_SEGMENTS = setOf("test", "testnet", "testnet3", "testnet4")

    /** Host only: scheme, user, port, and path stay outside the match. */
    private val ONION_HOST = Regex(
        """(?i)(?:^|://|@)([a-z0-9-]+(?:\.[a-z0-9-]+)*)\.onion(?=$|[/:?#])"""
    )

    private fun pathHint(url: String): PathHint {
        val path = runCatching { URI(url.trim()).path }.getOrNull() ?: return PathHint.UNSPECIFIED
        val segments = path.split('/').filter { it.isNotBlank() }.map { it.lowercase() }
        return when {
            segments.any { it == "signet" } -> PathHint.SIGNET
            segments.any { it in TESTNET_SEGMENTS } -> PathHint.TESTNET
            else -> PathHint.UNSPECIFIED
        }
    }

    private fun joinBase(base: String, page: ExplorerPage, id: String): String {
        val root = base.trim().trimEnd('/')
        val segment = if (page == ExplorerPage.TX) "tx" else "address"
        return "$root/$segment/$id"
    }

    private fun customPage(template: String, page: ExplorerPage, id: String): String? {
        if (page == ExplorerPage.TX) {
            return if (template.contains(TXID_PLACEHOLDER, ignoreCase = true)) {
                // A value that is not a txid must not be written into
                // the template. `https:{txid}` would otherwise take
                // whatever string the caller passed as its host.
                if (!isHexTxid(id)) return null
                template.replace(TXID_PLACEHOLDER, id, ignoreCase = true)
            } else {
                template.trimEnd('/') + "/" + id
            }
        }
        if (template.contains(ADDRESS_PLACEHOLDER, ignoreCase = true)) {
            return template.replace(ADDRESS_PLACEHOLDER, id, ignoreCase = true)
        }
        val base = template
            .replace(Regex("(?i)/tx/\\{txid\\}/?$"), "")
            .replace(Regex("(?i)/tx/?$"), "")
            .trimEnd('/')
        return "$base/address/$id"
    }

    /**
     * True when a Custom template must not be saved, imported, or
     * bound. Blank is a separate "nothing entered" error.
     *
     * After the placeholders are stood in, only an http(s) URL with
     * no userinfo is accepted. `\` , tab, CR, LF, and every other
     * control character are refused, including the single-slash
     * form `https:/user@host`. A second scheme in the authority or
     * the path is refused; one in the query is not. The host must
     * be the same for two different placeholder values, must be
     * ASCII (punycode is allowed), and must not be an octal, hex,
     * or other odd IPv4 form. A strict dotted quad such as
     * `127.0.0.1` is allowed.
     */
    fun customTemplateRejected(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.any { it == '\\' || it.isISOControl() }) return true
        if (hostHasPlaceholder(trimmed)) return true
        if (embeddedScheme(trimmed)) return true
        if (authorityHasUserinfo(trimmed)) return true
        if (rawHostHasNonAscii(trimmed)) return true
        val first = substitutedHost(trimmed, "aaaa", "addr-a")
        val second = substitutedHost(trimmed, "bbbb", "addr-b")
        if (first == null || second == null || !first.equals(second, ignoreCase = true)) return true
        val parsed = substitute(trimmed, "aaaa", "addr-a").toHttpUrlOrNull() ?: return true
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return true
        return ambiguousIpv4(parsed.host)
    }

    /**
     * Backup import. A rejected template is not a usable explorer.
     * When Custom is the saved choice the URL is kept so the choice
     * does not silently become the paired explorer; [resolve] then
     * leaves the lookup unbound. Any other choice drops the URL.
     */
    fun scrubImportedExplorer(customUrl: String?, selection: String?): ImportedExplorer {
        if (customUrl.isNullOrBlank() || !customTemplateRejected(customUrl)) {
            return ImportedExplorer(customUrl, selection, rejected = false)
        }
        val customSelected = selection?.trim()?.equals(CHOICE_CUSTOM, ignoreCase = true) == true
        return if (customSelected) {
            ImportedExplorer(customUrl, selection, rejected = true)
        } else {
            ImportedExplorer("", selection, rejected = true)
        }
    }

    data class ImportedExplorer(
        val customUrl: String?,
        val selection: String?,
        val rejected: Boolean,
    )

    private fun hostHasPlaceholder(url: String): Boolean {
        val host = authorityHost(url)
        return host.contains(TXID_PLACEHOLDER, ignoreCase = true) ||
            host.contains(ADDRESS_PLACEHOLDER, ignoreCase = true)
    }

    /** `@` in the authority, including an empty `https://@host`. */
    private fun authorityHasUserinfo(url: String): Boolean = '@' in authorityOf(url)

    private fun authorityHost(url: String): String {
        val hostPort = authorityOf(url).substringAfterLast('@')
        return if (hostPort.startsWith("[")) {
            hostPort.removePrefix("[").substringBefore(']')
        } else {
            hostPort.substringBefore(':')
        }
    }

    private fun authorityOf(url: String): String {
        val scheme = url.indexOf(':')
        val rest = if (scheme > 0 && url.getOrNull(scheme + 1) == '/') {
            url.substring(scheme + 1).dropWhile { it == '/' }
        } else {
            url
        }
        return rest.substringBefore('/').substringBefore('?').substringBefore('#')
    }

    /**
     * A second `://` after the URL's own scheme, in the authority
     * or the path. A `://` in the query or the fragment is left
     * alone: explorers put links there.
     */
    private fun embeddedScheme(url: String): Boolean {
        val hash = url.indexOf('#')
        val beforeFragment = if (hash < 0) url else url.substring(0, hash)
        val queryAt = beforeFragment.indexOf('?')
        val beforeQuery = if (queryAt < 0) beforeFragment else beforeFragment.substring(0, queryAt)
        val schemeEnd = beforeQuery.indexOf(':')
        if (schemeEnd <= 0) return false
        return "://" in beforeQuery.substring(schemeEnd + 1)
    }

    /** The characters of the host, before OkHttp rewrites IDN. */
    private fun rawHostHasNonAscii(url: String): Boolean =
        authorityHost(url).any { it.code > 0x7F }

    /**
     * Host after standing [txid] and [address] in for the
     * placeholders. Two calls with different values must agree:
     * `https:{txid}` parses as a different host for each value.
     */
    private fun substitutedHost(template: String, txid: String, address: String): String? =
        substitute(template, txid, address).toHttpUrlOrNull()?.host?.takeIf { it.isNotEmpty() }

    private fun substitute(template: String, txid: String, address: String): String =
        template
            .replace(TXID_PLACEHOLDER, txid, ignoreCase = true)
            .replace(ADDRESS_PLACEHOLDER, address, ignoreCase = true)

    /**
     * True for an IPv4 host that is not four decimal octets in
     * 0..255 without a leading zero. Octal (`0177.0.0.1`), a real
     * hex form (`0x7f.0.0.1`, `0x7f000001`), and the decimal dword
     * (`2130706433`) are refused. A name that merely contains the
     * letters "0x" is not an IPv4 address.
     */
    private fun ambiguousIpv4(host: String): Boolean {
        val name = host.trim().trimEnd('.').lowercase()
        if (name.isEmpty() || ':' in name) return false
        val parts = name.split('.')
        if (parts.any { it.isEmpty() }) return false
        if (!parts.all { isDecimalLabel(it) || isHexLabel(it) }) return false
        if (parts.any { isHexLabel(it) }) return true
        val canonical = parts.size == 4 && parts.all { part ->
            (part == "0" || !part.startsWith('0')) && (part.toIntOrNull() ?: -1) in 0..255
        }
        return !canonical
    }

    private fun isDecimalLabel(part: String): Boolean =
        part.isNotEmpty() && part.all { it.isDigit() }

    private fun isHexLabel(part: String): Boolean =
        part.length > 2 &&
            part.startsWith("0x") &&
            part.drop(2).all { it in '0'..'9' || it in 'a'..'f' }

    /** 64 hex characters. Anything else is not written into `{txid}`. */
    internal fun isHexTxid(id: String): Boolean {
        if (id.length != 64) return false
        return id.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    private fun customRoot(template: String): String {
        var root = template.trim()
        root = root.replace(Regex("(?i)\\{txid\\}"), "")
        root = root.replace(Regex("(?i)\\{address\\}"), "")
        root = root.trimEnd('/')
        root = root.replace(Regex("(?i)/tx$"), "")
        root = root.replace(Regex("(?i)/address$"), "")
        return root.trimEnd('/')
    }
}
