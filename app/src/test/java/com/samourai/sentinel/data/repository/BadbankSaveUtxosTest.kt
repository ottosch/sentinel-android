package com.samourai.sentinel.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.Utxo
import com.samourai.sentinel.data.db.SentinelCollectionStore
import com.samourai.sentinel.data.db.SentinelRoomDb
import com.samourai.sentinel.data.whirlpool.BadbankAccountLabeler
import com.samourai.sentinel.data.whirlpool.WhirlpoolConfig
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.util.UtxoMetaUtil
import com.samourai.wallet.util.XPUB
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.params.TestNet3Params
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The account labeller runs from saveUtxos itself. Whirlpool
 * auto-labels off does not skip a live Bad Bank coin.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BadbankSaveUtxosTest {

    private val txHash = "cd".repeat(32)
    private val vout = 3
    private lateinit var db: SentinelRoomDb
    private lateinit var prefs: PrefsUtil
    private lateinit var labels: LabelRepository
    private lateinit var transactions: TransactionsRepository
    private lateinit var collections: CollectionRepository

    @Before
    fun setUp() {
        if (GlobalContext.getOrNull() != null) stopKoin()
        val app = ApplicationProvider.getApplicationContext<Application>()
        prefs = PrefsUtil(app)
        prefs.clearAll()
        prefs.testnet = false
        prefs.whirlpoolAutoLabels = false
        prefs.autoOriginHandEditsMigrated = true
        db = Room.inMemoryDatabaseBuilder(app, SentinelRoomDb::class.java)
            .allowMainThreadQueries()
            .build()
        startKoin {
            androidContext(app)
            modules(module {
                single { prefs }
                single { db }
                single { db.utxoDao() }
                single { db.utxoLabelDao() }
                single { db.labelEntryDao() }
                single { db.txDao() }
                single { db.txEntropyDao() }
                single { SentinelCollectionStore() }
                single { DojoUtility() }
                single { CollectionRepository() }
                single { LabelRepository() }
                single { TransactionsRepository() }
            })
        }
        val field = SentinelState::class.java.getDeclaredField("networkParams")
        field.isAccessible = true
        field.set(null, MainNetParams.get())
        labels = org.koin.java.KoinJavaComponent.get(LabelRepository::class.java)
        transactions = org.koin.java.KoinJavaComponent.get(TransactionsRepository::class.java)
        collections = org.koin.java.KoinJavaComponent.get(CollectionRepository::class.java)
        UtxoMetaUtil.clearAll()
    }

    @After
    fun tearDown() {
        if (GlobalContext.getOrNull() != null) {
            prefs.testnet = false
            forceNetwork(MainNetParams.get())
            UtxoMetaUtil.clearAll()
        }
        if (this::db.isInitialized) db.close()
        if (GlobalContext.getOrNull() != null) stopKoin()
    }

    @Test
    fun saveUtxosFlagsABadBankCoinWhileAutoLabelsAreOff() = runBlocking {
        assertFalse(prefs.whirlpoolAutoLabels == true)
        val pub = accountXpub(WhirlpoolConfig.DEFAULT.badbankAccount)
        collections.addNew(
            PubKeyCollection(
                collectionLabel = "Whirlpool",
                pubs = arrayListOf(PubKeyModel(pub, "Bad Bank", AddressTypes.BIP84)),
            ),
        )
        val collectionId = collections.collectionsSnapshot().single().id
        val utxo = Utxo(
            idx = "$txHash:$vout",
            txHash = txHash,
            txOutputN = vout,
            pubKey = pub,
            value = 50_000L,
            collectionId = collectionId,
        )
        transactions.saveUtxos(arrayListOf(utxo), collectionId)
        val row = db.utxoLabelDao().find(labels.currentNetwork(), txHash, vout)!!
        assertEquals("mainnet", row.network)
        assertEquals(txHash, row.txid)
        assertEquals(vout, row.vout)
        assertEquals(1, row.badbank)
        assertEquals("Bad Bank", row.label)
        assertEquals(WhirlpoolConfig.DEFAULT.origin, row.origin)
    }

    @Test
    fun saveUtxosFlagsABadBankCoinOnTestnetAndNotOnMainnet() = runBlocking {
        prefs.testnet = true
        forceNetwork(TestNet3Params.get())
        assertEquals("testnet", labels.currentNetwork())
        val pub = accountXpub(WhirlpoolConfig.DEFAULT.badbankAccount, XPUB.MAGIC_VPUB)
        collections.addNew(
            PubKeyCollection(
                collectionLabel = "Whirlpool",
                pubs = arrayListOf(PubKeyModel(pub, "Bad Bank", AddressTypes.BIP84)),
            ),
        )
        val collectionId = collections.collectionsSnapshot().single().id
        val utxo = Utxo(
            idx = "$txHash:$vout",
            txHash = txHash,
            txOutputN = vout,
            pubKey = pub,
            value = 50_000L,
            collectionId = collectionId,
        )
        transactions.saveUtxos(arrayListOf(utxo), collectionId)
        val row = db.utxoLabelDao().find("testnet", txHash, vout)!!
        assertEquals("testnet", row.network)
        assertEquals(txHash, row.txid)
        assertEquals(vout, row.vout)
        assertEquals(1, row.badbank)
        assertNull(db.utxoLabelDao().find("mainnet", txHash, vout))
    }

    @Test
    fun cancellationThrownInTheHookPropagatesAndSkipsCleanup() = runBlocking {
        val (collectionId, utxo) = blockedSetup()
        installSink { throw CancellationException("labeler cancelled") }
        val result = runCatching { transactions.saveUtxos(arrayListOf(utxo), collectionId) }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertTrue(UtxoMetaUtil.has(BLOCKED_TX, 0))
        assertTrue(db.utxoDao().getUTXObyCollectionAsList(collectionId).any { it.idx == utxo.idx })
    }

    @Test
    fun realJobCancelStopsBeforeCleanup() = runBlocking {
        val (collectionId, utxo) = blockedSetup()
        val entered = CompletableDeferred<Unit>()
        installSink {
            entered.complete(Unit)
            delay(10_000)
        }
        var cause: Throwable? = null
        val job = launch(Dispatchers.Default) {
            try {
                transactions.saveUtxos(arrayListOf(utxo), collectionId)
            } catch (thrown: Throwable) {
                cause = thrown
                throw thrown
            }
        }
        withTimeout(5_000) {
            entered.await()
            job.cancel()
            job.join()
        }
        assertTrue(cause is CancellationException)
        assertTrue(UtxoMetaUtil.has(BLOCKED_TX, 0))
    }

    @Test
    fun errorsAreIsolatedAndSyncCompletes() = runBlocking {
        val errors = listOf(
            StackOverflowError("labeller"),
            OutOfMemoryError("labeller"),
            IllegalStateException("labeller"),
        )
        for (error in errors) {
            val (collectionId, utxo) = if (collections.collectionsSnapshot().isEmpty()) {
                blockedSetup()
            } else {
                val collectionId = collections.collectionsSnapshot().single().id
                UtxoMetaUtil.put(blockedUtxo(collectionId))
                collectionId to liveUtxo(collectionId)
            }
            installSink { throw error }
            val result = runCatching { transactions.saveUtxos(arrayListOf(utxo), collectionId) }
            assertTrue("${error.javaClass.simpleName} escaped saveUtxos", result.isSuccess)
            assertTrue(
                db.utxoDao().getUTXObyCollectionAsList(collectionId).any { it.idx == utxo.idx },
            )
            assertFalse(
                "${error.javaClass.simpleName} skipped do-not-spend cleanup",
                UtxoMetaUtil.has(BLOCKED_TX, 0),
            )
        }
    }

    private fun blockedSetup(): Pair<String, Utxo> {
        val pub = accountXpub(WhirlpoolConfig.DEFAULT.badbankAccount)
        collections.addNew(
            PubKeyCollection(
                collectionLabel = "Whirlpool",
                pubs = arrayListOf(PubKeyModel(pub, "Bad Bank", AddressTypes.BIP84)),
            ),
        )
        val collectionId = collections.collectionsSnapshot().single().id
        UtxoMetaUtil.clearAll()
        UtxoMetaUtil.put(blockedUtxo(collectionId, pub))
        return collectionId to liveUtxo(collectionId, pub)
    }

    private fun blockedUtxo(collectionId: String, pub: String = accountXpub(WhirlpoolConfig.DEFAULT.badbankAccount)) =
        Utxo(
            idx = "$BLOCKED_TX:0",
            txHash = BLOCKED_TX,
            txOutputN = 0,
            pubKey = pub,
            value = 1L,
            collectionId = collectionId,
        )

    private fun liveUtxo(collectionId: String, pub: String = accountXpub(WhirlpoolConfig.DEFAULT.badbankAccount)) =
        Utxo(
            idx = "$txHash:$vout",
            txHash = txHash,
            txOutputN = vout,
            pubKey = pub,
            value = 50_000L,
            collectionId = collectionId,
        )

    private fun installSink(onFind: suspend () -> Unit) {
        val field = TransactionsRepository::class.java.getDeclaredField("badbankAccountLabeler\$delegate")
        field.isAccessible = true
        field.set(transactions, lazyOf(BadbankAccountLabeler(ThrowingSink(onFind))))
    }

    private class ThrowingSink(
        private val onFind: suspend () -> Unit,
    ) : BadbankAccountLabeler.Sink {
        override suspend fun find(network: String, txid: String, vout: Int): BadbankAccountLabeler.Row? {
            onFind()
            return null
        }

        override suspend fun insertAuto(network: String, txid: String, vout: Int, now: Long) {}

        override suspend fun setFlag(network: String, txid: String, vout: Int, now: Long) {}
    }

    private fun forceNetwork(params: org.bitcoinj.core.NetworkParameters) {
        val field = SentinelState::class.java.getDeclaredField("networkParams")
        field.isAccessible = true
        field.set(null, params)
    }

    private fun accountXpub(account: Long, version: Int = XPUB.MAGIC_ZPUB): String {
        val child = (account - 2_147_483_648L).toInt()
        return XPUB.makeXPUB(
            version,
            3.toByte(),
            0,
            child,
            ByteArray(32) { 0x11 },
            ByteArray(33) { 0x02 },
        )
    }

    private companion object {
        private val BLOCKED_TX = "ee".repeat(32)
    }
}
