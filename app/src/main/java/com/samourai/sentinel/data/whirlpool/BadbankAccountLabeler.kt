package com.samourai.sentinel.data.whirlpool

/**
 * Always-on Bad Bank coin labels (no preference). A live UTXO whose
 * pub is the Bad Bank account is marked, whether or not Whirlpool
 * auto-labels is on and whether or not a Tx0 was detected.
 *
 *  - no row: insert "Bad Bank" / auto:whirlpool:v1 / badbank = 1
 *  - any existing row: set badbank = 1 and leave label and origin
 *    alone. A row that is already flagged is not rewritten, so
 *    updatedAt is not churned.
 *
 * Deposit, premix and postmix coins are not touched. A single
 * address, a non-account xpub, and a key that does not decode are
 * skipped. Writes are scoped to the [network] argument: a testnet
 * pass does not read or write mainnet rows.
 *
 * Pure JVM. The database lives behind [Sink].
 */
class BadbankAccountLabeler(
    private val sink: Sink,
    private val config: WhirlpoolConfig = WhirlpoolConfig.DEFAULT,
) {

    data class Coin(val txid: String, val vout: Int, val pubKey: String)

    data class Row(val label: String, val origin: String?, val badbank: Int)

    interface Sink {
        suspend fun find(network: String, txid: String, vout: Int): Row?
        suspend fun insertAuto(network: String, txid: String, vout: Int, now: Long)
        suspend fun setFlag(network: String, txid: String, vout: Int, now: Long)
    }

    suspend fun apply(coins: List<Coin>, network: String, now: Long = System.currentTimeMillis()) {
        for (coin in coins) {
            val txid = coin.txid.trim().lowercase()
            if (txid.isEmpty()) continue
            if (!AccountKey.isBadBank(coin.pubKey, config)) continue
            val existing = sink.find(network, txid, coin.vout)
            if (existing == null) {
                sink.insertAuto(network, txid, coin.vout, now)
            } else if (existing.badbank != 1) {
                sink.setFlag(network, txid, coin.vout, now)
            }
        }
    }
}
