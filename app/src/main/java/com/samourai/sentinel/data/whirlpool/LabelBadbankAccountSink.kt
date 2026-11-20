package com.samourai.sentinel.data.whirlpool

import com.samourai.sentinel.data.repository.LabelRepository

/**
 * Room-backed sink for [BadbankAccountLabeler]. The network argument
 * is the one the labeller was given, not a second read of the
 * process-wide flag.
 */
class LabelBadbankAccountSink(
    private val labels: LabelRepository,
) : BadbankAccountLabeler.Sink {

    override suspend fun find(network: String, txid: String, vout: Int) =
        labels.readUtxoLabel(network, txid, vout)?.let {
            BadbankAccountLabeler.Row(it.label, it.origin, it.badbank)
        }

    override suspend fun insertAuto(network: String, txid: String, vout: Int, now: Long) {
        labels.insertAutoBadbank(network, txid, vout, now)
    }

    override suspend fun setFlag(network: String, txid: String, vout: Int, now: Long) {
        labels.stickBadbankFlag(network, txid, vout, now)
    }
}
