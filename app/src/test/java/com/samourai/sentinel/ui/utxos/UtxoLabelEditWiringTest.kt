package com.samourai.sentinel.ui.utxos

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.R
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.core.access.AccessFactory
import com.samourai.sentinel.data.Utxo
import com.samourai.sentinel.data.db.SentinelCollectionStore
import com.samourai.sentinel.data.db.SentinelRoomDb
import com.samourai.sentinel.data.db.entity.UtxoLabel
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.data.repository.LabelRepository
import com.samourai.sentinel.data.whirlpool.WhirlpoolConfig
import com.samourai.wallet.util.XPUB
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.utils.PrefsUtil
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
import org.robolectric.shadows.ShadowDialog

/**
 * The UTXO details label dialog, on [UtxoDetailsActivity]. An unchanged
 * save must leave an automatic Bad Bank coin automatic. The Whirlpool
 * auto-labels setting does not hide coin labels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UtxoLabelEditWiringTest {

    private val direct = Executor { it.run() }
    private val auto = WhirlpoolConfig.DEFAULT.origin
    private val txid = "ee".repeat(32)
    private val idx = "$txid:0"

    private lateinit var prefs: PrefsUtil
    private lateinit var db: SentinelRoomDb
    private lateinit var labels: LabelRepository
    private var savedAdmitted = false
    private var controller: ActivityController<UtxoDetailsActivity>? = null

    @Before
    fun setUp() {
        if (GlobalContext.getOrNull() != null) stopKoin()
        val app = ApplicationProvider.getApplicationContext<Application>()
        File(app.filesDir, "app_wallet/dojo.payload").delete()
        File(app.filesDir, "app_wallet/dojo.payload.bak").delete()
        savedAdmitted = AccessFactory.getInstance(app).isSessionAdmitted
        val access = AccessFactory.getInstance(app)
        access.admitSession()
        prefs = PrefsUtil(app)
        prefs.clearAll()
        prefs.testnet = false
        prefs.enableTor = false
        prefs.pinHash = ""
        prefs.pinKeyMaterial = ""
        prefs.pinEnabled = false
        prefs.whirlpoolAutoLabels = false
        prefs.autoOriginHandEditsMigrated = true
        db = Room.inMemoryDatabaseBuilder(app, SentinelRoomDb::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(direct)
            .setTransactionExecutor(direct)
            .build()
        startKoin {
            androidContext(app)
            modules(
                module {
                    single { prefs }
                    single { access }
                    single { db }
                    single { db.utxoDao() }
                    single { db.utxoLabelDao() }
                    single { db.labelEntryDao() }
                    single { db.txDao() }
                    single { db.txEntropyDao() }
                    single { CollectionRepository() }
                    single { SentinelCollectionStore() }
                    single { ApiService() }
                    single { DojoUtility() }
                    single { LabelRepository() }
                },
            )
        }
        labels = org.koin.java.KoinJavaComponent.get(LabelRepository::class.java)
        forceMainnet()
        runBlocking {
            db.utxoDao().insert(
                Utxo(
                    idx = idx,
                    addr = "bc1qexample",
                    value = 50_000L,
                    txHash = txid,
                    txOutputN = 0,
                )
            )
        }
    }

    @After
    fun tearDown() {
        controller?.destroy()
        shadowOf(Looper.getMainLooper()).idle()
        if (this::db.isInitialized) db.close()
        if (GlobalContext.getOrNull() != null) stopKoin()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val access = AccessFactory.getInstance(app)
        if (savedAdmitted) access.admitSession() else {
            val field = AccessFactory::class.java.getDeclaredField("sessionAdmitted")
            field.isAccessible = true
            field.setBoolean(null, false)
        }
    }

    @Test
    fun unchangedSaveOnAnAutoBadBankCoinKeepsTheAutoOrigin() {
        insert("Bad Bank", auto, updatedAt = 10)
        val activity = open()
        awaitText(activity, "Bad Bank")
        val dialog = openDialog(activity)
        assertEquals("Bad Bank", editText(dialog).text.toString())
        val before = row()!!
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertStable(before)
        assertEquals(auto, row()!!.origin)
    }

    @Test
    fun whitespaceAroundTheShownCoinLabelIsANoOp() {
        insert("Bad Bank", auto, updatedAt = 10)
        val activity = open()
        awaitText(activity, "Bad Bank")
        val dialog = openDialog(activity)
        editText(dialog).setText("  Bad Bank  ")
        val before = row()!!
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertStable(before)
    }

    @Test
    fun aRealEditAndAVisibleClearWriteAManualCoinLabel() {
        insert("Bad Bank", auto, updatedAt = 10)
        val activity = open()
        awaitText(activity, "Bad Bank")
        var dialog = openDialog(activity)
        editText(dialog).setText("notes")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val edited = awaitRow { it?.label == "notes" && it.origin == null }
        assertEquals("notes", edited?.label)
        assertEquals(null, edited?.origin)

        awaitText(activity, "notes")
        dialog = openDialog(activity)
        editText(dialog).setText("")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val cleared = awaitRow { it?.label == "" && it.origin == null }
        assertEquals("", cleared?.label)
        assertEquals(null, cleared?.origin)
    }

    @Test
    fun manualUnchangedSaveDoesNotWrite() {
        insert("rent", null, updatedAt = 42)
        val activity = open()
        awaitText(activity, "rent")
        val dialog = openDialog(activity)
        assertEquals("rent", editText(dialog).text.toString())
        val before = row()!!
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertStable(before)
    }

    @Test
    fun anUneditedFlaggedBadBankCoinPrefillsEmptyAndEmptySaveIsANoOp() {
        insert("Bad Bank", auto, updatedAt = 10, badbank = 1)
        val activity = open()
        awaitText(activity, "Bad Bank")
        val dialog = openDialog(activity)
        assertEquals("", editText(dialog).text.toString())
        assertEquals(
            activity.getString(R.string.utxo_label_badbank_hint),
            editText(dialog).hint.toString(),
        )
        val before = row()!!
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertStable(before)
        val after = row()!!
        assertEquals(before.origin, after.origin)
        assertEquals(before.updatedAt, after.updatedAt)
        assertEquals(before.badbank, after.badbank)
    }

    @Test
    fun typingBadBankOnAnUneditedFlaggedCoinIsANoOp() {
        insert("Bad Bank", auto, updatedAt = 10, badbank = 1)
        val activity = open()
        awaitText(activity, "Bad Bank")
        val dialog = openDialog(activity)
        editText(dialog).setText("  Bad Bank  ")
        val before = row()!!
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertStable(before)
        val after = row()!!
        assertEquals(before.origin, after.origin)
        assertEquals(before.updatedAt, after.updatedAt)
        assertEquals(1, after.badbank)
    }

    @Test
    fun aSuffixedSaveOnAManualBadBankCoinIsANoOp() {
        insert("Cold storage", null, updatedAt = 42, badbank = 1)
        val activity = open()
        awaitText(activity, "Cold storage (Bad Bank)")
        val dialog = openDialog(activity)
        assertEquals("Cold storage", editText(dialog).text.toString())
        editText(dialog).setText("Cold storage (Bad Bank)")
        val before = row()!!
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertStable(before)
        val after = row()!!
        assertEquals(before.origin, after.origin)
        assertEquals(before.updatedAt, after.updatedAt)
        assertEquals(before.badbank, after.badbank)
    }

    @Test
    fun savingAndClearingAFlaggedCoinKeepsTheFlagAndTheSuffix() {
        insert("Bad Bank", auto, updatedAt = 10, badbank = 1)
        val activity = open()
        awaitText(activity, "Bad Bank")
        var dialog = openDialog(activity)
        editText(dialog).setText("Vault (Bad Bank)")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val edited = awaitRow { it?.label == "Vault" && it.origin == null && it.badbank == 1 }
        assertEquals("Vault", edited?.label)
        assertEquals(null, edited?.origin)
        assertEquals(1, edited?.badbank)
        awaitText(activity, "Vault (Bad Bank)")

        dialog = openDialog(activity)
        assertEquals("Vault", editText(dialog).text.toString())
        editText(dialog).setText("")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val cleared = awaitRow { it?.label == "" && it.origin == null && it.badbank == 1 }
        assertEquals("", cleared?.label)
        assertEquals(1, cleared?.badbank)
        awaitText(activity, "Bad Bank")
    }

    @Test
    fun aHandTypedSuffixOnADepositCoinIsStoredVerbatim() {
        insert("x (Bad Bank)", null, updatedAt = 7, badbank = 0)
        val activity = open()
        awaitText(activity, "x (Bad Bank)")
        val dialog = openDialog(activity)
        assertEquals("x (Bad Bank)", editText(dialog).text.toString())
        val before = row()!!
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertStable(before)
        val after = row()!!
        assertEquals("x (Bad Bank)", after.label)
        assertEquals(before.origin, after.origin)
        assertEquals(before.updatedAt, after.updatedAt)
        assertEquals(0, after.badbank)
    }

    @Test
    fun anAccountMatchedCoinWithNoRowSavesTheUserPartAndTheFlag() {
        val pub = accountXpub(WhirlpoolConfig.DEFAULT.badbankAccount)
        runBlocking {
            db.utxoDao().insert(
                Utxo(
                    idx = idx,
                    addr = "bc1qexample",
                    value = 50_000L,
                    txHash = txid,
                    txOutputN = 0,
                    pubKey = pub,
                )
            )
        }
        val activity = open()
        awaitText(activity, "Bad Bank")
        val dialog = openDialog(activity)
        assertEquals("", editText(dialog).text.toString())
        editText(dialog).setText("Vault")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val written = awaitRow { it?.label == "Vault" && it.badbank == 1 && it.origin == null }
        assertEquals("Vault", written?.label)
        assertEquals(1, written?.badbank)
        awaitText(activity, "Vault (Bad Bank)")
    }

    @Test
    fun theAutoLabelsToggleDoesNotHideACoinLabel() {
        prefs.whirlpoolAutoLabels = false
        insert("Bad Bank", auto)
        val activity = open()
        awaitText(activity, "Bad Bank")
        assertFalse(activity.isFinishing)
    }

    private fun open(): UtxoDetailsActivity {
        val intent = Intent().putExtra("idx", idx)
        val host = Robolectric.buildActivity(UtxoDetailsActivity::class.java, intent)
        controller = host
        val activity = host.setup().get()
        assertFalse(activity.isFinishing)
        return activity
    }

    private fun openDialog(activity: UtxoDetailsActivity): AlertDialog {
        activity.findViewById<View>(R.id.utxo_details_label).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog()
        assertTrue(dialog is AlertDialog && dialog.isShowing)
        return dialog as AlertDialog
    }

    private fun editText(dialog: AlertDialog): EditText {
        val found = ArrayList<EditText>()
        val stack = ArrayDeque<View>()
        stack.add(dialog.window!!.decorView)
        while (stack.isNotEmpty()) {
            val view = stack.removeFirst()
            if (view is EditText) found.add(view)
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) stack.add(view.getChildAt(i))
            }
        }
        return found.single()
    }

    private fun insert(label: String, origin: String?, updatedAt: Long = 1, badbank: Int = 0) {
        forceMainnet()
        runBlocking {
            db.utxoLabelDao().upsert(
                UtxoLabel(
                    network = labels.currentNetwork(),
                    txid = txid,
                    vout = 0,
                    label = label,
                    origin = origin,
                    createdAt = updatedAt,
                    updatedAt = updatedAt,
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

    private fun row(): UtxoLabel? = runBlocking {
        db.utxoLabelDao().find(labels.currentNetwork(), txid, 0)
    }

    private fun awaitText(activity: UtxoDetailsActivity, expected: String) {
        val deadline = System.currentTimeMillis() + 5_000
        val view = activity.findViewById<TextView>(R.id.utxo_details_label)
        while (view.text.toString() != expected && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertEquals(expected, view.text.toString())
    }

    private fun awaitRow(predicate: (UtxoLabel?) -> Boolean): UtxoLabel? {
        val deadline = System.currentTimeMillis() + 5_000
        var current: UtxoLabel? = null
        while (System.currentTimeMillis() < deadline) {
            current = row()
            if (predicate(current)) return current
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        return current
    }

    private fun assertStable(before: UtxoLabel) {
        val deadline = System.currentTimeMillis() + 400
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
            assertEquals(before, row())
        }
    }

    private fun forceMainnet() {
        prefs.testnet = false
        val field = SentinelState::class.java.getDeclaredField("networkParams")
        field.isAccessible = true
        field.set(null, MainNetParams.get())
        assertFalse(SentinelState.isTestNet())
    }
}
