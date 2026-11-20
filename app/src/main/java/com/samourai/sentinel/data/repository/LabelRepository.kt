package com.samourai.sentinel.data.repository

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.db.dao.LabelEntryDao
import com.samourai.sentinel.data.db.SentinelRoomDb
import androidx.room.withTransaction
import com.samourai.sentinel.data.db.dao.UtxoLabelDao
import com.samourai.sentinel.data.db.entity.LabelEntry
import com.samourai.sentinel.data.db.entity.LabelType
import com.samourai.sentinel.data.db.entity.UtxoLabel
import com.samourai.sentinel.data.whirlpool.CoinLabel
import com.samourai.sentinel.data.whirlpool.ManualLabelWrite
import com.samourai.sentinel.data.whirlpool.WhirlpoolConfig
import com.samourai.sentinel.data.whirlpool.manualLabelWrite
import com.samourai.sentinel.ui.utils.PrefsUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.inject

/**
 * One stored tx label and its provenance. [origin] is null for a label
 * the user typed, `auto:…` for a detector write, and a foreign string
 * for a BIP-329 import. Display code may read it; nothing here writes.
 */
data class TxLabelDetail(val label: String, val origin: String?)

/**
 * One stored coin label and its Bad Bank flag. [badbank] is 1 when
 * the marking sticks. [label] is the user part, not the composed
 * display string.
 */
data class UtxoLabelView(val label: String, val badbank: Int)

/**
 * User-defined labels (BIP329).
 *
 * UTXO labels ("output" records) live in utxo_labels; tx/addr/pubkey/xpub
 * labels live in label_entries. All access is scoped to the active network
 * ("mainnet"/"testnet") so labels can never leak across networks. Labels
 * are stored independently of the sync-managed utxos table and therefore
 * survive backend refreshes.
 *
 * Storage invariants (enforced here so UI callers can be naive):
 *  - tx refs are stored lowercased; observeTxLabel/setTxLabel normalize.
 *  - addr refs are stored verbatim; observeAddrLabel passes input through.
 *  - a blank address label deletes that record.
 *  - a blank tx or utxo label is kept, with a manual origin, so
 *    auto-labelling does not write it back on the next sync.
 */
class LabelRepository {

    private val dao: UtxoLabelDao by inject(UtxoLabelDao::class.java)
    private val entryDao: LabelEntryDao by inject(LabelEntryDao::class.java)
    private val roomDb: SentinelRoomDb by inject(SentinelRoomDb::class.java)
    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java)
    private val autoOriginMigrationMutex = Mutex()
    @Volatile
    private var autoOriginMigrationDone = false

    fun currentNetwork(): String =
        if (SentinelState.isTestNet()) "testnet" else "mainnet"

    //
    // UTXO labels (utxo_labels)
    //

    fun observe(txid: String, vout: Int): LiveData<UtxoLabel?> =
        dao.observe(currentNetwork(), txid, vout)

    /**
     * Live map of "txid:vout" -> label for the active network.
     *
     * MediatorLiveData instead of Transformations.map: the project's resolved
     * lifecycle version does not expose Transformations to Kotlin compilation,
     * while MediatorLiveData is already used elsewhere in the codebase.
     */
    fun observeLabelMap(): LiveData<Map<String, String>> {
        val result = MediatorLiveData<Map<String, String>>()
        result.addSource(dao.observeAll(currentNetwork())) { labels ->
            result.value = labels.associate { "${it.txid}:${it.vout}" to it.label }
        }
        return result
    }

    /**
     * Live map of "txid:vout" -> stored label plus the sticky Bad Bank
     * flag. Render sites compose the suffix from this; they do not
     * read the flag off the label text.
     */
    fun observeUtxoLabels(): LiveData<Map<String, UtxoLabelView>> {
        val result = MediatorLiveData<Map<String, UtxoLabelView>>()
        result.addSource(dao.observeAll(currentNetwork())) { labels ->
            result.value = labels.associate {
                "${it.txid}:${it.vout}" to UtxoLabelView(it.label, it.badbank)
            }
        }
        return result
    }

    suspend fun getAll(): List<UtxoLabel> =
        withContext(Dispatchers.IO) { dao.getAll(currentNetwork()) }

    /**
     * Sets, updates, or clears a utxo label by hand. The auto origin
     * is replaced with the manual origin. A blank label is kept, so
     * the next sync does not treat the outpoint as unlabelled.
     *
     * The Bad Bank flag is sticky. [markBadbank] sets it when the coin
     * is in the Bad Bank account and no row exists yet. A row that
     * already has the flag keeps it. The "(Bad Bank)" suffix is stored
     * only for a coin that is not Bad Bank; a Bad Bank coin stores
     * [CoinLabel.userPart].
     */
    suspend fun setLabel(
        txid: String,
        vout: Int,
        label: String,
        markBadbank: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        migrateHandEditedAutoOrigins()
        val network = currentNetwork()
        val existing = dao.find(network, txid, vout)
        val marked = existing?.badbank == 1 || markBadbank
        val write = manualLabelWrite(label)
        val stored = if (marked) CoinLabel.userPart(write.label) else write.label
        val now = System.currentTimeMillis()
        dao.upsert(
            UtxoLabel(
                network = network,
                txid = txid,
                vout = vout,
                label = stored,
                origin = write.origin,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                badbank = if (marked) 1 else 0,
            )
        )
    }

    //
    // Transaction labels (label_entries, type "tx")
    //

    /**
     * Live label for a single transaction. Input is lowercased to match
     * the storage invariant from BIP329 import normalization.
     */
    fun observeTxLabel(txid: String): LiveData<LabelEntry?> =
        entryDao.observe(currentNetwork(), LabelType.TX.wire, txid.lowercase())

    /**
     * Live map of txid -> stored label plus origin for all tx labels on
     * the active network. Intended for list adapters: observe once, look
     * up per row. Origin is how a row tells an auto-written label from
     * one the user typed (display only — BIP-329 export reads the DAO,
     * not this map).
     */
    fun observeTxLabelMap(): LiveData<Map<String, TxLabelDetail>> {
        val result = MediatorLiveData<Map<String, TxLabelDetail>>()
        result.addSource(
            entryDao.observeAllForType(currentNetwork(), LabelType.TX.wire)
        ) { entries ->
            result.value = entries.associate {
                it.ref to TxLabelDetail(it.label, it.origin)
            }
        }
        return result
    }

    /**
     * Sets, updates, or clears a transaction label by hand. Input txid
     * is lowercased to match storage. The auto origin is replaced with
     * the manual origin. A blank label is kept, so the next sync does
     * not treat the tx as unlabelled. createdAt is preserved.
     */
    suspend fun setTxLabel(txid: String, label: String) =
        writeManualEntry(LabelType.TX, txid.lowercase(), manualLabelWrite(label))

    /**
     * Existing tx label row, or null when none exists. Txid lowercased
     * to match storage. Distinguishes "no row" from "row with a null
     * origin" — the auto-writer's provenance guard depends on that
     * difference (issue #6 part 2).
     */
    suspend fun getTxLabel(txid: String): LabelEntry? =
        withContext(Dispatchers.IO) {
            migrateHandEditedAutoOrigins()
            entryDao.find(currentNetwork(), LabelType.TX.wire, txid.lowercase())
        }

    /**
     * Writes a detector-originated tx label. Unlike setTxLabel this
     * sets the provided origin explicitly instead of preserving an
     * existing one, and never deletes (auto labels are never blank).
     * The manual-provenance guard lives in WhirlpoolAutoWriter, not
     * here — callers must have passed it before calling this.
     */
    suspend fun setAutoTxLabel(txid: String, label: String, origin: String) =
        withContext(Dispatchers.IO) {
            migrateHandEditedAutoOrigins()
            val network = currentNetwork()
            val ref = txid.lowercase()
            val trimmed = label.trim()
            check(trimmed.isNotEmpty()) { "auto label must not be blank" }
            val now = System.currentTimeMillis()
            val existing = entryDao.find(network, LabelType.TX.wire, ref)
            entryDao.upsert(
                LabelEntry(
                    network = network,
                    type = LabelType.TX.wire,
                    ref = ref,
                    label = trimmed,
                    origin = origin,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now
                )
            )
        }

    //
    // Address labels (label_entries, type "addr")
    //

    /**
     * Live label for a single address. Refs are stored verbatim (no
     * case normalization applies to addr records), input passes through.
     */
    fun observeAddrLabel(address: String): LiveData<LabelEntry?> =
        entryDao.observe(currentNetwork(), LabelType.ADDR.wire, address)

    /**
     * Sets, updates or removes (blank) an address label. Ref stored
     * verbatim, matching importer normalization rules.
     */
    suspend fun setAddrLabel(address: String, label: String) =
        setEntryLabel(LabelType.ADDR, address, label)

    private suspend fun writeManualEntry(
        type: LabelType,
        ref: String,
        write: ManualLabelWrite,
    ) = withContext(Dispatchers.IO) {
        migrateHandEditedAutoOrigins()
        val network = currentNetwork()
        val now = System.currentTimeMillis()
        val existing = entryDao.find(network, type.wire, ref)
        entryDao.upsert(
            LabelEntry(
                network = network,
                type = type.wire,
                ref = ref,
                label = write.label,
                origin = write.origin,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now
            )
        )
    }

    private suspend fun setEntryLabel(type: LabelType, ref: String, label: String) =
        withContext(Dispatchers.IO) {
            migrateHandEditedAutoOrigins()
            val network = currentNetwork()
            val trimmed = label.trim()
            if (trimmed.isEmpty()) {
                entryDao.delete(network, type.wire, ref)
            } else {
                val now = System.currentTimeMillis()
                val existing = entryDao.find(network, type.wire, ref)
                entryDao.upsert(
                    LabelEntry(
                        network = network,
                        type = type.wire,
                        ref = ref,
                        label = trimmed,
                        origin = existing?.origin,
                        createdAt = existing?.createdAt ?: now,
                        updatedAt = now
                    )
                )
            }
        }

    //
    // Auto UTXO labels (utxo_labels, issue #46) — mirror setAutoTxLabel
    //

    /**
     * Existing utxo label row, or null when none exists. The
     * null-vs-null-origin distinction matters to the provenance guard,
     * same as tx labels (issue #6 part 2).
     */
    suspend fun getUtxoLabel(txid: String, vout: Int): UtxoLabel? =
        withContext(Dispatchers.IO) {
            migrateHandEditedAutoOrigins()
            dao.find(currentNetwork(), txid.lowercase(), vout)
        }

    /**
     * Detector-originated utxo label: explicit origin, never blank,
     * never deletes, preserves createdAt. The manual-provenance guard
     * lives in the caller (the #46 writer), not here — same contract
     * as setAutoTxLabel.
     */
    suspend fun setAutoUtxoLabel(
        txid: String,
        vout: Int,
        label: String,
        origin: String,
        markBadbank: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        migrateHandEditedAutoOrigins()
        val network = currentNetwork()
        val ref = txid.lowercase()
        val trimmed = label.trim()
        check(trimmed.isNotEmpty()) { "auto label must not be blank" }
        val now = System.currentTimeMillis()
        val existing = dao.find(network, ref, vout)
        val marked = markBadbank || existing?.badbank == 1
        dao.upsert(
            UtxoLabel(
                network = network,
                txid = ref,
                vout = vout,
                label = trimmed,
                origin = origin,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                badbank = if (marked) 1 else (existing?.badbank ?: 0),
            )
        )
    }

    /**
     * Row for [network], or null. The account labeller looks up by the
     * network it was asked to write, so a testnet pass cannot see a
     * mainnet row.
     */
    suspend fun readUtxoLabel(network: String, txid: String, vout: Int): UtxoLabel? =
        withContext(Dispatchers.IO) {
            migrateHandEditedAutoOrigins()
            dao.find(network, txid.lowercase(), vout)
        }

    /** First sight of a live Bad Bank coin: auto label plus the flag. */
    suspend fun insertAutoBadbank(network: String, txid: String, vout: Int, now: Long) =
        withContext(Dispatchers.IO) {
            migrateHandEditedAutoOrigins()
            val config = WhirlpoolConfig.DEFAULT
            dao.upsert(
                UtxoLabel(
                    network = network,
                    txid = txid.lowercase(),
                    vout = vout,
                    label = config.labels.badbank,
                    origin = config.origin,
                    createdAt = now,
                    updatedAt = now,
                    badbank = 1,
                )
            )
        }

    /**
     * Set the flag on a row that does not have it. Label, origin and
     * createdAt stay. A row that is already flagged is left alone, so
     * updatedAt is not rewritten on every sync.
     */
    suspend fun stickBadbankFlag(network: String, txid: String, vout: Int, now: Long) =
        withContext(Dispatchers.IO) {
            migrateHandEditedAutoOrigins()
            val existing = dao.find(network, txid.lowercase(), vout) ?: return@withContext
            if (existing.badbank == 1) return@withContext
            dao.upsert(existing.copy(badbank = 1, updatedAt = now))
        }

    /**
     * Terminator supersession (issue #46): remove OUR auto label only —
     * a manual or imported row is never touched. "A coin can stop being
     * a badbank descendant; it can never silently stay one."
     */
    suspend fun deleteAutoUtxoLabelIfOurs(
        txid: String, vout: Int, ourLabel: String,
    ) = withContext(Dispatchers.IO) {
        migrateHandEditedAutoOrigins()
        dao.deleteIfLabelEquals(currentNetwork(), txid.lowercase(), vout, ourLabel)
    }

    /**
     * #42: move label rows written under a wrong network to the real
     * one. Runs the LabelRescopePlanner's plan copy-then-purge inside
     * a single withTransaction - copy first so the rows are never
     * absent, then purge the distinct foreign networks. Idempotent:
     * an empty plan (all rows already on target) does nothing.
     *
     * Called after keychain derivation; the planner's golden covers
     * the semantics, this executor is carried by planner coverage +
     * device QA (no Room harness exists in the JVM suite).
     */
    suspend fun rescopeToNetwork(target: String) = withContext(Dispatchers.IO) {
        migrateHandEditedAutoOrigins()
        val plan = LabelRescopePlanner.plan(
            target,
            dao.getAllNetworks(),
            entryDao.getAllNetworks()
        )
        if (plan.utxoCopies.isEmpty() && plan.entryCopies.isEmpty() &&
            plan.purgeNetworks.isEmpty()
        ) return@withContext
        roomDb.withTransaction {
            if (plan.utxoCopies.isNotEmpty()) dao.upsertAll(plan.utxoCopies)
            if (plan.entryCopies.isNotEmpty()) entryDao.upsertAll(plan.entryCopies)
            plan.purgeNetworks.forEach { network ->
                if (network != target) {
                    dao.deleteAll(network)
                    entryDao.deleteAll(network)
                }
            }
        }
    }

    /**
     * One-shot, off the main thread. Tx and utxo rows edited by hand
     * while their origin was still auto: are marked manual when the
     * text is not an exact auto label. The pref flag makes a second
     * call return without reading the tables. Safe to call again
     * from a label read or write: the first one does the work.
     */
    suspend fun migrateHandEditedAutoOrigins() {
        if (autoOriginMigrationDone) return
        withContext(Dispatchers.IO) {
            autoOriginMigrationMutex.withLock {
                if (autoOriginMigrationDone) return@withLock
                runAutoOriginMigration(
                    alreadyRan = { prefsUtil.autoOriginHandEditsMigrated == true },
                    markRan = { prefsUtil.autoOriginHandEditsMigrated = true },
                    loadEntries = { entryDao.getAllNetworks() },
                    loadUtxos = { dao.getAllNetworks() },
                    apply = { plan ->
                        if (plan.entries.isNotEmpty() || plan.utxos.isNotEmpty()) {
                            roomDb.withTransaction {
                                if (plan.entries.isNotEmpty()) entryDao.upsertAll(plan.entries)
                                if (plan.utxos.isNotEmpty()) dao.upsertAll(plan.utxos)
                            }
                        }
                    },
                )
                autoOriginMigrationDone = true
            }
        }
    }
}
