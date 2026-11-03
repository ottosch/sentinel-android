package com.samourai.sentinel.ui.collectionDetails.send

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.core.hd.HD_Account
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.Utxo
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.send.FeeUtil
import com.samourai.sentinel.send.SuggestedFee
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.wallet.segwit.SegwitAddress
import com.samourai.wallet.segwit.bech32.Bech32UtilGeneric
import com.samourai.wallet.util.XPUB
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Transaction
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.HDKeyDerivation
import org.bouncycastle.util.encoders.Hex
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.math.BigInteger

/**
 * #190: [TransactionComposer.compose] with explicitSelection = true spends
 * exactly the coins it is handed - no auto passes - and reports a pinned
 * set that cannot pay as [TransactionComposer.InsufficientSelectionException]
 * instead of composing around it.
 *
 * Fixture and runtime bootstrap follow [PsbtChangeDerivationTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ExplicitSelectionComposeTest {

    private lateinit var wallet: Wallet

    @Before
    fun setUp() {
        ensureRuntime()
        val suggested = SuggestedFee()
        suggested.defaultPerKB = BigInteger.valueOf(1000L)
        FeeUtil.getInstance().suggestedFee = suggested
        wallet = Wallet(SentinelState.getNetworkParam()!!)
    }

    @Test
    fun autoSelectionPicksOneCoinWhereOneSuffices() {
        // Baseline for the next test: the auto path's single-UTXO pass.
        val tx = compose(
            utxos = listOf(
                wallet.utxo(10_000_000L, 0, txid(1)),
                wallet.utxo(1_000_000L, 1, txid(2))
            ),
            amountSats = 50_000L,
            explicit = false
        )
        assertEquals(1, tx.inputs.size)
    }

    @Test
    fun explicitSelectionSpendsEveryPinnedCoin() {
        val tx = compose(
            utxos = listOf(
                wallet.utxo(10_000_000L, 0, txid(1)),
                wallet.utxo(1_000_000L, 1, txid(2))
            ),
            amountSats = 50_000L,
            explicit = true
        )
        assertEquals(setOf(txid(1), txid(2)), inputTxids(tx))
    }

    @Test
    fun explicitSelectionOfTheSmallerCoinIsNotWidened() {
        val tx = compose(
            utxos = listOf(wallet.utxo(1_000_000L, 1, txid(3))),
            amountSats = 50_000L,
            explicit = true
        )
        assertEquals(setOf(txid(3)), inputTxids(tx))
    }

    @Test
    fun explicitSpendAllOfPinnedCoinsHasOneOutput() {
        val pinned = listOf(
            wallet.utxo(600_000L, 0, txid(4)),
            wallet.utxo(400_000L, 1, txid(5))
        )
        val tx = compose(utxos = pinned, amountSats = 1_000_000L, explicit = true)
        assertEquals(setOf(txid(4), txid(5)), inputTxids(tx))
        assertEquals(1, tx.outputs.size)
    }

    @Test
    fun pinnedCoinsBelowTheAmountAreReportedInsufficient() {
        expectInsufficient(listOf(wallet.utxo(30_000L, 0, txid(6))), amountSats = 40_000L)
    }

    @Test
    fun pinnedCoinsCoveringAmountButNotFeeAreReportedInsufficient() {
        // 30_000 pinned, 29_900 asked: the fee does not fit, and the auto
        // passes are not allowed to reach for another coin.
        expectInsufficient(listOf(wallet.utxo(30_000L, 0, txid(7))), amountSats = 29_900L)
    }

    @Test
    fun pinnedCoinFromAnotherAccountIsRefused() {
        val foreign = wallet.utxo(1_000_000L, 0, txid(8)).copy(pubKey = "not-this-account")
        val composer = composer()
        runBlocking {
            withTimeout(20_000) {
                try {
                    composer.compose(
                        inputUtxos = arrayListOf(foreign),
                        inputAddress = wallet.externalAddress,
                        inputFee = 1000L,
                        inputAmount = 50_000.0,
                        explicitSelection = true
                    )
                    fail("a pinned coin outside the account must not be dropped silently")
                } catch (expected: TransactionComposer.ComposeException) {
                }
            }
        }
    }

    private fun expectInsufficient(utxos: List<Utxo>, amountSats: Long) {
        val composer = composer()
        runBlocking {
            withTimeout(20_000) {
                // compose() hands the fee to a rendezvous channel before it
                // can fail on change, so keep a receiver ready and drop it.
                val fee = async { composer.getMinerFee().receive() }
                try {
                    composer.compose(
                        inputUtxos = ArrayList(utxos),
                        inputAddress = wallet.externalAddress,
                        inputFee = 1000L,
                        inputAmount = amountSats.toDouble(),
                        explicitSelection = true
                    )
                    fail("expected InsufficientSelectionException")
                } catch (e: TransactionComposer.InsufficientSelectionException) {
                    assertEquals(utxos.sumOf { it.value!! }, e.selectedSats)
                    assertTrue(e.requiredSats > e.selectedSats)
                } finally {
                    fee.cancel()
                }
            }
        }
    }

    private fun composer() = TransactionComposer().apply { setPubKey(listOf(wallet.pub)) }

    private fun compose(utxos: List<Utxo>, amountSats: Long, explicit: Boolean): Transaction {
        val composer = composer()
        val bytes = runBlocking {
            withTimeout(20_000) {
                val fee = async { composer.getMinerFee().receive() }
                val ok = composer.compose(
                    inputUtxos = ArrayList(utxos),
                    inputAddress = wallet.externalAddress,
                    inputFee = 1000L,
                    inputAmount = amountSats.toDouble(),
                    explicitSelection = explicit
                )
                assertTrue(ok)
                fee.await()
                composer.getPSBT()?.toBytes() ?: error("composer returned no PSBT bytes")
            }
        }
        return unsignedTx(bytes)
    }

    private fun inputTxids(tx: Transaction): Set<String> =
        tx.inputs.map { it.outpoint.hash.toString() }.toSet()

    /** The PSBT global unsigned tx (key type 0x00, first global record). */
    private fun unsignedTx(psbt: ByteArray): Transaction {
        var i = 5 // magic + 0xff
        fun compact(): Int {
            val first = psbt[i++].toInt() and 0xff
            return when (first) {
                0xfd -> (psbt[i++].toInt() and 0xff) or ((psbt[i++].toInt() and 0xff) shl 8)
                0xfe -> {
                    var v = 0
                    for (s in 0 until 4) v = v or ((psbt[i++].toInt() and 0xff) shl (8 * s))
                    v
                }
                else -> first
            }
        }
        while (true) {
            val keyLen = compact()
            check(keyLen != 0) { "no unsigned tx in PSBT globals" }
            val keyType = psbt[i].toInt() and 0xff
            i += keyLen
            val valueLen = compact()
            val value = psbt.copyOfRange(i, i + valueLen)
            i += valueLen
            if (keyType == 0x00) return Transaction(SentinelState.getNetworkParam(), value)
        }
    }

    private class Wallet(val params: NetworkParameters) {
        val zpub: String
        val externalAddress: String
        val pub: PubKeyModel

        init {
            val seed = ByteArray(32) { (it + 7).toByte() }
            val master = HDKeyDerivation.createMasterPrivateKey(seed)
            val accountKey = HDKeyDerivation.deriveChildKey(
                HDKeyDerivation.deriveChildKey(
                    HDKeyDerivation.deriveChildKey(master, ChildNumber(84, true)),
                    ChildNumber(0, true)
                ),
                ChildNumber(0, true)
            )
            zpub = XPUB.makeXPUB(
                XPUB.MAGIC_ZPUB,
                accountKey.depth.toByte(),
                accountKey.parentFingerprint,
                accountKey.childNumber.i(),
                accountKey.chainCode,
                accountKey.pubKey
            )
            externalAddress = SegwitAddress(
                ECKey.fromPrivate(ByteArray(32) { 0x24 }).pubKey,
                params
            ).bech32AsString
            pub = PubKeyModel(
                pubKey = zpub,
                label = "Deposit",
                type = AddressTypes.BIP84,
                change_index = 3,
                fingerPrint = "0badf00d"
            )
        }

        fun utxo(value: Long, index: Int, txid: String): Utxo {
            val account = HD_Account(params, zpub, "", 0)
            val key = account.getChain(0).getAddressAt(index).ecKey
            val address = SegwitAddress(key.pubKey, params).bech32AsString
            val script = Bech32UtilGeneric.getInstance().computeScriptPubKey(address, params)
            return Utxo(
                idx = txid,
                addr = address,
                confirmations = 1,
                script = Hex.toHexString(script),
                txHash = txid,
                txOutputN = 0,
                value = value,
                path = "m/0/$index",
                pubKey = zpub
            )
        }
    }

    companion object {
        @Volatile
        private var startedKoin = false

        private fun txid(n: Int): String = "%064x".format(n)

        private fun ensureRuntime() {
            if (GlobalContext.getOrNull() == null) {
                val app = ApplicationProvider.getApplicationContext<Application>()
                val prefs = PrefsUtil(app)
                prefs.testnet = false
                startKoin {
                    androidContext(app)
                    modules(
                        module {
                            single { prefs }
                            single { CollectionRepository() }
                        }
                    )
                }
                startedKoin = true
            }
            check(!SentinelState.isTestNet()) { "fixture is a mainnet zpub" }
            shadowOf(Looper.getMainLooper()).idle()
        }

        @JvmStatic
        @AfterClass
        fun tearDownClass() {
            if (startedKoin) {
                stopKoin()
                startedKoin = false
            }
        }
    }
}
