package com.samourai.sentinel.ui.dojo

import com.samourai.sentinel.core.DerivedNetwork
import com.samourai.sentinel.core.NetworkClassifier
import com.samourai.sentinel.helpers.fromJSON

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

/** A listing with the result of checking its owner's signature. */
data class CheckedDojo(
    val node: CommunityDojoNode,
    val check: DojoBaySignatures.NodeCheck,
) {
    val verified: DojoBaySignatures.NodeCheck.Verified?
        get() = check as? DojoBaySignatures.NodeCheck.Verified

    /**
     * The listing's network ([communityDojoNetwork]): from the URL only,
     * taken from inside the signature for a verified listing. Dojo Bay signs
     * only `{pairing, explorer}`, so the directory's `network` label is
     * unsigned and ignored.
     */
    val network: DerivedNetwork
        get() {
            val url = verified?.let { fromJSON<DojoPairing>(it.signedPayloadJson)?.pairing?.url }
                ?: node.payload?.pairing?.url
            return communityDojoNetwork(url)
        }
}

/**
 * What the Community Dojos sheet shows after a fetch attempt, kept out of the
 * fragment so the rules are unit-tested:
 *  - a fetch from a directory whose operator binding fails, or whose operator
 *    is not the pinned one, is refused: nothing from it is shown;
 *  - a custom directory is checked against the operator code the user
 *    entered with its address; there is no trust on first use;
 *  - a trusted fetch shows the fresh list (or the empty message when the
 *    current network has no Dojos), and replaces the saved list;
 *  - a trusted fetch that lists no Dojos at all does not replace the saved
 *    list: a saved list from the same operator stays up with a "lists no
 *    Dojos" indicator, so one empty publish can't erase the offline
 *    fallback;
 *  - a failed fetch with a saved list, from the operator still trusted for
 *    this address, shows that list with the "unreachable" indicator and its
 *    date; after an operator.json 404 the indicator says instead that Dojo
 *    Bay has no operator statement ([SavedListBanner]), with no age limit;
 *  - otherwise the failure message, Retry, and Change Dojo Bay address.
 * Every listing carries its own signature check; only verified ones pair.
 */
sealed class CommunityDojoDisplay {
    data class Fresh(val dojos: List<CheckedDojo>, val operatorCode: String) : CommunityDojoDisplay()
    data class Empty(val network: String) : CommunityDojoDisplay()
    /**
     * A failed fetch with the saved list shown, saved at [fetchedAtMs].
     * There is no age limit: the list was verified under the operator code
     * still trusted for this address, and every listing is checked again
     * each time it is shown. [banner] says which banner goes over it.
     */
    data class Cached(
        val dojos: List<CheckedDojo>,
        val fetchedAtMs: Long,
        val failure: CommunityDojoRepository.Failure,
    ) : CommunityDojoDisplay() {
        val banner: SavedListBanner
            get() = if (failure == CommunityDojoRepository.Failure.NO_OPERATOR_STATEMENT) {
                SavedListBanner.NO_OPERATOR_STATEMENT
            } else {
                SavedListBanner.UNREACHABLE
            }
    }

    /** The banner over a saved list after a failed fetch. */
    enum class SavedListBanner {
        /** Dojo Bay could not be reached, or did not answer usefully. */
        UNREACHABLE,
        /**
         * Dojo Bay answered, but data/operator.json is 404: the instance has
         * no operator statement, so the saved list can't be re-checked
         * against it. The list itself was not parsed.
         */
        NO_OPERATOR_STATEMENT,
    }
    /** [attempts]: fetch attempts made before this failure (0 when none was made, e.g. Tor unavailable). */
    data class Failed(val failure: CommunityDojoRepository.Failure, val attempts: Int = 1) : CommunityDojoDisplay()
    /** Dojo Bay answered but lists no Dojos at all: the saved list is kept and shown. */
    data class ListedNone(val dojos: List<CheckedDojo>, val fetchedAtMs: Long) : CommunityDojoDisplay()
    /** Dojo Bay answered but lists no Dojos at all, and there is no saved list for this network. */
    object DirectoryEmpty : CommunityDojoDisplay()
    data class Refused(val reason: DojoBayTrust.RefusalReason, val operatorCode: String?) : CommunityDojoDisplay()

    companion object {
        /**
         * The date pattern skeleton for the no-operator-statement banner's
         * "Saved list from <date>": day and month ("12 Oct"), plus the year
         * when the list was saved in another year (there is no age limit).
         */
        fun savedDateSkeleton(savedAtMs: Long, nowMs: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
            fun year(ms: Long) = java.util.Calendar.getInstance(zone).apply { timeInMillis = ms }.get(java.util.Calendar.YEAR)
            return if (year(savedAtMs) == year(nowMs)) "dMMM" else "dMMMyyyy"
        }

        fun networkName(testnet: Boolean): String = if (testnet) "testnet" else "mainnet"

        /**
         * Current network only ([CheckedDojo.network]: from the signed URL,
         * never the directory's label); verified first, then
         * online, then by name. Each listing checked.
         */
        fun forNetwork(nodes: List<CommunityDojoNode>, network: String): List<CheckedDojo> {
            val wanted = normalizeDeclaredNetwork(network)
            return nodes
                .map { CheckedDojo(it, DojoBaySignatures.checkNode(it)) }
                .filter { wanted != null && it.network == wanted }
                .sortedWith(
                    compareByDescending<CheckedDojo> { it.verified != null }
                        .thenByDescending { it.node.isOnline }
                        .thenBy { it.node.name.orEmpty().lowercase() }
                )
        }

        /** An empty directory never replaces the saved list (on disk or on screen). */
        fun keepsSavedList(nodes: List<CommunityDojoNode>): Boolean = nodes.isEmpty()

        /**
         * [cached] must already be the entry for the current address
         * ([CommunityDojoCache.read]); it is shown only when an empty trusted
         * directory comes from the operator it was verified under.
         */
        fun afterSuccess(
            directory: CommunityDojoRepository.Directory,
            decision: DojoBayTrust.Decision,
            network: String,
            cached: CommunityDojoCache.Entry? = null,
        ): CommunityDojoDisplay = when (decision) {
            is DojoBayTrust.Decision.Refused -> Refused(decision.reason, decision.operatorCode)
            is DojoBayTrust.Decision.Trusted -> if (keepsSavedList(directory.nodes)) {
                val saved = if (cached != null && cached.operatorCode == decision.operatorCode) {
                    forNetwork(cached.nodes, network)
                } else {
                    emptyList()
                }
                if (saved.isNotEmpty()) ListedNone(saved, cached!!.fetchedAtMs) else DirectoryEmpty
            } else {
                val shown = forNetwork(directory.nodes, network)
                if (shown.isEmpty()) Empty(network) else Fresh(shown, decision.operatorCode)
            }
        }

        fun afterFailure(
            failure: CommunityDojoRepository.Failure,
            cached: CommunityDojoCache.Entry?,
            baseUrl: String,
            pinnedCode: String?,
            network: String,
            attempts: Int = 1,
        ): CommunityDojoDisplay {
            val trusted = cached != null &&
                DojoBayTrust.cachedListTrusted(baseUrl, cached.operatorCode, pinnedCode)
            val shown = if (trusted) forNetwork(cached!!.nodes, network) else emptyList()
            return if (shown.isNotEmpty()) Cached(shown, cached!!.fetchedAtMs, failure) else Failed(failure, attempts)
        }
    }
}
