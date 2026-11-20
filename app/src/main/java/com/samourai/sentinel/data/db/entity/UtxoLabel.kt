package com.samourai.sentinel.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * A user-defined label for a specific transaction output (BIP329 "output"
 * record), keyed by outpoint and network.
 *
 * Stored in a dedicated table (never merged into the sync-managed
 * [com.samourai.sentinel.data.Utxo] rows) so labels survive backend UTXO
 * replacement during syncs, and persist even after the output is spent -
 * useful wallet history and BIP329 round-tripping both require that.
 *
 * [network] ("mainnet"/"testnet") prevents label collisions if the install
 * is ever switched between networks.
 *
 * [badbank] is sticky. Once it is 1, an edit, import, or sync does not
 * clear it. [label] holds the user part only (or the literal "Bad Bank"
 * an auto-writer stored). The "(Bad Bank)" suffix is composed at render
 * time and is not stored.
 */
@Entity(
    tableName = "utxo_labels",
    primaryKeys = ["network", "txid", "vout"],
    indices = [Index("network")]
)
data class UtxoLabel(
    val network: String,
    val txid: String,
    val vout: Int,
    val label: String,
    val origin: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0")
    val badbank: Int = 0,
)
