package com.samourai.sentinel.ui.utxos

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.chip.Chip
import com.samourai.sentinel.R
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.Utxo
import com.samourai.sentinel.data.db.SentinelRoomDb
import com.samourai.sentinel.data.db.entity.UtxoLabel
import com.samourai.sentinel.data.repository.LabelRepository
import com.samourai.sentinel.data.db.SentinelCollectionStore
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.data.whirlpool.WhirlpoolConfig
import com.samourai.sentinel.ui.collectionDetails.send.CoinPickerBottomSheet
import com.samourai.sentinel.ui.collectionDetails.send.SendViewModel
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.wallet.util.XPUB
import java.util.concurrent.Executor
import kotlinx.coroutines.runBlocking
import org.bitcoinj.params.MainNetParams
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Unspent-output list and coin picker render the composed Bad Bank
 * label. The chips are not buttons.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BadbankCoinChipWiringTest {

    private val direct = Executor { it.run() }
    private lateinit var db: SentinelRoomDb
    private lateinit var labels: LabelRepository

    @Before
    fun setUp() {
        if (GlobalContext.getOrNull() != null) stopKoin()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = PrefsUtil(app)
        prefs.clearAll()
        prefs.testnet = false
        prefs.autoOriginHandEditsMigrated = true
        db = Room.inMemoryDatabaseBuilder(app, SentinelRoomDb::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(direct)
            .setTransactionExecutor(direct)
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
                single { ApiService() }
                single { SentinelCollectionStore() }
                single { DojoUtility() }
                single { CollectionRepository() }
                single { LabelRepository() }
            })
        }
        val field = SentinelState::class.java.getDeclaredField("networkParams")
        field.isAccessible = true
        field.set(null, MainNetParams.get())
        labels = org.koin.java.KoinJavaComponent.get(LabelRepository::class.java)
    }

    @After
    fun tearDown() {
        if (this::db.isInitialized) db.close()
        if (GlobalContext.getOrNull() != null) stopKoin()
    }

    @Test
    fun theUnspentListComposesBadBankChips() {
        val badPub = accountXpub(WhirlpoolConfig.DEFAULT.badbankAccount)
        val bad = utxo("aa", 0, badPub)
        val flagged = utxo("bb", 1, accountXpub(WhirlpoolConfig.DEFAULT.depositAccount))
        val hand = utxo("cc", 2, accountXpub(WhirlpoolConfig.DEFAULT.depositAccount))
        insert("bb", 1, "Cold storage", badbank = 1)
        insert("cc", 2, "x (Bad Bank)", badbank = 0)
        val host = Robolectric.buildActivity(AppCompatActivity::class.java)
        host.get().setTheme(R.style.AppThemeV3)
        val activity = host.setup().get()
        val fragment = UTXOFragment()
        activity.supportFragmentManager.beginTransaction()
            .add(android.R.id.content, fragment)
            .commitNow()
        fragment.setUtxos(arrayListOf(bad, flagged, hand))
        val recycler = fragment.requireView().findViewById<RecyclerView>(R.id.utxoRecyclerView)
        val chips = awaitChips(recycler)
        assertEquals(setOf("Bad Bank", "Cold storage (Bad Bank)", "x (Bad Bank)"), chips.map { it.text.toString() }.toSet())
        assertTrue(chips.all { !it.isClickable })
    }

    @Test
    fun theCoinPickerComposesBadBankChips() {
        val badPub = accountXpub(WhirlpoolConfig.DEFAULT.badbankAccount)
        val bad = utxo("aa", 0, badPub)
        val flagged = utxo("bb", 1, "")
        insert("bb", 1, "Cold storage", badbank = 1)
        val host = Robolectric.buildActivity(AppCompatActivity::class.java)
        host.get().setTheme(R.style.AppThemeV3)
        val activity = host.setup().get()
        val parent = Fragment()
        activity.supportFragmentManager.beginTransaction()
            .add(android.R.id.content, parent)
            .commitNow()
        val sheet = CoinPickerBottomSheet()
        sheet.show(parent.childFragmentManager, CoinPickerBottomSheet.TAG)
        parent.childFragmentManager.executePendingTransactions()
        shadowOf(Looper.getMainLooper()).idle()
        val model = ViewModelProvider(parent)[SendViewModel::class.java]
        val field = SendViewModel::class.java.getDeclaredField("_utxoPool")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val pool = field.get(model) as MutableLiveData<List<Utxo>>
        pool.value = listOf(bad, flagged)
        val recycler = sheet.requireView().findViewById<RecyclerView>(R.id.coinPickerList)
        val chips = awaitChips(recycler)
        assertEquals(setOf("Bad Bank", "Cold storage (Bad Bank)"), chips.map { it.text.toString() }.toSet())
        assertTrue(chips.all { !it.isClickable })
    }

    @Test
    fun mixedSpendWarnsForAFlaggedCustomLabelBesideADeposit() {
        val depositPub = accountXpub(WhirlpoolConfig.DEFAULT.depositAccount)
        val flagged = utxo("bb", 1, depositPub)
        val deposit = utxo("cc", 2, depositPub)
        insert("bb", 1, "Cold storage", badbank = 1)
        val host = Robolectric.buildActivity(AppCompatActivity::class.java)
        host.get().setTheme(R.style.AppThemeV3)
        val activity = host.setup().get()
        val parent = Fragment()
        activity.supportFragmentManager.beginTransaction()
            .add(android.R.id.content, parent)
            .commitNow()
        val sheet = CoinPickerBottomSheet()
        sheet.show(parent.childFragmentManager, CoinPickerBottomSheet.TAG)
        parent.childFragmentManager.executePendingTransactions()
        shadowOf(Looper.getMainLooper()).idle()
        val model = ViewModelProvider(parent)[SendViewModel::class.java]
        val field = SendViewModel::class.java.getDeclaredField("_utxoPool")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val pool = field.get(model) as MutableLiveData<List<Utxo>>
        pool.value = listOf(flagged, deposit)
        val recycler = sheet.requireView().findViewById<RecyclerView>(R.id.coinPickerList)
        val chips = awaitChips(recycler, minCount = 1)
        assertEquals(listOf("Cold storage (Bad Bank)"), chips.map { it.text.toString() })
        val warning = sheet.requireView().findViewById<View>(R.id.coinPickerCoSpendWarning)
        assertEquals(View.GONE, warning.visibility)
        val rows = viewsWithId(recycler, R.id.coinPickerRow)
        assertEquals(2, rows.size)
        rows[0].performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.GONE, warning.visibility)
        rows[1].performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE, warning.visibility)
    }

    private fun awaitChips(recycler: RecyclerView, minCount: Int = 2): List<Chip> {
        val deadline = System.currentTimeMillis() + 5_000
        var found = emptyList<Chip>()
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            recycler.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY),
            )
            recycler.layout(0, 0, 1080, 2400)
            found = chipsUnder(recycler)
            if (found.size >= minCount) return found
            Thread.sleep(20)
        }
        assertFalse("chips: ${found.map { it.text }}", found.isEmpty())
        return found
    }

    private fun viewsWithId(root: View, id: Int): List<View> {
        val found = ArrayList<View>()
        val stack = ArrayDeque<View>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val view = stack.removeFirst()
            if (view.id == id) found.add(view)
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) stack.add(view.getChildAt(i))
            }
        }
        return found
    }

    private fun chipsUnder(root: View): List<Chip> {
        val found = ArrayList<Chip>()
        val stack = ArrayDeque<View>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val view = stack.removeFirst()
            if (view is Chip) found.add(view)
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) stack.add(view.getChildAt(i))
            }
        }
        return found
    }

    private fun utxo(tx: String, vout: Int, pub: String) = Utxo(
        idx = "$tx:$vout",
        addr = "bc1q$tx",
        value = 50_000L,
        txHash = tx,
        txOutputN = vout,
        pubKey = pub,
    )

    private fun insert(tx: String, vout: Int, label: String, badbank: Int) {
        runBlocking {
            db.utxoLabelDao().upsert(
                UtxoLabel(
                    network = labels.currentNetwork(),
                    txid = tx,
                    vout = vout,
                    label = label,
                    origin = null,
                    createdAt = 1,
                    updatedAt = 1,
                    badbank = badbank,
                )
            )
        }
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
