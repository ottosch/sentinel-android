package com.samourai.sentinel.ui.collectionDetails.send

import com.samourai.sentinel.data.Utxo

/**
 * #190: manual coin control - the pure rules for a pinned input set.
 *
 * The user pins outpoints in [CoinPickerBottomSheet]; [SendViewModel] holds
 * the pinned keys and, before every compose, asks [resolve] what the pin
 * means against the current candidate pool (the selected account's UTXOs,
 * as the utxoDao LiveData last delivered them).
 *
 * Contract:
 *  - No pin ([pinned] empty) is [Resolution.Auto]: compose keeps its
 *    automatic selection, exactly as before #190.
 *  - A pinned coin marked do-not-spend is never handed to compose. That is
 *    the hard strip SendViewModel already applied to the auto pool, kept
 *    for explicit selections too (Ashigaru SendActivity re-validates its
 *    preselected set the same way). Here it fails the whole selection
 *    loudly instead of composing a smaller set the user did not choose.
 *  - A pinned coin missing from the pool (spent, or dropped by a sync since
 *    it was pinned) also fails the selection. Composing the remainder would
 *    put a different input set in the PSBT than the one the user reviewed.
 *  - Otherwise [Resolution.Pinned] carries exactly the pinned coins, in pool
 *    order, with their total.
 *
 * Pure JVM, no Android imports: [isBlocked] is UtxoMetaUtil::has in
 * production and a plain predicate in tests.
 */
object CoinSelection {

    sealed class Resolution {
        /** Nothing pinned - automatic selection. */
        object Auto : Resolution()

        /** Spend exactly [utxos]; [totalSats] is their summed value. */
        data class Pinned(val utxos: List<Utxo>, val totalSats: Long) : Resolution()

        /** Pinned coins that are now marked do-not-spend. */
        data class Blocked(val keys: Set<String>) : Resolution()

        /** Pinned coins that are no longer in the pool. */
        data class Missing(val keys: Set<String>) : Resolution()
    }

    /** The outpoint key a pin is stored under: "txid:vout". */
    fun keyOf(utxo: Utxo): String = "${utxo.txHash}:${utxo.txOutputN}"

    fun resolve(
        pinned: Set<String>,
        pool: List<Utxo>,
        isBlocked: (Utxo) -> Boolean
    ): Resolution {
        if (pinned.isEmpty()) return Resolution.Auto

        val byKey = pool.associateBy { keyOf(it) }
        val missing = pinned.filterNot { byKey.containsKey(it) }.toSet()
        if (missing.isNotEmpty()) return Resolution.Missing(missing)

        val blocked = pinned.filter { isBlocked(byKey.getValue(it)) }.toSet()
        if (blocked.isNotEmpty()) return Resolution.Blocked(blocked)

        val chosen = pool.filter { pinned.contains(keyOf(it)) }
        return Resolution.Pinned(chosen, chosen.sumOf { it.value ?: 0L })
    }

    /**
     * #190 co-spend warning: the pinned set mixes coins carrying the
     * bad-bank label (#46/#54 lineage) with coins that do not. Spending
     * them together links the badbank change back to the rest of the
     * wallet - the pre-flight warning the issue asks for at compose time.
     *
     * [labels] is LabelRepository.observeLabelMap(): "txid:vout" -> label.
     * Keys are compared lowercased, the invariant the #46 writer stores by.
     */
    fun mixesBadBank(selected: List<Utxo>, labels: Map<String, String>): Boolean {
        if (selected.size < 2) return false
        val flags = selected.map { isBadBank(it, labels) }
        return flags.any { it } && flags.any { !it }
    }

    fun isBadBank(utxo: Utxo, labels: Map<String, String>): Boolean =
        labelFor(utxo, labels)?.trim()?.equals(BAD_BANK_LABEL, ignoreCase = true) == true

    /** The utxo label for [utxo], tolerant of txid case. */
    fun labelFor(utxo: Utxo, labels: Map<String, String>): String? =
        labels[keyOf(utxo)]
            ?: labels["${utxo.txHash?.lowercase()}:${utxo.txOutputN}"]

    /** WhirlpoolConfig's badbank label, as the #46 auto-writer stores it. */
    const val BAD_BANK_LABEL = "Bad Bank"
}
