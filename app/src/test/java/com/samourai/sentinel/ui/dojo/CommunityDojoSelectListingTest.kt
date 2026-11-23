package com.samourai.sentinel.ui.dojo

import android.app.Application
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.MutableLiveData
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.R
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.data.db.SentinelCollectionStore
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.tor.TorState
import com.samourai.sentinel.ui.dojo.DojoBayTestVectors.ThrowawayPayNym
import com.samourai.sentinel.ui.utils.PrefsUtil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * Verifies that [CommunityDojoListBottomSheet.selectListing] forwards the
 * verified signed pairing JSON (never falling back to the directory's unsigned
 * payload) and the signer's [signerCode] to [DojoConfigureBottomSheet].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h400dp-xxhdpi")
class CommunityDojoSelectListingTest {

    private lateinit var prefs: PrefsUtil
    private val owner = ThrowawayPayNym(77)

    @Before
    fun setUp() {
        GlobalContext.getOrNull()?.let { stopKoin() }
        val app = ApplicationProvider.getApplicationContext<Application>()
        prefs = PrefsUtil(app)
        prefs.clearAll()
        prefs.testnet = true
        // Refused before any network or Tor start.
        prefs.dojoBayUrl = "http://" + "d".repeat(56) + ".onion/"
        prefs.dojoBayPinnedCode = null
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
        setTorReady()
    }

    @After
    fun tearDown() {
        GlobalContext.getOrNull()?.let { stopKoin() }
    }

    private fun setTorReady() {
        val f = SentinelTorManager::class.java
            .getDeclaredField("torStateLiveDataPrivate")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (f.get(SentinelTorManager) as MutableLiveData<TorState>).value =
            TorState().apply { state = EnumTorState.ON; progressIndicator = 100 }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun openList(): Pair<DojoConfigureBottomSheet, CommunityDojoListBottomSheet> {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.AppThemeV3)
        val activity = controller.setup().get()
        val sheet = DojoConfigureBottomSheet.newInstance("select_listing_test")
        sheet.show(activity.supportFragmentManager, "dojo_configure")
        idle()
        CommunityDojoListBottomSheet().show(
            sheet.childFragmentManager, DojoConfigureBottomSheet.COMMUNITY_DOJO_LIST_TAG
        )
        idle()
        val list = sheet.childFragmentManager
            .findFragmentByTag(DojoConfigureBottomSheet.COMMUNITY_DOJO_LIST_TAG) as CommunityDojoListBottomSheet
        assertTrue(list.dialog?.isShowing == true)
        return sheet to list
    }

    private fun clickRow(list: CommunityDojoListBottomSheet, items: List<CheckedDojo>, position: Int = 0) {
        val rv = list.requireView().findViewById<RecyclerView>(R.id.communityDojoRecyclerView)
        val adapter = rv.adapter as CommunityDojoAdapter
        adapter.submitList(items)
        idle()
        rv.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY),
        )
        rv.layout(0, 0, 1080, 1920)
        val holder = rv.findViewHolderForAdapterPosition(position)
            ?: error("no ViewHolder bound at position $position")
        holder.itemView.performClick()
        idle()
    }

    private fun sheetPayload(sheet: DojoConfigureBottomSheet): String =
        DojoConfigureBottomSheet::class.java
            .getDeclaredField("payload")
            .apply { isAccessible = true }
            .get(sheet) as String

    private fun sheetPendingSignerCode(sheet: DojoConfigureBottomSheet): String? {
        val pin = DojoConfigureBottomSheet::class.java
            .getDeclaredField("pendingSignerPin")
            .apply { isAccessible = true }
            .get(sheet) as PendingSignerPin
        return pin.forDialog()
    }

    @Test
    fun `tapping a verified row forwards the signed payload and signerCode, not the unsigned payload`() {
        val (sheet, list) = openList()

        // Unsigned directory payload declares 1.28.0 while the signed block carries 1.27.0.
        val signedJson = DojoBayTestVectors.TEST_PAIRING_JSON
        val unsignedPayload = DojoBayTestVectors.testPayload().let { p -> p.copy(pairing = p.pairing!!.copy(version = "1.28.0")) }
        val node = CommunityDojoNode(
            id = "row-tap-drift",
            network = "testnet",
            name = "Signed Dojo",
            status = "active",
            paymentCode = owner.code,
            payload = unsignedPayload,
            signed = owner.listing(signedJson),
        )
        val checked = CheckedDojo(node, DojoBaySignatures.checkNode(node))
        assertNotNull(checked.verified)
        assertTrue(signedJson.contains("1.27.0"))

        clickRow(list, listOf(checked))

        assertEquals(checked.verified!!.signedPayloadJson, sheetPayload(sheet))
        assertFalse(sheetPayload(sheet).contains("1.28.0"))
        assertEquals(owner.code, sheetPendingSignerCode(sheet))

        val dialog = ShadowDialog.getLatestDialog() as? AlertDialog
        assertNotNull("pre-pair dialog should open", dialog)
        assertTrue(dialog!!.isShowing)
        val shownCode = dialog.findViewById<TextView>(R.id.dojoPairingSignerCode)!!.text.toString()
        assertEquals(owner.code, SignerCodeFormat.compact(shownCode))
    }

    @Test
    fun `selectListing never falls back to the unsigned payload when signedPayloadJson differs`() {
        val (sheet, list) = openList()

        val signedUrl = "http://" + "s".repeat(56) + ".onion/v2"
        val unsignedUrl = "http://" + "u".repeat(56) + ".onion/v2"
        val signedJson = """{"pairing":{"type":"dojo.api","version":"1.27.0","apikey":"signed-key","url":"$signedUrl"}}"""
        val unsignedPayload = CommunityDojoPayload(
            pairing = Pairing(type = "dojo.api", version = "1.28.0", apikey = "unsigned-key", url = unsignedUrl),
        )
        val node = CommunityDojoNode(
            id = "row-tap-distinct",
            network = "testnet",
            name = "Distinct Signed Dojo",
            status = "active",
            paymentCode = owner.code,
            payload = unsignedPayload,
            signed = null,
        )
        val verifiedItem = CheckedDojo(
            node,
            DojoBaySignatures.NodeCheck.Verified(owner.code, signedJson, versionDrift = false),
        )

        clickRow(list, listOf(verifiedItem))

        assertEquals(signedJson, sheetPayload(sheet))
        assertFalse(sheetPayload(sheet).contains(unsignedUrl))
        assertFalse(sheetPayload(sheet).contains("unsigned-key"))
        assertEquals(owner.code, sheetPendingSignerCode(sheet))

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        assertEquals(signedUrl, dialog.findViewById<TextView>(R.id.dojoPairingUrlValue)!!.text.toString())
        assertEquals(
            owner.code,
            SignerCodeFormat.compact(dialog.findViewById<TextView>(R.id.dojoPairingSignerCode)!!.text.toString()),
        )
    }

    @Test
    fun `tapping an unverified row does not forward any payload or signerCode`() {
        val (sheet, list) = openList()
        val unverified = CheckedDojo(
            CommunityDojoNode(
                id = "row-tap-unverified",
                network = "testnet",
                name = "Unsigned Dojo",
                status = "active",
                payload = DojoBayTestVectors.testPayload(),
                signed = null,
            ),
            DojoBaySignatures.NodeCheck.Unverified(DojoBaySignatures.Failure.MISSING_SIGNATURE),
        )

        clickRow(list, listOf(unverified))

        assertEquals("", sheetPayload(sheet))
        assertNull(sheetPendingSignerCode(sheet))
        val alertDialogs = ShadowDialog.getShownDialogs().filterIsInstance<AlertDialog>().filter { it.isShowing }
        assertTrue(alertDialogs.isEmpty())
    }
}
