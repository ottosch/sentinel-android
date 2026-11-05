package com.samourai.sentinel.ui.adapters

import com.samourai.sentinel.core.DerivedNetwork
import com.samourai.sentinel.core.NetworkClassifier
import com.samourai.sentinel.data.PubKeyCollection

/**
 * #429: render state for a collection row measured against the
 * active network. The PairingNetworkDecision precedent (#208/#250):
 * the decision lives in its own file, pure and unit-testable
 * without Android; the adapter owns all rendering and string
 * resolution.
 *
 * Table (#429 decisions, frozen on the issue):
 *   no verdict (empty / mixed / all-unknown) -> Normal, never guess
 *   verdict matches the active network       -> Normal
 *   verdict differs, Dojo paired             -> GreyedPaired
 *   verdict differs, no Dojo paired          -> GreyedUnpaired
 *
 * Grey is informative, not a gate: rows stay clickable for
 * view-only access. The paired/unpaired split only selects the
 * badge template (decision 2); both grey identically.
 */
sealed class CollectionRowNetworkState {
    object Normal : CollectionRowNetworkState()
    object GreyedPaired : CollectionRowNetworkState()
    object GreyedUnpaired : CollectionRowNetworkState()
}

/**
 * #429: a single collection's network verdict - aggregate over
 * its pubs' xpub prefixes. Mixed or all-abstaining input yields
 * null, NetworkClassifier's null-on-mixed discipline: a legacy
 * mixed collection must not be silently re-flagged.
 *
 * Intended to replace the sheet-local isPublicKeyTesnet twin in
 * AddNewPubKeyBottomSheet when that file is next touched.
 */
fun collectionNetwork(collection: PubKeyCollection): DerivedNetwork? =
    NetworkClassifier.aggregate(
        collection.pubs.map { NetworkClassifier.fromXpub(it.pubKey) }
    )

fun collectionRowNetworkState(
    collection: PubKeyCollection,
    activeIsTestnet: Boolean,
    dojoEnabled: Boolean,
): CollectionRowNetworkState {
    val verdict = collectionNetwork(collection)
        ?: return CollectionRowNetworkState.Normal
    val collectionIsTestnet = verdict == DerivedNetwork.TESTNET
    if (collectionIsTestnet == activeIsTestnet) {
        return CollectionRowNetworkState.Normal
    }
    return if (dojoEnabled) {
        CollectionRowNetworkState.GreyedPaired
    } else {
        CollectionRowNetworkState.GreyedUnpaired
    }
}
