package com.samourai.sentinel.data.repository

import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.db.dao.LabelEntryDao
import com.samourai.sentinel.data.db.dao.UtxoLabelDao
import com.samourai.sentinel.data.db.entity.UtxoLabel
import com.samourai.sentinel.data.db.entity.LabelEntry
import com.samourai.sentinel.data.whirlpool.CoinLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.koin.java.KoinJavaComponent.inject
import java.io.PrintWriter

/**
 * Exports labels as BIP-329 JSONL.
 *
 *  - "output" records from utxo_labels (sorted by txid, vout)
 *  - all other types from label_entries (sorted by type, ref)
 *
 * Sorting makes exports deterministic: re-exporting an unchanged
 * wallet produces byte-identical files, so diffs between exports
 * show only real label changes. "origin" is emitted only when
 * present, exactly as imported (opaque pass-through).
 *
 * The Whirlpool auto-labels setting is display-only and is not
 * consulted here. Automatic transaction rows are exported with
 * their origin either way, so a backup round-trips back into an
 * `auto:` label. A wallet that ignores origin will show the stored
 * text ("Whirlpool Tx0", "Whirlpool Mix") as an ordinary label.
 *
 * A coin with the Bad Bank flag exports the composed label
 * ("Bad Bank" or "<user> (Bad Bank)"). Origin is unchanged. Import
 * strips the suffix back off a flagged row, so a second export
 * matches the first.
 */
class Bip329Exporter(
    private val utxoLabelDaoOverride: UtxoLabelDao? = null,
    private val labelEntryDaoOverride: LabelEntryDao? = null
) {

    private val koinUtxoLabelDao: UtxoLabelDao by inject(UtxoLabelDao::class.java)
    private val koinLabelEntryDao: LabelEntryDao by inject(LabelEntryDao::class.java)
    private val utxoLabelDao: UtxoLabelDao get() = utxoLabelDaoOverride ?: koinUtxoLabelDao
    private val labelEntryDao: LabelEntryDao get() = labelEntryDaoOverride ?: koinLabelEntryDao

    fun currentNetwork(): String =
        if (SentinelState.isTestNet()) "testnet" else "mainnet"

    suspend fun export(writer: PrintWriter) {
        export(writer, currentNetwork())
    }

    /** Test seam: explicit network. */
    internal suspend fun export(writer: PrintWriter, network: String) {
        withContext(Dispatchers.IO) {
            writeOutputs(writer, utxoLabelDao.getAll(network))
            writeOthers(writer, labelEntryDao.getAll(network))
            writer.flush()
        }
    }

    private fun writeOutputs(writer: PrintWriter, rows: List<UtxoLabel>) {
        rows.sortedWith(compareBy({ it.txid }, { it.vout }))
            .forEach { row ->
                // A flagged coin with no user part exports as "Bad Bank",
                // including a hand clear that kept the flag. An unflagged
                // blank stays on device and is omitted.
                val label = if (row.badbank == 1) {
                    CoinLabel.display(row.label, true).orEmpty()
                } else {
                    row.label
                }
                if (label.isBlank()) return@forEach
                val o = JSONObject()
                    .put("type", "output")
                    .put("ref", "${row.txid}:${row.vout}")
                    .put("label", label)
                row.origin?.let { o.put("origin", it) }
                writer.println(o.toString())
            }
    }

    private fun writeOthers(writer: PrintWriter, rows: List<LabelEntry>) {
        rows.sortedWith(compareBy({ it.type }, { it.ref }))
            .forEach { row ->
                if (row.label.isBlank()) return@forEach
                val o = JSONObject()
                    .put("type", row.type)
                    .put("ref", row.ref)
                    .put("label", row.label)
                row.origin?.let { o.put("origin", it) }
                writer.println(o.toString())
            }
    }
}
