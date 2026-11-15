package com.samourai.sentinel.ui.collectionDetails.send

import com.samourai.sentinel.data.Utxo
import com.samourai.sentinel.data.repository.PubSyncHealth

/**
 * Coins of an XPUB whose last sync failed stay out of send selection
 * and out of PSBT compose until that XPUB syncs successfully.
 */
internal fun utxosSelectableForSend(utxos: List<Utxo>): List<Utxo> =
    utxos.filter { utxo -> !PubSyncHealth.isHidden(utxo.pubKey) }

internal fun hiddenXpubCount(utxos: List<Utxo>): Int =
    utxos.map { it.pubKey }.filter { PubSyncHealth.isHidden(it) }.distinct().size

internal fun unsyncedXpubNote(hiddenPubs: Int): String? = when {
    hiddenPubs <= 0 -> null
    hiddenPubs == 1 -> "1 XPUB didn't sync; its coins are hidden until it does"
    else -> "$hiddenPubs XPUBs didn't sync; their coins are hidden until they do"
}
