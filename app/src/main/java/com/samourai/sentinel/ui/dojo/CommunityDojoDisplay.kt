package com.samourai.sentinel.ui.dojo

import com.samourai.sentinel.core.DerivedNetwork
import com.samourai.sentinel.core.NetworkClassifier

/**
 * A directory listing's network, from its pairing URL only: a `/test/`
 * mount ([NetworkClassifier.fromUrl]) is testnet, anything else mainnet.
 * The directory's `network` label is ignored, so a directory can't move a
 * Dojo to the other network's list or into the pairing network check.
 */
fun communityDojoNetwork(pairingUrl: String?): DerivedNetwork =
    if (NetworkClassifier.fromUrl(pairingUrl) == DerivedNetwork.TESTNET) DerivedNetwork.TESTNET
    else DerivedNetwork.MAINNET

fun DerivedNetwork.directoryName(): String = if (this == DerivedNetwork.TESTNET) "testnet" else "mainnet"

/**
 * What the Community Dojos sheet shows after a fetch attempt, kept out of the
 * fragment so the rules are unit-tested:
 *  - a successful fetch shows the fresh list (or the empty message when the
 *    current network has no Dojos), and replaces the saved list;
 *  - a successful fetch that lists no Dojos at all does not replace the
 *    saved list: it stays up with a "lists no Dojos" indicator, so one
 *    empty publish can't erase the offline fallback;
 *  - a failed fetch with a saved list for this address shows that list with
 *    the "unreachable" indicator and its date;
 *  - otherwise the failure message, Retry, and Change Dojo Bay address.
 */
sealed class CommunityDojoDisplay {
    data class Fresh(val dojos: List<CommunityDojoNode>) : CommunityDojoDisplay()
    data class Empty(val network: String) : CommunityDojoDisplay()
    data class Cached(
        val dojos: List<CommunityDojoNode>,
        val fetchedAtMs: Long,
        val failure: CommunityDojoRepository.Failure,
    ) : CommunityDojoDisplay()
    /** [attempts]: fetch attempts made before this failure (0 when none was made, e.g. Tor unavailable). */
    data class Failed(val failure: CommunityDojoRepository.Failure, val attempts: Int = 1) : CommunityDojoDisplay()
    /** Dojo Bay answered but lists no Dojos at all: the saved list is kept and shown. */
    data class ListedNone(val dojos: List<CommunityDojoNode>, val fetchedAtMs: Long) : CommunityDojoDisplay()
    /** Dojo Bay answered but lists no Dojos at all, and there is no saved list for this network. */
    object DirectoryEmpty : CommunityDojoDisplay()

    companion object {
        fun networkName(testnet: Boolean): String = if (testnet) "testnet" else "mainnet"

        /** Current network only ([communityDojoNetwork], never the label); online first, then by name. */
        fun forNetwork(nodes: List<CommunityDojoNode>, network: String): List<CommunityDojoNode> =
            nodes
                .filter { communityDojoNetwork(it.payload?.pairing?.url).directoryName().equals(network, ignoreCase = true) }
                .sortedWith(
                    compareByDescending<CommunityDojoNode> { it.isOnline }
                        .thenBy { it.name.orEmpty().lowercase() }
                )

        /** An empty directory never replaces the saved list (on disk or on screen). */
        fun keepsSavedList(nodes: List<CommunityDojoNode>): Boolean = nodes.isEmpty()

        /** [cached] must already be the entry for the current address ([CommunityDojoCache.read]). */
        fun afterSuccess(
            nodes: List<CommunityDojoNode>,
            network: String,
            cached: CommunityDojoCache.Entry? = null,
        ): CommunityDojoDisplay {
            if (keepsSavedList(nodes)) {
                val saved = if (cached != null) forNetwork(cached.nodes, network) else emptyList()
                return if (saved.isNotEmpty()) ListedNone(saved, cached!!.fetchedAtMs) else DirectoryEmpty
            }
            val shown = forNetwork(nodes, network)
            return if (shown.isEmpty()) Empty(network) else Fresh(shown)
        }

        /** [cached] must already be the entry for the current address ([CommunityDojoCache.read]). */
        fun afterFailure(
            failure: CommunityDojoRepository.Failure,
            cached: CommunityDojoCache.Entry?,
            network: String,
            attempts: Int = 1,
        ): CommunityDojoDisplay {
            val shown = if (cached != null) forNetwork(cached.nodes, network) else emptyList()
            return if (shown.isNotEmpty()) Cached(shown, cached!!.fetchedAtMs, failure) else Failed(failure, attempts)
        }
    }
}
