package com.samourai.sentinel.ui.collectionDetails.transactions

import android.app.Application
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.R
import com.samourai.sentinel.data.Tx
import com.samourai.sentinel.data.repository.TxLabelDetail
import com.samourai.sentinel.data.whirlpool.WhirlpoolConfig
import com.samourai.sentinel.ui.fragments.TransactionsDetailsBottomSheet
import com.samourai.sentinel.ui.utils.PrefsUtil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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

/**
 * Binds real [TransactionAdapter] rows. One Tx0 txid is stored under
 * Deposit, Premix, Bad Bank, and Postmix (the All tab shows every row;
 * a per-account tab shows one). The remix row's amount line must stay
 * the upstream render: "Whirlpool remix", cyan, repeat icon.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TransactionAdapterPerspectiveLabelTest {

    private val txid = "ab".repeat(32)
    private val remixTxid = "cd".repeat(32)
    private val manualTxid = "ef".repeat(32)
    private val mixTxid = "12".repeat(32)
    private val collectionId = "col-1"

    private val depositPub = "zpub-deposit"
    private val premixPub = "zpub-premix"
    private val badBankPub = "zpub-badbank"
    private val postmixPub = "zpub-postmix"

    private val autoOrigin = WhirlpoolConfig.DEFAULT.origin
    private val tx0 = TxLabelDetail("Whirlpool Tx0", autoOrigin)

    private val roles = mapOf(
        depositPub to TxPerspectiveLabel.Role.DEPOSIT,
        premixPub to TxPerspectiveLabel.Role.PREMIX,
        badBankPub to TxPerspectiveLabel.Role.BAD_BANK,
        postmixPub to TxPerspectiveLabel.Role.OTHER,
    )

    private lateinit var prefs: PrefsUtil

    @Before
    fun setUp() {
        if (GlobalContext.getOrNull() != null) stopKoin()
        val app = ApplicationProvider.getApplicationContext<Application>()
        prefs = PrefsUtil(app)
        prefs.streetMode = false
        prefs.whirlpoolAutoLabels = true
        startKoin {
            androidContext(app)
            modules(
                module {
                    single { prefs }
                },
            )
        }
    }

    @After
    fun tearDown() {
        if (this::prefs.isInitialized) {
            prefs.streetMode = false
            prefs.whirlpoolAutoLabels = false
        }
        if (GlobalContext.getOrNull() != null) stopKoin()
    }

    @Test
    fun allTabShowsEachAccountPerspectiveForTheSameTxid() {
        val holders = bind(
            listOf(
                row(txid, 1, depositPub, result = -50_000_000L),
                row(txid, 2, premixPub, result = 12_500_000L),
                row(txid, 3, badBankPub, result = 180_000L),
                row(txid, 4, postmixPub, result = 1L),
                row(remixTxid, 1, postmixPub, result = 0L),
            ),
            labels = mapOf(txid to tx0),
        )

        assertEquals("Whirlpool Tx0", holders[0].txLabel.text.toString())
        assertEquals(View.VISIBLE, holders[0].txLabel.visibility)
        assertEquals("Whirlpool Premix", holders[1].txLabel.text.toString())
        assertEquals("Bad Bank", holders[2].txLabel.text.toString())
        // Postmix is not a Tx0 perspective. Stored text stays.
        assertEquals("Whirlpool Tx0", holders[3].txLabel.text.toString())

        assertRemixUnchanged(holders[4])
        assertEquals(View.GONE, holders[4].txLabel.visibility)

        // Amount lines of the Tx0 rows are ordinary BTC amounts.
        holders.take(4).forEach { holder ->
            assertNotEquals("Whirlpool remix", holder.txAmount.text.toString())
        }
    }

    @Test
    fun depositTabShowsWhirlpoolTx0() {
        val holder = bindOne(row(txid, 1, depositPub, result = -50_000_000L))
        assertEquals("Whirlpool Tx0", holder.txLabel.text.toString())
        assertNotEquals("Whirlpool remix", holder.txAmount.text.toString())
    }

    @Test
    fun premixTabShowsWhirlpoolPremix() {
        val holder = bindOne(row(txid, 2, premixPub, result = 12_500_000L))
        assertEquals("Whirlpool Premix", holder.txLabel.text.toString())
    }

    @Test
    fun badBankTabShowsBadBank() {
        val holder = bindOne(row(txid, 3, badBankPub, result = 180_000L))
        assertEquals("Bad Bank", holder.txLabel.text.toString())
    }

    @Test
    fun manualTx0LabelOnPremixStaysTyped() {
        val holder = bind(
            listOf(row(manualTxid, 1, premixPub, result = 12_500_000L)),
            labels = mapOf(manualTxid to TxLabelDetail("Whirlpool Tx0", null)),
        ).single()
        assertEquals("Whirlpool Tx0", holder.txLabel.text.toString())
    }

    @Test
    fun nonTx0AutoLabelStaysWhirlpoolMix() {
        val holder = bind(
            listOf(row(mixTxid, 1, premixPub, result = 2_500_000L)),
            labels = mapOf(mixTxid to TxLabelDetail("Whirlpool Mix", autoOrigin)),
        ).single()
        assertEquals("Whirlpool Mix", holder.txLabel.text.toString())
    }

    @Test
    fun postmixTabShowsWhirlpoolMix() {
        val holder = bind(
            listOf(row(mixTxid, 1, postmixPub, result = 2_500_000L)),
            labels = mapOf(mixTxid to TxLabelDetail("Whirlpool Mix", autoOrigin)),
        ).single()
        assertEquals("Whirlpool Mix", holder.txLabel.text.toString())
        assertEquals(View.VISIBLE, holder.txLabel.visibility)
        assertNotEquals("Whirlpool remix", holder.txAmount.text.toString())
    }

    @Test
    fun whirlpoolMixIsNotRemappedOnTx0Tabs() {
        val holders = bind(
            listOf(
                row(mixTxid, 1, depositPub, result = -2_500_444L),
                row(mixTxid, 2, premixPub, result = -2_500_444L),
                row(mixTxid, 3, badBankPub, result = 1L),
                row(mixTxid, 4, postmixPub, result = 2_500_000L),
            ),
            labels = mapOf(mixTxid to TxLabelDetail("Whirlpool Mix", autoOrigin)),
        )
        holders.forEach { holder ->
            assertEquals("Whirlpool Mix", holder.txLabel.text.toString())
            assertNotEquals("Whirlpool remix", holder.txAmount.text.toString())
        }
    }

    @Test
    fun remixRowAmountIsUnchanged() {
        val holder = bind(
            listOf(row(remixTxid, 1, postmixPub, result = 0L)),
            labels = mapOf(txid to tx0),
        ).single()
        assertRemixUnchanged(holder)
        assertEquals(View.GONE, holder.txLabel.visibility)
    }

    @Test
    fun toggleOffHidesAutoLabelsOnEveryRoleAndKeepsManualAndImported() {
        prefs.whirlpoolAutoLabels = false
        val importedTxid = "34".repeat(32)
        val holders = bind(
            listOf(
                row(txid, 1, depositPub, result = -50_000_000L),
                row(txid, 2, premixPub, result = 12_500_000L),
                row(txid, 3, badBankPub, result = 180_000L),
                row(txid, 4, postmixPub, result = 1L),
                row(mixTxid, 1, premixPub, result = 2_500_000L),
                row(manualTxid, 1, premixPub, result = 12_500_000L),
                row(importedTxid, 1, badBankPub, result = 1L),
            ),
            labels = mapOf(
                txid to tx0,
                mixTxid to TxLabelDetail("Whirlpool Mix", autoOrigin),
                manualTxid to TxLabelDetail("Whirlpool Tx0", null),
                importedTxid to TxLabelDetail("invoice", "sparrow"),
            ),
        )
        holders.take(5).forEach { holder ->
            assertEquals(View.GONE, holder.txLabel.visibility)
        }
        assertEquals(View.VISIBLE, holders[5].txLabel.visibility)
        assertEquals("Whirlpool Tx0", holders[5].txLabel.text.toString())
        assertEquals(View.VISIBLE, holders[6].txLabel.visibility)
        assertEquals("invoice", holders[6].txLabel.text.toString())
    }

    @Test
    fun toggleBackOnRestoresThePerspectiveWithoutAnotherLabelMap() {
        val txs = listOf(
            row(txid, 1, depositPub, result = -50_000_000L),
            row(txid, 2, premixPub, result = 12_500_000L),
            row(txid, 3, badBankPub, result = 180_000L),
            row(mixTxid, 1, postmixPub, result = 2_500_000L),
        )
        val labels = mapOf(
            txid to tx0,
            mixTxid to TxLabelDetail("Whirlpool Mix", autoOrigin),
        )
        prefs.whirlpoolAutoLabels = false
        bind(txs, labels).forEach { holder ->
            assertEquals(View.GONE, holder.txLabel.visibility)
        }
        prefs.whirlpoolAutoLabels = true
        val shown = bind(txs, labels)
        assertEquals("Whirlpool Tx0", shown[0].txLabel.text.toString())
        assertEquals("Whirlpool Premix", shown[1].txLabel.text.toString())
        assertEquals("Bad Bank", shown[2].txLabel.text.toString())
        assertEquals("Whirlpool Mix", shown[3].txLabel.text.toString())
    }

    @Test
    fun streetModeHidesThePerspectiveLabel() {
        prefs.streetMode = true
        val holder = bindOne(row(txid, 2, premixPub, result = 12_500_000L))
        assertEquals(View.GONE, holder.txLabel.visibility)
    }

    @Test
    fun detailsSheetKeepsTheOpeningRowsRole() {
        val sheet = TransactionsDetailsBottomSheet.newInstance(
            row(txid, 2, premixPub, result = 12_500_000L),
            secure = false,
            perspectiveRole = TxPerspectiveLabel.Role.PREMIX,
        )
        assertEquals(
            TxPerspectiveLabel.Role.PREMIX.name,
            sheet.arguments?.getString(TransactionsDetailsBottomSheet.ARG_PERSPECTIVE_ROLE),
        )
    }

    private fun assertRemixUnchanged(holder: TransactionAdapter.ViewHolder) {
        assertEquals("Whirlpool remix", holder.txAmount.text.toString())
        assertEquals(Color.parseColor("#69dee3"), holder.txAmount.currentTextColor)
        val icon = holder.directionImageView.drawable
        assertNotNull(icon)
        assertEquals(R.drawable.ic_repeat_24dp, shadowOf(icon).createdFromResId)
    }

    private fun bindOne(tx: Tx): TransactionAdapter.ViewHolder =
        bind(listOf(tx), labels = mapOf(txid to tx0)).single()

    private fun bind(
        txs: List<Tx>,
        labels: Map<String, TxLabelDetail>,
    ): List<TransactionAdapter.ViewHolder> {
        val adapter = RowAdapter(txs)
        adapter.pubRoles = roles
        adapter.txLabels = labels
        val parent = FrameLayout(ApplicationProvider.getApplicationContext())
        return txs.indices.map { index ->
            val holder = adapter.onCreateViewHolder(parent, 0)
            adapter.onBindViewHolder(holder, index)
            holder
        }
    }

    /**
     * ListAdapter applies its list on a background executor. These tests
     * bind [onBindViewHolder] directly, so the rows are supplied here
     * instead of through [androidx.recyclerview.widget.ListAdapter.submitList].
     */
    private class RowAdapter(private val rows: List<Tx>) : TransactionAdapter() {
        override fun getItem(position: Int): Tx = rows[position]
        override fun getItemCount(): Int = rows.size
    }

    private fun row(bareTxid: String, suffix: Int, pub: String, result: Long): Tx = Tx(
        hash = "$bareTxid-$collectionId-$suffix",
        time = 1_700_000_000L,
        associatedPubKey = pub,
        collectionId = collectionId,
        version = 1,
        locktime = 0,
        result = result,
        inputs = emptyList(),
        out = emptyList(),
        block_height = 800_000L,
        confirmations = 10,
    )
}
