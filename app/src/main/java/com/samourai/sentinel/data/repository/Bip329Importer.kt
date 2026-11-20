package com.samourai.sentinel.data.repository

import androidx.room.withTransaction
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.db.SentinelRoomDb
import com.samourai.sentinel.data.db.dao.LabelEntryDao
import com.samourai.sentinel.data.db.dao.UtxoLabelDao
import com.samourai.sentinel.data.db.entity.LabelEntry
import com.samourai.sentinel.data.db.entity.LabelType
import com.samourai.sentinel.data.db.entity.UtxoLabel
import com.samourai.sentinel.data.whirlpool.CoinLabel
import com.samourai.sentinel.data.whirlpool.WhirlpoolConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import java.io.BufferedReader

data class ImportResult(
    val imported: Int,
    val updated: Int,
    val skipped: Int,
    val noLabel: Int
)

/**
 * Imports BIP-329 JSONL label data. Line parsing lives in [Bip329Parser];
 * this class owns database merge semantics.
 *
 *  - Lines the parser rejects (malformed JSON, missing/unknown type,
 *    bad outpoint, cross-network ref): counted as skipped, with the
 *    offending line number logged (visible via adb logcat on staging).
 *  - ABSENT label field (Sparrow metadata lines) and blank lines:
 *    counted as noLabel, never written, never deletes anything.
 *    Per BIP-329, an omitted label means "do not alter".
 *  - EMPTY label on a tx or output: stores a blank label with a null
 *    origin. That is a manual clear, so auto-labelling does not write
 *    the label back. createdAt is preserved when a row already exists.
 *  - EMPTY label on any other type, including address: deletes an
 *    existing record for the ref, if any.
 *  - createdAt is preserved when a record already exists.
 *  - The whole batch applies inside one transaction: all-or-nothing.
 */
class Bip329Importer(private val dbOverride: SentinelRoomDb? = null) {

    private val koinDb: SentinelRoomDb by inject(SentinelRoomDb::class.java)
    private val db: SentinelRoomDb get() = dbOverride ?: koinDb
    private val utxoLabelDao: UtxoLabelDao get() = db.utxoLabelDao()
    private val labelEntryDao: LabelEntryDao get() = db.labelEntryDao()

    fun currentNetwork(): String =
        if (SentinelState.isTestNet()) "testnet" else "mainnet"

    suspend fun import(reader: BufferedReader): ImportResult =
        import(reader, currentNetwork(), System.currentTimeMillis())

    /**
     * Test seam: explicit network + clock. Pure JVM, no SentinelState /
     * wall-clock reads; identical merge semantics.
     */
    internal suspend fun import(
        reader: BufferedReader,
        network: String,
        now: Long
    ): ImportResult =
        withContext(Dispatchers.IO) {
            var imported = 0
            var updated = 0
            var skipped = 0
            var noLabel = 0

            val outputs = mutableListOf<UtxoLabel>()
            val others = mutableListOf<LabelEntry>()

            reader.lineSequence().forEachIndexed { index, raw ->
                val lineNo = index + 1
                when (val parsed = Bip329Parser.parse(raw, network, now)) {
                    null -> {
                        Timber.w("BIP329 line %d rejected", lineNo)
                        skipped++
                    }
                    Bip329Parser.Parsed.Ignored -> noLabel++
                    is Bip329Parser.Parsed.Output -> outputs += parsed.label
                    is Bip329Parser.Parsed.Other -> others += parsed.entry
                }
            }

            db.withTransaction {
                outputs.forEach { row ->
                    val existing = utxoLabelDao.find(row.network, row.txid, row.vout)
                    val badbank = existing?.badbank ?: 0
                    val keepAutoBadBank = badbank == 1 &&
                        row.origin == WhirlpoolConfig.DEFAULT.origin &&
                        row.label.trim() == WhirlpoolConfig.DEFAULT.labels.badbank
                    if (keepAutoBadBank) {
                        // The auto row exports as "Bad Bank" with its
                        // origin. Stripping that to an empty user part
                        // would drop the origin on the next export.
                        if (existing == null) imported++ else updated++
                        utxoLabelDao.upsert(
                            row.copy(
                                label = WhirlpoolConfig.DEFAULT.labels.badbank,
                                createdAt = existing?.createdAt ?: row.createdAt,
                                badbank = badbank,
                            )
                        )
                    } else {
                        val label = if (badbank == 1) CoinLabel.userPart(row.label) else row.label
                        if (label.isBlank()) {
                            // A blank output import is a manual clear. Deleting
                            // the row would let the next auto sync label it again.
                            // The Bad Bank flag, once set, stays.
                            if (existing == null) imported++ else updated++
                            utxoLabelDao.upsert(
                                row.copy(
                                    label = "",
                                    origin = null,
                                    createdAt = existing?.createdAt ?: row.createdAt,
                                    badbank = badbank,
                                )
                            )
                        } else {
                            if (existing == null) imported++ else updated++
                            utxoLabelDao.upsert(
                                row.copy(
                                    label = label,
                                    createdAt = existing?.createdAt ?: row.createdAt,
                                    badbank = badbank,
                                )
                            )
                        }
                    }
                }
                others.forEach { row ->
                    val existing = labelEntryDao.find(row.network, row.type, row.ref)
                    if (row.label.isBlank()) {
                        if (row.type == LabelType.TX.wire) {
                            if (existing == null) imported++ else updated++
                            labelEntryDao.upsert(
                                row.copy(
                                    label = "",
                                    origin = null,
                                    createdAt = existing?.createdAt ?: row.createdAt,
                                )
                            )
                        } else if (existing != null) {
                            labelEntryDao.delete(row.network, row.type, row.ref)
                            updated++
                        }
                    } else {
                        if (existing == null) imported++ else updated++
                        labelEntryDao.upsert(
                            row.copy(createdAt = existing?.createdAt ?: row.createdAt)
                        )
                    }
                }
            }

            ImportResult(imported, updated, skipped, noLabel)
        }
}
