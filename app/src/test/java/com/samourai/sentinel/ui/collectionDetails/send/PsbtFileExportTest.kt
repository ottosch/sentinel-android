package com.samourai.sentinel.ui.collectionDetails.send

import android.app.Application
import android.os.Looper
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.R
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.core.hd.HD_Account
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.Utxo
import com.samourai.sentinel.data.db.SentinelCollectionStore
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.send.FeeUtil
import com.samourai.sentinel.send.SuggestedFee
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.wallet.psbt.PSBT
import com.samourai.wallet.segwit.SegwitAddress
import com.samourai.wallet.segwit.bech32.Bech32UtilGeneric
import com.samourai.wallet.util.XPUB
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.bitcoinj.core.Coin
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionInput
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.HDKeyDerivation
import org.bouncycastle.util.encoders.Hex
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetAddress

/**
 * #566: the file the user saves or shares is the PSBT they reviewed.
 *
 * Compose goes through [TransactionComposer], the same call the send
 * screen makes. The snapshot is what [SendViewModel.makeTx] keeps, and
 * the file is what [PsbtFileExport] writes. A later compose landing in
 * the live composer must not change either.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PsbtFileExportTest {

    private lateinit var wallet: Wallet

    @Before
    fun setUp() {
        ensureRuntime(serverUrl())
        val suggested = SuggestedFee()
        suggested.defaultPerKB = BigInteger.valueOf(1000L)
        FeeUtil.getInstance().suggestedFee = suggested
        wallet = Wallet(SentinelState.getNetworkParam()!!)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun savedAndSharedFileBytesMatchTheReviewedQrSnapshot() {
        val coin = wallet.funded(1_000_000L, 0)
        val vm = review(compose(coin))
        val snapshot = vm.getPsbtBytes()
        assertNotNull(snapshot)
        val shown = Hex.decode(vm.psbtLive.value)
        assertArrayEquals(snapshot, shown)

        val shared = File.createTempFile("share", ".psbt")
        val share = PsbtFileExport.write(
            bytes = vm.getPsbtBytes(),
            params = params(),
            documentAlreadyExists = false,
            open = { shared.outputStream() },
            delete = { shared.delete() },
        )
        assertTrue(share is PsbtFileExport.Outcome.Saved)
        assertArrayEquals(shown, shared.readBytes())

        val saved = File.createTempFile("save", ".psbt")
        saved.writeBytes(ByteArray(0))
        val save = PsbtFileExport.write(
            bytes = vm.getPsbtBytes(),
            params = params(),
            documentAlreadyExists = true,
            open = { saved.outputStream() },
            delete = { saved.delete() },
        )
        assertTrue(save is PsbtFileExport.Outcome.Saved)
        assertArrayEquals(shown, saved.readBytes())
        shared.delete()
        saved.delete()
    }

    @Test
    fun resumeDuringShareDoesNotCrashAndLeavesTheFile() {
        val reviewedCoin = wallet.funded(1_000_000L, 0)
        val composer = compose(reviewedCoin)
        val vm = review(composer)
        val snapshot = vm.getPsbtBytes()!!
        val file = File.createTempFile("midshare", ".psbt")
        assertTrue(
            PsbtFileExport.write(
                bytes = snapshot,
                params = params(),
                documentAlreadyExists = false,
                open = { file.outputStream() },
                delete = { file.delete() },
            ) is PsbtFileExport.Outcome.Saved
        )

        val rebuilt = compose(wallet.funded(2_000_000L, 1)).getPSBT()!!.toBytes()!!
        assertFalse(
            "rebuilt PSBT must differ from the reviewed one",
            snapshot.contentEquals(rebuilt),
        )
        // Resume started a new compose. The field already points at it,
        // and the spend is not valid yet. The old reader returned null
        // here, and the share path crashed on !!.
        composer.plant(PSBT.fromBytes(rebuilt, params()))
        val live = composer.getPSBT()?.toBytes()
        assertNotNull(live)
        assertFalse(snapshot.contentEquals(live))
        vm.markValid(false)
        val gate = CompletableDeferred<Unit>()
        vm.job = vm.viewModelScope.launch { gate.await() }
        shadowOf(Looper.getMainLooper()).idle()
        try {
            assertTrue(vm.job!!.isActive)
            val during = vm.getPsbtBytes()
            assertNotNull(during)
            assertArrayEquals(snapshot, during)
            val again = PsbtFileExport.write(
                bytes = vm.getPsbtBytes(),
                params = params(),
                documentAlreadyExists = false,
                open = { file.outputStream() },
                delete = { file.delete() },
            )
            assertTrue(again is PsbtFileExport.Outcome.Saved)
            assertArrayEquals(snapshot, file.readBytes())
            assertFalse(file.readBytes().contentEquals(rebuilt))
            // The same coins are still unspent. Resume itself is not a reason to close.
            assertNull(vm.noteReviewedInputs(listOf(reviewedCoin.utxo)) { false })
            assertArrayEquals(snapshot, vm.getPsbtBytes())
        } finally {
            gate.cancel()
            vm.job?.cancel()
            file.delete()
        }
    }

    @Test
    fun utxoUpdateWhileThePickerIsOpenLeavesTheFileUnchanged() {
        val reviewedCoin = wallet.funded(1_000_000L, 0)
        val composer = compose(reviewedCoin)
        val vm = review(composer)
        val snapshot = vm.getPsbtBytes()!!
        val rebuiltComposer = compose(wallet.funded(2_000_000L, 1))
        val rebuilt = rebuiltComposer.getPSBT()!!.toBytes()!!
        assertFalse(
            "rebuilt PSBT must differ from the reviewed one, or this would pass on the old export",
            snapshot.contentEquals(rebuilt),
        )
        // The picker is open. A coin sync finished a new compose and
        // marked it valid. The document on disk is still the empty one
        // the picker created.
        composer.plant(rebuiltComposer.getPSBT())
        vm.markValid(true)
        val doc = File.createTempFile("picker", ".psbt")
        doc.writeBytes(ByteArray(0))
        val outcome = PsbtFileExport.write(
            bytes = vm.getPsbtBytes(),
            params = params(),
            documentAlreadyExists = true,
            open = { doc.outputStream() },
            delete = { doc.delete() },
        )
        assertTrue(outcome is PsbtFileExport.Outcome.Saved)
        assertArrayEquals(snapshot, doc.readBytes())
        assertFalse(doc.readBytes().contentEquals(rebuilt))
        assertNull(vm.noteReviewedInputs(listOf(reviewedCoin.utxo)) { false })
        doc.delete()
    }

    @Test
    fun nullEmptyAndMalformedPsbtAreRefusedBeforeAnyFileOpens() {
        val params = params()
        val bad = listOf<ByteArray?>(
            null,
            ByteArray(0),
            byteArrayOf(0x00),
            "not-a-psbt".toByteArray(),
            Hex.decode("70736274ff"),
        )
        for (bytes in bad) {
            var opened = false
            var deleted = false
            val outcome = PsbtFileExport.write(
                bytes = bytes,
                params = params,
                documentAlreadyExists = false,
                open = {
                    opened = true
                    ByteArrayOutputStream()
                },
                delete = { deleted = true },
            )
            assertFalse("opened for ${bytes?.size}", opened)
            assertFalse("deleted for ${bytes?.size}", deleted)
            assertTrue(outcome is PsbtFileExport.Outcome.Refused)
        }
    }

    @Test
    fun writeFailuresAreContainedAndTheDocumentIsRemoved() {
        val snapshot = review(compose(wallet.funded(1_000_000L, 0))).getPsbtBytes()!!
        val failures = listOf<Throwable>(
            IOException("disk"),
            SecurityException("denied"),
            OutOfMemoryError("export"),
            BoomError(),
            BareThrowable(),
            ApiService.ApiNotConfigured(),
        )
        for (failure in failures) {
            val doc = File.createTempFile("psbt-fail", ".psbt")
            doc.writeBytes(byteArrayOf(9))
            val stream = ProbeStream(failure)
            val outcome = try {
                PsbtFileExport.write(
                    bytes = snapshot,
                    params = params(),
                    documentAlreadyExists = true,
                    open = { stream },
                    delete = { doc.delete() },
                )
            } catch (t: Throwable) {
                doc.delete()
                throw AssertionError("${failure.javaClass.simpleName} escaped the export", t)
            }
            assertTrue("${failure.javaClass.name} should fail closed", outcome is PsbtFileExport.Outcome.Failed)
            assertSame(failure, (outcome as PsbtFileExport.Outcome.Failed).error)
            assertTrue("stream closed for ${failure.javaClass.simpleName}", stream.closed)
            assertFalse("doc deleted for ${failure.javaClass.simpleName}", doc.exists())
        }

        val empty = File.createTempFile("psbt-null-stream", ".psbt")
        empty.writeBytes(ByteArray(0))
        var opened = false
        val missing = PsbtFileExport.write(
            bytes = snapshot,
            params = params(),
            documentAlreadyExists = true,
            open = {
                opened = true
                null
            },
            delete = { empty.delete() },
        )
        assertTrue(opened)
        assertTrue(missing is PsbtFileExport.Outcome.Failed)
        assertFalse(empty.exists())
    }

    @Test
    fun spentOrFrozenReviewedInputClosesTheReview() {
        val coin = wallet.funded(1_000_000L, 0)
        val vm = review(compose(coin))
        assertEquals(setOf(CoinSelection.keyOf(coin.utxo)), vm.reviewedOutpoints())
        val message = ApplicationProvider.getApplicationContext<Application>()
            .getString(R.string.send_review_inputs_changed)
        assertEquals(
            "A coin in this transaction was spent or frozen. Review it again.",
            message,
        )

        val spent = vm.noteReviewedInputs(emptyList()) { false }
        assertEquals(R.string.send_review_inputs_changed, spent)
        assertEquals(R.string.send_review_inputs_changed, vm.freshReview.value)
        assertNull(vm.getPsbtBytes())
        assertEquals("", vm.psbtLive.value)

        val again = review(compose(coin))
        val frozen = again.noteReviewedInputs(listOf(coin.utxo)) { true }
        assertEquals(R.string.send_review_inputs_changed, frozen)
        assertNull(again.getPsbtBytes())
    }

    @Test
    fun unrelatedCoinChangeDoesNotCloseTheReview() {
        val coin = wallet.funded(1_000_000L, 0)
        val vm = review(compose(coin))
        val snapshot = vm.getPsbtBytes()!!
        val other = coin.utxo.copy(
            txHash = "11".repeat(32),
            txOutputN = 1,
            idx = "other",
        )
        assertNull(vm.noteReviewedInputs(listOf(coin.utxo, other)) { false })
        assertNull(vm.noteReviewedInputs(listOf(coin.utxo)) { CoinSelection.keyOf(it) == CoinSelection.keyOf(other) })
        assertNull(vm.noteReviewedInputs(listOf(coin.utxo, other)) { CoinSelection.keyOf(it) == CoinSelection.keyOf(other) })
        assertNull(vm.freshReview.value)
        assertArrayEquals(snapshot, vm.getPsbtBytes())
    }

    @Test
    fun processDeathWhileThePickerIsOpenFailsClosed() {
        val rebuilt = compose(wallet.funded(1_000_000L, 0))
        val rebuiltBytes = rebuilt.getPSBT()!!.toBytes()!!
        assertTrue(rebuiltBytes.isNotEmpty())
        // The process was killed. The new model can see a composer that
        // resume already rebuilt, and the button can look valid. There
        // was no review in this process, so the export must not write it.
        val killed = SendViewModel()
        killed.use(rebuilt)
        killed.markValid(true)
        assertNull(killed.getPsbtBytes())
        assertFalse(rebuiltBytes.contentEquals(killed.getPsbtBytes() ?: ByteArray(0)))

        val doc = File.createTempFile("killed", ".psbt")
        doc.writeBytes(ByteArray(0))
        var opened = false
        val outcome = PsbtFileExport.write(
            bytes = killed.getPsbtBytes(),
            params = params(),
            documentAlreadyExists = true,
            open = {
                opened = true
                doc.outputStream()
            },
            delete = { doc.delete() },
        )
        assertFalse(opened)
        assertTrue(outcome is PsbtFileExport.Outcome.Refused)
        assertEquals(
            PsbtFileExport.Refusal.MISSING,
            (outcome as PsbtFileExport.Outcome.Refused).reason,
        )
        assertFalse(doc.exists())
        assertNull(killed.getPsbtBytes())
    }

    @Test
    fun makeTxRefusesARebuildInFlightOrAnUnusablePsbt() {
        val composer = compose(wallet.funded(1_000_000L, 0))
        val good = composer.getPSBT()!!.toBytes()!!
        val vm = SendViewModel()
        vm.use(composer)
        vm.markValid(true)
        val gate = CompletableDeferred<Unit>()
        vm.job = vm.viewModelScope.launch { gate.await() }
        shadowOf(Looper.getMainLooper()).idle()
        try {
            assertTrue(vm.job!!.isActive)
            assertFalse(vm.makeTx())
            assertEquals(SendViewModel.PsbtSnapshotError.REBUILD_IN_FLIGHT, vm.takeSnapshotError())
            assertNull(vm.getPsbtBytes())
            assertEquals("", vm.psbtLive.value)
        } finally {
            gate.cancel()
            vm.job?.cancel()
            vm.job = null
        }

        vm.markValid(true)
        assertTrue(vm.makeTx())
        assertArrayEquals(good, vm.getPsbtBytes())
        vm.clearReviewedPsbt()
        assertNull(vm.getPsbtBytes())

        val empty = TransactionComposer()
        vm.use(empty)
        vm.markValid(true)
        assertFalse(vm.makeTx())
        assertEquals(SendViewModel.PsbtSnapshotError.UNUSABLE, vm.takeSnapshotError())
        assertNull(vm.getPsbtBytes())

        val half = TransactionComposer()
        half.plant(PSBT(Transaction(params())))
        vm.use(half)
        vm.markValid(true)
        assertFalse(vm.makeTx())
        assertEquals(SendViewModel.PsbtSnapshotError.UNUSABLE, vm.takeSnapshotError())
        assertNull(vm.getPsbtBytes())

        vm.markValid(false)
        vm.use(composer)
        assertFalse(vm.makeTx())
        assertEquals(SendViewModel.PsbtSnapshotError.REBUILD_IN_FLIGHT, vm.takeSnapshotError())
    }

    private fun params(): NetworkParameters = SentinelState.getNetworkParam()!!

    private fun review(composer: TransactionComposer): SendViewModel {
        val vm = SendViewModel()
        vm.use(composer)
        vm.markValid(true)
        assertTrue(vm.makeTx())
        return vm
    }

    private fun compose(funded: FundedUtxo): TransactionComposer {
        val composer = TransactionComposer().apply { setPubKey(listOf(wallet.pub)) }
        runBlocking {
            withTimeout(20_000) {
                val fee = async { composer.getMinerFee().receive() }
                val ok = composer.compose(
                    inputUtxos = arrayListOf(funded.utxo),
                    inputAddress = wallet.externalAddress,
                    inputFee = 1000L,
                    inputAmount = 50_000.0,
                    explicitSelection = true,
                )
                assertTrue(ok)
                fee.await()
            }
        }
        assertNotNull(composer.getPSBT()?.toBytes())
        return composer
    }

    private fun SendViewModel.use(composer: TransactionComposer) {
        val field = SendViewModel::class.java.getDeclaredField("transactionComposer")
        field.isAccessible = true
        field.set(this, composer)
    }

    private fun SendViewModel.markValid(valid: Boolean) {
        val field = SendViewModel::class.java.getDeclaredField("_validSpend")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(this) as MutableLiveData<Boolean>).value = valid
    }

    private fun TransactionComposer.plant(psbt: PSBT?) {
        val field = TransactionComposer::class.java.getDeclaredField("psbt")
        field.isAccessible = true
        field.set(this, psbt)
    }

    private class ProbeStream(private val failure: Throwable) : OutputStream() {
        var closed = false
        override fun write(b: Int) = throw failure
        override fun write(b: ByteArray) = throw failure
        override fun write(b: ByteArray, off: Int, len: Int) = throw failure
        override fun close() {
            closed = true
        }
    }

    private class BoomError : Error("boom")

    private class BareThrowable : Throwable("bare")

    /** #469: the Utxo plus the funding-tx hex the fake Dojo serves. */
    private class FundedUtxo(val utxo: Utxo, val hex: String)

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

        fun funded(value: Long, index: Int): FundedUtxo {
            val account = HD_Account(params, zpub, "", 0)
            val key = account.getChain(0).getAddressAt(index).ecKey
            val address = SegwitAddress(key.pubKey, params).bech32AsString
            val script = Bech32UtilGeneric.getInstance().computeScriptPubKey(address, params)
            val funding = Transaction(params)
            funding.addInput(TransactionInput(params, funding, byteArrayOf(0x51)))
            funding.addOutput(TransactionOutput(params, funding, Coin.valueOf(value), script))
            val hex = Hex.toHexString(funding.bitcoinSerialize())
            val txid = funding.hash.toString()
            served[txid] = hex
            return FundedUtxo(
                Utxo(
                    idx = txid,
                    addr = address,
                    confirmations = 1,
                    script = Hex.toHexString(script),
                    txHash = txid,
                    txOutputN = 0,
                    value = value,
                    path = "m/0/$index",
                    pubKey = zpub
                ),
                hex
            )
        }
    }

    companion object {
        @Volatile
        private var startedKoin = false

        private lateinit var server: MockWebServer

        private val served = HashMap<String, String>()

        private fun serverUrl(): String = server.url("/").toString()

        private fun installDispatcher() {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val txid = request.path!!.substringAfter("/tx/").removeSuffix("/HEX")
                    val hex = served[txid]
                        ?: return MockResponse().setResponseCode(404)
                            .setBody("""{"status":"error"}""")
                    return MockResponse()
                        .setBody("""{"status":"ok","data":"$hex"}""")
                }
            }
        }

        private fun ensureRuntime(baseUrl: String) {
            GlobalContext.getOrNull()?.let { stopKoin() }
            val app = ApplicationProvider.getApplicationContext<Application>()
            val prefs = PrefsUtil(app)
            prefs.testnet = false
            prefs.apiEndPoint = baseUrl
            startKoin {
                androidContext(app)
                modules(
                    module {
                        single { prefs }
                        single { CollectionRepository() }
                        single { ApiService() }
                        single { SentinelCollectionStore() }
                        single { DojoUtility() }
                    }
                )
            }
            startedKoin = true
            check(!SentinelState.isTestNet()) { "fixture is a mainnet zpub" }
            shadowOf(Looper.getMainLooper()).idle()
        }

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            server = MockWebServer()
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            installDispatcher()
        }

        @JvmStatic
        @AfterClass
        fun tearDownClass() {
            if (startedKoin) {
                stopKoin()
                startedKoin = false
            }
            server.shutdown()
            served.clear()
        }
    }
}
