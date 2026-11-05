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
 *
 * Stage 2 moved the derivation itself into
 * NetworkClassifier.fromCollection (core, beside aggregate) so
 * util's total filter consumes the same verdict without reaching
 * into ui.adapters. This file keeps only the render-state table.
 */
sealed class CollectionRowNetworkState {
    object Normal : CollectionRowNetworkState()
    object GreyedPaired : CollectionRowNetworkState()
    object GreyedUnpaired : CollectionRowNetworkState()
}

fun collectionRowNetworkState(
    collection: PubKeyCollection,
    activeIsTestnet: Boolean,
    dojoEnabled: Boolean,
): CollectionRowNetworkState {
    val verdict = NetworkClassifier.fromCollection(collection)
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
