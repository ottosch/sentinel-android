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
import com.samourai.sentinel.data.whirlpool.WhirlpoolConfig
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.wallet.util.XPUB
import kotlinx.coroutines.runBlocking
import org.bitcoinj.params.MainNetParams
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    }

    @After
    fun tearDown() {
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

    private fun accountXpub(account: Long): String {
        val child = (account - 2_147_483_648L).toInt()
        return XPUB.makeXPUB(
            XPUB.MAGIC_ZPUB,
            3.toByte(),
            0,
            child,
            ByteArray(32) { 0x11 },
            ByteArray(33) { 0x02 },
        )
    }
}
