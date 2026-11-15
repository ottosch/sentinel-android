package com.samourai.sentinel.ui.webview

import com.samourai.sentinel.data.txfacts.TxFactsResolver
import java.net.URI

/**
 * Settings rows, info-sheet choice, and "don't show again" for the
 * explorer. Pure: the screen fills the sentences, this decides which
 * row is which and when an advisory comes back.
 *
 * A dismissal remembers how private that load was. A later load that
 * is less private (Tor was on and is now off, or a load that was not
 * going to be sent would now go out in the clear) asks again. A
 * later load that is as private, or more private, stays quiet.
 */
object ExplorerOptions {

    enum class Id { PAIRED, MEMPOOL_ONION, MEMPOOL_SPACE, CUSTOM }

    enum class Icon { ONION_PAIRED, ONION, GLOBE }

    /** How much of the network address this load exposes. */
    enum class Privacy { TOR, UNSENT, DIRECT }

    enum class SpaceInfo { OVER_TOR, TOR_OFF }

    data class Row(
        val id: Id,
        val selectable: Boolean,
        val needsTor: Boolean,
        val icon: Icon,
        val addressLine: String?,
    )

    /**
     * Paired, onion service, clearnet site, Custom. The onion row
     * stays visible when Tor is not ready and cannot be selected.
     * Tor still starting counts as not ready. Whether the clearnet
     * subline says Tor is off is [mempoolSpaceTorOff], not this list.
     */
    fun rows(
        facts: ExplorerBinding.ExplorerFacts,
        torReady: Boolean,
    ): List<Row> {
        val custom = facts.customUrl?.trim().orEmpty()
        return listOf(
            Row(
                id = Id.PAIRED,
                selectable = true,
                needsTor = false,
                icon = Icon.ONION_PAIRED,
                addressLine = pairedAddress(facts),
            ),
            Row(
                id = Id.MEMPOOL_ONION,
                selectable = torReady,
                needsTor = !torReady,
                icon = Icon.ONION,
                addressLine = null,
            ),
            Row(
                id = Id.MEMPOOL_SPACE,
                selectable = true,
                needsTor = false,
                icon = Icon.GLOBE,
                addressLine = null,
            ),
            Row(
                id = Id.CUSTOM,
                selectable = true,
                needsTor = false,
                icon = if (custom.isNotEmpty() && customIsOnion(custom)) {
                    Icon.ONION
                } else {
                    Icon.GLOBE
                },
                addressLine = custom.takeIf { it.isNotEmpty() }
                    ?.let { ExplorerBinding.truncateExplorerLabel(it) },
            ),
        )
    }

    fun pairedAddress(facts: ExplorerBinding.ExplorerFacts): String? {
        val paired = ExplorerBinding.resolve(
            facts.copy(savedSelection = ExplorerBinding.CHOICE_PAIRED),
        )
        val bound = paired as? ExplorerBinding.ExplorerResolution.Bound ?: return null
        if (bound.kind != ExplorerBinding.ExplorerKind.PAIRED) return null
        return ExplorerBinding.truncateExplorerLabel(bound.displayUrl)
    }

    fun idOf(saved: String?, customUrl: String?): Id =
        when (ExplorerBinding.migratedSelection(saved, customUrl)) {
            ExplorerBinding.CHOICE_MEMPOOL_ONION -> Id.MEMPOOL_ONION
            TxFactsResolver.MEMPOOL_DISPLAY_NAME -> Id.MEMPOOL_SPACE
            ExplorerBinding.CHOICE_CUSTOM -> Id.CUSTOM
            else -> Id.PAIRED
        }

    fun persisted(id: Id): String = when (id) {
        Id.PAIRED -> ExplorerBinding.CHOICE_PAIRED
        Id.MEMPOOL_ONION -> ExplorerBinding.CHOICE_MEMPOOL_ONION
        Id.MEMPOOL_SPACE -> TxFactsResolver.MEMPOOL_DISPLAY_NAME
        Id.CUSTOM -> ExplorerBinding.CHOICE_CUSTOM
    }

    /** Preference summary. Paired with a bound onion shows that onion. */
    fun summary(facts: ExplorerBinding.ExplorerFacts): String {
        return when (idOf(facts.savedSelection, facts.customUrl)) {
            Id.PAIRED -> pairedAddress(facts) ?: "Paired explorer"
            Id.MEMPOOL_ONION -> ExplorerBinding.CHOICE_MEMPOOL_ONION
            Id.MEMPOOL_SPACE -> TxFactsResolver.MEMPOOL_DISPLAY_NAME
            Id.CUSTOM -> "Custom"
        }
    }

    fun dojoPaired(facts: ExplorerBinding.ExplorerFacts): Boolean =
        !facts.dojoApiUrl.isNullOrBlank() || !facts.publishedUrl.isNullOrBlank()

    /**
     * The clearnet subline says Tor is off only when the daemon is
     * off and no Dojo is paired. A paired Dojo, or Tor still starting,
     * keeps the over-Tor line.
     */
    fun mempoolSpaceTorOff(facts: ExplorerBinding.ExplorerFacts, torOff: Boolean): Boolean =
        torOff && !dojoPaired(facts)

    fun spaceInfo(facts: ExplorerBinding.ExplorerFacts, torOff: Boolean): SpaceInfo =
        if (mempoolSpaceTorOff(facts, torOff)) SpaceInfo.TOR_OFF else SpaceInfo.OVER_TOR

    /**
     * Null when this pick does not owe a blocking advisory: the paired
     * explorer, and a Custom URL whose host is an onion. The clearnet
     * key is the host, so a different site asks again and a lookup id
     * never lands in the stored key.
     */
    fun advisoryKey(id: Id, url: String?): String? = when (id) {
        Id.PAIRED -> null
        Id.MEMPOOL_ONION -> ExplorerBinding.CHOICE_MEMPOOL_ONION
        Id.MEMPOOL_SPACE -> TxFactsResolver.MEMPOOL_DISPLAY_NAME
        Id.CUSTOM -> {
            val trimmed = url?.trim().orEmpty()
            if (trimmed.isEmpty() || customIsOnion(trimmed)) {
                null
            } else {
                "custom:" + (clearnetHost(trimmed) ?: "clearnet")
            }
        }
    }

    fun privacyOf(decision: ExplorerLoadDecision): Privacy = when (ExplorerAdvisory.transport(decision)) {
        ExplorerAdvisory.Transport.TOR -> Privacy.TOR
        ExplorerAdvisory.Transport.DIRECT -> Privacy.DIRECT
        ExplorerAdvisory.Transport.UNSENT -> Privacy.UNSENT
    }

    /**
     * Higher is more private. Tor hides the address. A load that has
     * not started has not exposed it. A direct load has.
     */
    fun privacyRank(privacy: Privacy): Int = when (privacy) {
        Privacy.TOR -> 2
        Privacy.UNSENT -> 1
        Privacy.DIRECT -> 0
    }

    /** True when the user has not dismissed this, or the route got worse. */
    fun shouldShowAdvisory(accepted: Privacy?, current: Privacy): Boolean {
        if (accepted == null) return true
        return privacyRank(current) < privacyRank(accepted)
    }

    fun encodeDismissals(map: Map<String, Privacy>): String =
        map.entries
            .sortedBy { it.key }
            .joinToString("\n") { (key, privacy) ->
                key.replace("\n", "").replace("\t", "") + "\t" + privacy.name
            }

    fun decodeDismissals(raw: String?): Map<String, Privacy> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = linkedMapOf<String, Privacy>()
        raw.lineSequence().forEach { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@forEach
            val key = line.substring(0, tab).trim()
            val privacy = runCatching { Privacy.valueOf(line.substring(tab + 1).trim()) }.getOrNull()
                ?: return@forEach
            if (key.isNotEmpty()) out[key] = privacy
        }
        return out
    }

    /**
     * True when Settings must not save this Custom template.
     * Chromium treats `\` as `/` in an http(s) URL, and the host is
     * only what follows the last `@` in the authority, so either one
     * can open a different site than the address looks like. An `@`
     * in the query or the fragment is not userinfo. A blank value is
     * a separate "nothing entered" error.
     */
    fun customTemplateRejected(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        if ('\\' in trimmed) return true
        val authority = trimmed
            .substringAfter("://", trimmed)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        return '@' in authority
    }

    /**
     * A saved Custom template often contains `{txid}` or `{address}`,
     * which [java.net.URI] will not parse. Those placeholders are not
     * part of the host, so they are stood in for before the host check.
     * The substituted page URL is what the WebView loads.
     */
    private fun customIsOnion(url: String): Boolean =
        TxFactsResolver.isOnionHost(parsable(url))

    private fun clearnetHost(url: String): String? =
        try {
            URI(parsable(url)).host?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }

    internal fun parsable(url: String): String =
        url.trim()
            .replace(ExplorerBinding.TXID_PLACEHOLDER, "x", ignoreCase = true)
            .replace(ExplorerBinding.ADDRESS_PLACEHOLDER, "x", ignoreCase = true)
}
