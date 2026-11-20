package com.samourai.sentinel.ui.collectionDetails.transactions

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.samourai.sentinel.R
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.Out
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.Tx
import com.samourai.sentinel.data.Xpub
import com.samourai.sentinel.data.db.SentinelCollectionStore
import com.samourai.sentinel.data.db.SentinelRoomDb
import com.samourai.sentinel.data.db.entity.LabelEntry
import com.samourai.sentinel.data.db.entity.UtxoLabel
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.data.repository.ExchangeRateRepository
import com.samourai.sentinel.data.repository.LabelRepository
import com.samourai.sentinel.data.whirlpool.WhirlpoolConfig
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.fragments.TransactionsDetailsBottomSheet
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.views.InputBottomSheet
import com.samourai.wallet.util.XPUB
import java.io.File
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * The transactions list and the details sheet, on the production
 * fragment and sheet. Roles come from the xpub account, the
 * auto-labels toggle only changes what is drawn, and Save goes
 * through the real editor result listener.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WhirlpoolTxLabelWiringTest {

    private val direct = Executor { it.run() }
    private val auto = WhirlpoolConfig.DEFAULT.origin
    private val tx0 = "aa".repeat(32)
    private val manualTx = "bb".repeat(32)
    private val mixTx = "cc".repeat(32)
    private val importedTx = "dd".repeat(32)

    private lateinit var prefs: PrefsUtil
    private lateinit var db: SentinelRoomDb
    private lateinit var labels: LabelRepository
    private lateinit var collections: CollectionRepository
    private var controller: ActivityController<out AppCompatActivity>? = null

    @Before
    fun setUp() {
        if (GlobalContext.getOrNull() != null) stopKoin()
        val app = ApplicationProvider.getApplicationContext<Application>()
        File(app.filesDir, "app_wallet/dojo.payload").delete()
        File(app.filesDir, "app_wallet/dojo.payload.bak").delete()
        prefs = PrefsUtil(app)
        prefs.clearAll()
        prefs.testnet = false
        prefs.enableTor = false
        prefs.streetMode = false
        prefs.displaySecure = false
        prefs.pinEnabled = false
        prefs.whirlpoolAutoLabels = true
        prefs.autoOriginHandEditsMigrated = true
        db = Room.inMemoryDatabaseBuilder(app, SentinelRoomDb::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(direct)
            .setTransactionExecutor(direct)
            .build()
        collections = CollectionRepository()
        startKoin {
            androidContext(app)
            modules(
                module {
                    single { prefs }
                    single { db }
                    single { db.txDao() }
                    single { db.utxoDao() }
                    single { db.utxoLabelDao() }
                    single { db.labelEntryDao() }
                    single { db.txEntropyDao() }
                    single { collections }
                    single { SentinelCollectionStore() }
                    single { ApiService() }
                    single { DojoUtility() }
                    single { LabelRepository() }
                    single { ExchangeRateRepository() }
                },
            )
        }
        labels = org.koin.java.KoinJavaComponent.get(LabelRepository::class.java)
        forceMainnet()
    }

    @After
    fun tearDown() {
        controller?.destroy()
        shadowOf(Looper.getMainLooper()).idle()
        if (this::db.isInitialized) db.close()
        if (GlobalContext.getOrNull() != null) stopKoin()
    }

    @Test
    fun listUsesTheAccountRoleAndTheToggleDoesNotRewriteRows() {
        val cfg = WhirlpoolConfig.DEFAULT
        val deposit = accountXpub(cfg.depositAccount)
        val premix = accountXpub(cfg.premixAccount)
        val badBank = accountXpub(cfg.badbankAccount)
        val postmix = accountXpub(cfg.postmixAccount)
        val address = "bc1qaddressfallback"
        val collection = PubKeyCollection(
            collectionLabel = "Whirlpool",
            pubs = arrayListOf(
                PubKeyModel(deposit, "Deposit", AddressTypes.BIP84),
                PubKeyModel(premix, "Savings", AddressTypes.BIP84),
                PubKeyModel(badBank, "Checking", AddressTypes.BIP84),
                PubKeyModel(postmix, "Bad Bank", AddressTypes.BIP84),
                PubKeyModel(address, "Premix", AddressTypes.ADDRESS),
            ),
        )
        collections.addNew(collection)
        val id = collection.id
        runBlocking {
            db.txDao().insertAll(
                listOf(
                    tx("$tx0-$id-1", 800, deposit, id, -50_000_000L),
                    tx("$tx0-$id-2", 700, premix, id, 12_500_000L),
                    tx("$tx0-$id-3", 600, badBank, id, 180_000L),
                    tx("$tx0-$id-4", 500, postmix, id, 1L),
                    tx("$tx0-$id-5", 400, address, id, 12_500_000L),
                    tx("$manualTx-$id-1", 300, premix, id, 12_500_000L),
                    tx("$mixTx-$id-1", 200, postmix, id, 2_500_000L),
                    tx("$importedTx-$id-1", 100, deposit, id, 1L),
                )
            )
        }
        insertTx(tx0, "Whirlpool Tx0", auto)
        insertTx(manualTx, "Whirlpool Tx0", null)
        insertTx(mixTx, "Whirlpool Mix", auto)
        insertTx(importedTx, "invoice", "sparrow")

        val activity = host()
        val fragment = TransactionsListFragment.newInstance(0, collection)
        activity.supportFragmentManager.beginTransaction()
            .add(android.R.id.content, fragment, "txs")
            .commitNow()
        val adapter = fragment.requireView()
            .findViewById<RecyclerView>(R.id.transactionsRecycler).adapter as TransactionAdapter
        awaitUntil { adapter.itemCount == 8 && adapter.txLabels.size == 4 }

        val shown = bound(adapter)
        assertEquals("Whirlpool Tx0", text(shown, "$tx0-$id-1"))
        // Premix xpub renamed "Savings" still shows the premix perspective.
        assertEquals("Whirlpool Premix", text(shown, "$tx0-$id-2"))
        // Bad Bank xpub renamed "Checking".
        assertEquals("Bad Bank", text(shown, "$tx0-$id-3"))
        // Postmix labeled "Bad Bank" is not the badbank account.
        assertEquals("Whirlpool Tx0", text(shown, "$tx0-$id-4"))
        // A single address keeps the label fallback.
        assertEquals("Whirlpool Premix", text(shown, "$tx0-$id-5"))
        assertEquals("Whirlpool Tx0", text(shown, "$manualTx-$id-1"))
        assertEquals("Whirlpool Mix", text(shown, "$mixTx-$id-1"))
        assertEquals("invoice", text(shown, "$importedTx-$id-1"))

        val addressPub = collection.pubs.single { it.pubKey == address }
        addressPub.label = "Bad Bank"
        fragment.onResume()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(TxPerspectiveLabel.Role.BAD_BANK, adapter.pubRoles[address])

        prefs.whirlpoolAutoLabels = false
        fragment.onResume()
        shadowOf(Looper.getMainLooper()).idle()
        val hidden = bound(adapter)
        listOf("$tx0-$id-1", "$tx0-$id-2", "$tx0-$id-3", "$tx0-$id-4", "$tx0-$id-5", "$mixTx-$id-1")
            .forEach { hash -> assertEquals(View.GONE, hidden.getValue(hash).visibility) }
        assertEquals("Whirlpool Tx0", text(hidden, "$manualTx-$id-1"))
        assertEquals("invoice", text(hidden, "$importedTx-$id-1"))

        val stored = txRow(tx0)!!
        assertEquals("Whirlpool Tx0", stored.label)
        assertEquals(auto, stored.origin)

        prefs.whirlpoolAutoLabels = true
        fragment.onResume()
        shadowOf(Looper.getMainLooper()).idle()
        val restored = bound(adapter)
        assertEquals("Whirlpool Premix", text(restored, "$tx0-$id-2"))
        assertEquals("Bad Bank", text(restored, "$tx0-$id-3"))
        assertEquals("Whirlpool Mix", text(restored, "$mixTx-$id-1"))
        assertEquals(auto, txRow(tx0)!!.origin)
        assertEquals(auto, txRow(mixTx)!!.origin)
        assertEquals(null, txRow(manualTx)!!.origin)
    }

    @Test
    fun badBankEditorPrefillsTheDisplayedTextAndUnchangedSaveKeepsTheAutoOrigin() {
        insertTx(tx0, "Whirlpool Tx0", auto, updatedAt = 10)
        val sheet = show(TxPerspectiveLabel.Role.BAD_BANK)
        awaitLabel(sheet, "Bad Bank")
        val editor = openEditor(sheet)
        assertEquals("Bad Bank", editorText(editor))
        assertEquals("Bad Bank", editor.arguments?.getString(InputBottomSheet.ARG_VALUE))
        val before = txRow(tx0)!!
        save(editor, "  Bad Bank  ")
        assertStable(before)
        save(openEditor(sheet), "Whirlpool Tx0")
        assertStable(before)
        assertEquals(auto, txRow(tx0)!!.origin)
    }

    @Test
    fun premixAndDepositPrefillWhatThatAccountShows() {
        insertTx(tx0, "Whirlpool Tx0", auto)
        val premix = show(TxPerspectiveLabel.Role.PREMIX)
        awaitLabel(premix, "Whirlpool Premix")
        assertEquals("Whirlpool Premix", editorText(openEditor(premix)))
        controller?.destroy()
        controller = null
        val deposit = show(TxPerspectiveLabel.Role.DEPOSIT)
        awaitLabel(deposit, "Whirlpool Tx0")
        assertEquals("Whirlpool Tx0", editorText(openEditor(deposit)))
    }

    @Test
    fun aRealEditAndAVisibleClearWriteAManualLabel() {
        insertTx(tx0, "Whirlpool Tx0", auto, updatedAt = 10)
        val sheet = show(TxPerspectiveLabel.Role.BAD_BANK)
        awaitLabel(sheet, "Bad Bank")
        save(openEditor(sheet), "rent")
        val edited = awaitTx { it != null && it.origin == null && it.label == "rent" }
        assertEquals("rent", edited?.label)
        assertEquals(null, edited?.origin)
        awaitLabel(sheet, "rent")

        save(openEditor(sheet), "")
        val cleared = awaitTx { it != null && it.label == "" && it.origin == null }
        assertEquals("", cleared?.label)
        assertEquals(null, cleared?.origin)
    }

    @Test
    fun manualUnchangedSaveDoesNotWrite() {
        insertTx(manualTx, "rent", null, updatedAt = 42)
        val sheet = show(TxPerspectiveLabel.Role.DEPOSIT, bareTxid = manualTx)
        awaitLabel(sheet, "rent")
        val editor = openEditor(sheet)
        assertEquals("rent", editorText(editor))
        val before = txRow(manualTx)!!
        save(editor)
        assertStable(before)
        assertEquals(42L, txRow(manualTx)!!.updatedAt)
    }

    @Test
    fun toggleOffHidesTheAutoLabelAndEmptySaveIsANoOp() {
        insertTx(tx0, "Whirlpool Tx0", auto, updatedAt = 10)
        insertTx(manualTx, "rent", null)
        insertTx(importedTx, "invoice", "sparrow")
        prefs.whirlpoolAutoLabels = false
        val sheet = show(TxPerspectiveLabel.Role.BAD_BANK)
        awaitLabel(sheet, "Add label")
        val editor = openEditor(sheet)
        assertEquals("", editorText(editor))
        assertEquals("", editor.arguments?.getString(InputBottomSheet.ARG_VALUE))
        val before = txRow(tx0)!!
        save(editor, "")
        assertStable(before)
        save(openEditor(sheet), "   ")
        assertStable(before)
        assertEquals(auto, txRow(tx0)!!.origin)
    }

    @Test
    fun toggleOffTypedTextWritesAManualLabelIncludingTheStoredAutoText() {
        insertTx(tx0, "Whirlpool Tx0", auto)
        prefs.whirlpoolAutoLabels = false
        val sheet = show(TxPerspectiveLabel.Role.PREMIX)
        awaitLabel(sheet, "Add label")
        save(openEditor(sheet), "Whirlpool Tx0")
        val written = awaitTx { it != null && it.origin == null && it.label == "Whirlpool Tx0" }
        assertEquals(null, written?.origin)
        assertEquals("Whirlpool Tx0", written?.label)
    }

    @Test
    fun resumeAfterTurningTheSettingOffHidesAndOnRestoresWithoutAResync() {
        insertTx(tx0, "Whirlpool Tx0", auto, updatedAt = 10)
        val (host, sheet) = showHost(TxPerspectiveLabel.Role.BAD_BANK)
        awaitLabel(sheet, "Bad Bank")
        prefs.whirlpoolAutoLabels = false
        host.pause().resume()
        shadowOf(Looper.getMainLooper()).idle()
        awaitLabel(sheet, "Add label")
        assertStable(txRow(tx0)!!)
        prefs.whirlpoolAutoLabels = true
        host.pause().resume()
        shadowOf(Looper.getMainLooper()).idle()
        awaitLabel(sheet, "Bad Bank")
        assertEquals(auto, txRow(tx0)!!.origin)
        assertEquals(10L, txRow(tx0)!!.updatedAt)
    }

    @Test
    fun hiddenAutoTransactionLabelLeavesTheBadBankCoinChip() {
        insertTx(tx0, "Whirlpool Tx0", auto)
        runBlocking {
            db.utxoLabelDao().upsert(
                UtxoLabel(
                    network = labels.currentNetwork(),
                    txid = tx0,
                    vout = 5,
                    label = "Bad Bank",
                    origin = auto,
                    createdAt = 1,
                    updatedAt = 1,
                )
            )
        }
        prefs.whirlpoolAutoLabels = false
        val sheet = show(TxPerspectiveLabel.Role.BAD_BANK, secure = false, withOutput = true)
        awaitLabel(sheet, "Add label")
        awaitUntil {
            sheet.requireView().findViewById<View>(R.id.txDetailsUtxoLabelsRow).visibility == View.VISIBLE
        }
        val chips = sheet.requireView().findViewById<ChipGroup>(R.id.txDetailsUtxoLabelsChips)
        assertEquals(1, chips.childCount)
        assertEquals("Bad Bank", (chips.getChildAt(0) as Chip).text.toString())
        assertFalse((chips.getChildAt(0) as Chip).isClickable)
        assertEquals(auto, runBlocking {
            db.utxoLabelDao().find(labels.currentNetwork(), tx0, 5)
        }!!.origin)
    }

    @Test
    fun aSpentBadBankOutputWithNoRowShowsAReadOnlyChip() {
        val sheet = show(
            TxPerspectiveLabel.Role.DEPOSIT,
            secure = false,
            withOutput = true,
            outputXpub = accountXpub(WhirlpoolConfig.DEFAULT.badbankAccount),
        )
        awaitUntil {
            sheet.requireView().findViewById<View>(R.id.txDetailsUtxoLabelsRow).visibility == View.VISIBLE
        }
        val chips = sheet.requireView().findViewById<ChipGroup>(R.id.txDetailsUtxoLabelsChips)
        assertEquals(1, chips.childCount)
        val chip = chips.getChildAt(0) as Chip
        assertEquals("Bad Bank", chip.text.toString())
        assertFalse(chip.isClickable)
    }

    @Test
    fun aFlaggedUserPartComposesOnTheDetailsChip() {
        runBlocking {
            db.utxoLabelDao().upsert(
                UtxoLabel(
                    network = labels.currentNetwork(),
                    txid = tx0,
                    vout = 5,
                    label = "Cold storage",
                    origin = null,
                    createdAt = 1,
                    updatedAt = 1,
                    badbank = 1,
                )
            )
        }
        val sheet = show(TxPerspectiveLabel.Role.OTHER, secure = false, withOutput = true)
        awaitUntil {
            val chips = sheet.requireView().findViewById<ChipGroup>(R.id.txDetailsUtxoLabelsChips)
            chips.childCount == 1 && (chips.getChildAt(0) as Chip).text.toString() == "Cold storage (Bad Bank)"
        }
        val chip = sheet.requireView().findViewById<ChipGroup>(R.id.txDetailsUtxoLabelsChips).getChildAt(0) as Chip
        assertFalse(chip.isClickable)
    }

    @Test
    fun emptySaveOnATransactionWithNoLabelStillWritesAHandClear() {
        val sheet = show(TxPerspectiveLabel.Role.OTHER, bareTxid = manualTx)
        awaitLabel(sheet, "Add label")
        save(openEditor(sheet), "")
        val row = awaitTx { it != null && it.label == "" }
        assertEquals("", row?.label)
        assertEquals(null, row?.origin)
    }

    private fun show(
        role: TxPerspectiveLabel.Role,
        bareTxid: String = tx0,
        secure: Boolean = true,
        withOutput: Boolean = false,
        outputXpub: String? = null,
    ): TransactionsDetailsBottomSheet = showHost(role, bareTxid, secure, withOutput, outputXpub).second

    private fun showHost(
        role: TxPerspectiveLabel.Role,
        bareTxid: String = tx0,
        secure: Boolean = true,
        withOutput: Boolean = false,
        outputXpub: String? = null,
    ): Pair<ActivityController<AppCompatActivity>, TransactionsDetailsBottomSheet> {
        controller?.destroy()
        val host = Robolectric.buildActivity(AppCompatActivity::class.java)
        host.get().setTheme(R.style.AppThemeV3)
        val activity = host.setup().get()
        controller = host
        val output = if (withOutput) {
            listOf(
                Out(
                    n = 5,
                    value = 180_000L,
                    addr = "bc1qchange",
                    xpub = outputXpub?.let { Xpub(it, null) },
                )
            )
        } else {
            emptyList()
        }
        val sheet = TransactionsDetailsBottomSheet.newInstance(
            Tx(
                hash = "$bareTxid-col-1",
                time = 1_700_000_000L,
                associatedPubKey = "zpub",
                collectionId = "col",
                version = 1,
                locktime = 0,
                result = -50_000_000L,
                inputs = emptyList(),
                out = output,
                block_height = 800_000L,
                confirmations = 6,
            ),
            secure,
            role,
        )
        sheet.show(activity.supportFragmentManager, "tx")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(sheet.isAdded)
        return host to sheet
    }

    private fun openEditor(sheet: TransactionsDetailsBottomSheet): InputBottomSheet {
        sheet.requireView().findViewById<View>(R.id.txDetailsLabel).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        return sheet.childFragmentManager.fragments
            .filterIsInstance<InputBottomSheet>()
            .single { it.isAdded }
    }

    private fun editorText(editor: InputBottomSheet): String =
        editor.requireView().findViewById<TextView>(R.id.bottomSheetInputField).text.toString()

    private fun save(editor: InputBottomSheet, replacement: String? = null) {
        if (replacement != null) {
            editor.requireView().findViewById<TextView>(R.id.bottomSheetInputField).text = replacement
        }
        editor.requireView().findViewById<View>(R.id.bottomSheetConfirmPositiveBtn).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun awaitLabel(sheet: TransactionsDetailsBottomSheet, expected: String) {
        awaitUntil {
            sheet.view?.findViewById<TextView>(R.id.txDetailsLabel)?.text?.toString() == expected
        }
        assertEquals(expected, sheet.requireView().findViewById<TextView>(R.id.txDetailsLabel).text.toString())
    }

    private data class Bound(val visibility: Int, val text: String)

    private fun bound(adapter: TransactionAdapter): Map<String, Bound> {
        val parent = FrameLayout(controller!!.get())
        return (0 until adapter.itemCount).associate { index ->
            val item = itemAt(adapter, index)
            val holder = adapter.onCreateViewHolder(parent, 0)
            adapter.onBindViewHolder(holder, index)
            item.hash to Bound(holder.txLabel.visibility, holder.txLabel.text.toString())
        }
    }

    private fun itemAt(adapter: TransactionAdapter, index: Int): Tx {
        var type: Class<*>? = adapter.javaClass
        while (type != null) {
            val method = type.declaredMethods.firstOrNull {
                it.name == "getItem" && it.parameterTypes.size == 1
            }
            if (method != null) {
                method.isAccessible = true
                return method.invoke(adapter, index) as Tx
            }
            type = type.superclass
        }
        error("TransactionAdapter.getItem")
    }

    private fun text(rows: Map<String, Bound>, hash: String): String {
        val row = rows.getValue(hash)
        assertEquals(View.VISIBLE, row.visibility)
        return row.text
    }

    private fun host(): AppCompatActivity {
        val host = Robolectric.buildActivity(AppCompatActivity::class.java)
        host.get().setTheme(R.style.AppThemeV3)
        controller = host
        return host.setup().get()
    }

    private fun insertTx(ref: String, label: String, origin: String?, updatedAt: Long = 1) {
        forceMainnet()
        runBlocking {
            db.labelEntryDao().upsert(
                LabelEntry(
                    network = labels.currentNetwork(),
                    type = "tx",
                    ref = ref,
                    label = label,
                    origin = origin,
                    createdAt = updatedAt,
                    updatedAt = updatedAt,
                )
            )
        }
    }

    private fun txRow(ref: String): LabelEntry? = runBlocking {
        db.labelEntryDao().find(labels.currentNetwork(), "tx", ref)
    }

    private fun awaitTx(predicate: (LabelEntry?) -> Boolean): LabelEntry? {
        val deadline = System.currentTimeMillis() + 5_000
        var row: LabelEntry? = null
        while (System.currentTimeMillis() < deadline) {
            row = txRow(tx0) ?: txRow(manualTx)
            if (predicate(row) || predicate(txRow(tx0)) || predicate(txRow(manualTx))) {
                return listOf(txRow(tx0), txRow(manualTx)).firstOrNull { predicate(it) }
            }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        return row
    }

    private fun assertStable(before: LabelEntry) {
        val deadline = System.currentTimeMillis() + 400
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
            assertEquals(before, txRow(before.ref))
        }
    }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 8_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertTrue(condition())
    }

    private fun tx(hash: String, time: Long, pub: String, collectionId: String, result: Long) = Tx(
        hash = hash,
        time = time,
        associatedPubKey = pub,
        collectionId = collectionId,
        version = 1,
        locktime = 0,
        result = result,
        inputs = emptyList(),
        out = emptyList(),
        block_height = 800_000L,
        confirmations = 6,
    )

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

    private fun forceMainnet() {
        prefs.testnet = false
        val field = SentinelState::class.java.getDeclaredField("networkParams")
        field.isAccessible = true
        field.set(null, MainNetParams.get())
        assertFalse(SentinelState.isTestNet())
    }
}
