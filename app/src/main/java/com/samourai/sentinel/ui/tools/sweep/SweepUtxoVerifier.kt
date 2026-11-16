package com.samourai.sentinel.ui.tools.sweep

import com.samourai.sentinel.api.ApiService
import com.samourai.wallet.api.backend.beans.UnspentOutput
import kotlinx.coroutines.CancellationException
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bouncycastle.util.encoders.Hex
import com.samourai.sentinel.helpers.SafeJson

/**
 * #477 (leg 4): chain verification for the sweep path's claimed utxos.
 *
 * The sweep is the one flow where this app signs with a private key, and
 * every value it commits comes from Dojo's unspent feed: the preview
 * amount is UnspentOutput.sumValue(claimed) minus fee, and
 * MyTransactionOutPoint.getConnectedOutput() rebuilds the signing input
 * from the claimed value and the claimed script - SendFactoryGeneric's
 * signInput reads exactly that. A lying Dojo could deflate the claimed
 * value (the destination output shrinks and the difference burns as
 * fee) or swap the claimed script (the signature commits to a script
 * that is not the one being spent).
 *
 * verifySweepUtxos holds every claimed utxo against the prev transaction
 * before any tx is built or signed: hash, output bounds, value, script.
 * Same doctrine as the compose path's verifyPrevTx (#477 legs 1-2, PR
 * #493): fail closed, never sign unverified data.
 */
class SweepUtxoVerificationException(message: String) : Exception(message)

suspend fun verifySweepUtxos(
    utxos: List<UnspentOutput>,
    params: NetworkParameters?,
    fetchTxHex: suspend (txid: String) -> String?
) {
    val networkParams = params
        ?: throw SweepUtxoVerificationException("Network parameters unavailable")
    for (utxo in utxos) {
        val txid = utxo.tx_hash
            ?: throw SweepUtxoVerificationException("Sweep input carries no txid")
        val hex = fetchTxHex(txid)
            ?: throw SweepUtxoVerificationException("No prev tx data for sweep input")
        val prevTx = try {
            Transaction(networkParams, Hex.decode(hex))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SweepUtxoVerificationException("Prev tx unparseable for sweep input")
        }
        val claimedHash = try {
            Sha256Hash.wrap(Hex.decode(txid))
        } catch (e: Exception) {
            throw SweepUtxoVerificationException("Sweep input txid unparseable")
        }
        if (prevTx.hash != claimedHash) {
            throw SweepUtxoVerificationException("Prev tx hash mismatch for sweep input")
        }
        val outputIndex = utxo.tx_output_n
        if (outputIndex < 0 || outputIndex >= prevTx.outputs.size) {
            throw SweepUtxoVerificationException("Prev tx output index out of range for sweep input")
        }
        val output = prevTx.getOutput(outputIndex.toLong())
        if (output.value.value != utxo.value) {
            throw SweepUtxoVerificationException("Prev tx value mismatch for sweep input")
        }
        // #503: getScriptBytes() hex-decodes the server-claimed script.
        // That decode used to sit outside this function's catches, so a
        // malformed script threw DecoderException instead of
        // SweepUtxoVerificationException. The sweep button only catches
        // the latter, and then its toast and spinner cleanup never run.
        // Every untrusted script conversion on this path goes through
        // here. Cancellation is not a verification failure.
        val claimedScript = try {
            utxo.getScriptBytes()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SweepUtxoVerificationException("Sweep input script unparseable")
        } ?: throw SweepUtxoVerificationException("Sweep input carries no script")
        if (!claimedScript.contentEquals(output.scriptBytes)) {
            throw SweepUtxoVerificationException("Prev tx script mismatch for sweep input")
        }
    }
}

/**
 * #477 (leg 4): the sweep's prev-tx fetch - the same envelope the compose
 * path's verifyPrevTx reads: a JSON body with a "data" field carrying the
 * tx hex. Null on any failure; the verifier turns that into a refusal.
 * Cancellation passes through untouched (#129 doctrine).
 *
 * #503: an oversized body fails inside [ApiService.getTxHex], while the
 * socket is read, and arrives here as an [Exception]. That is the same
 * refusal as any other fetch failure. Checking the string after
 * [ApiService.getTxHex] returns is too late: the body is already copied.
 */
suspend fun fetchPrevTxHexViaApi(apiService: ApiService, txid: String): String? {
    return try {
        val response = apiService.getTxHex(txid)
        if (!response.isSuccessful) {
            null
        } else {
            val body = response.body?.string()
            val json = body?.let { runCatching { SafeJson.parseObject(it) }.getOrNull() }
            if (json?.has("data") == true) json.getString("data") else null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
