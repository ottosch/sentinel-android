package com.samourai.sentinel.ui.dojo

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.MutableLiveData
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h400dp-xxhdpi")
class PrePairVersionSheetWiringTest {

    private lateinit var prefs: PrefsUtil
    private val owner = ThrowawayPayNym(52)
    private val urlA = "http://" + "a".repeat(56) + ".onion/test/v2"

    @Before
    fun setUp() {
        GlobalContext.getOrNull()?.let { stopKoin() }
        val app = ApplicationProvider.getApplicationContext<Application>()
        prefs = PrefsUtil(app)
        prefs.clearAll()
        prefs.testnet = true
        // A custom directory saved without its operator code is refused
        // before anything is requested: the list opens with no fetch and
        // no Tor start, which keeps this test off the network.
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
        val f = SentinelTorManager::class.java.getDeclaredField("torStateLiveDataPrivate").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (f.get(SentinelTorManager) as MutableLiveData<TorState>).value =
            TorState().apply { state = EnumTorState.ON; progressIndicator = 100 }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun listing(version: String? = null): CheckedDojo {
        val node = CommunityDojoNode(
            id = "dontpair" + owner.code.takeLast(4), network = "testnet", name = "Test Dojo", status = "active", version = version,
            paymentCode = owner.code, payload = DojoBayTestVectors.testPayload(),
            signed = owner.listing(DojoBayTestVectors.TEST_PAIRING_JSON),
        )
        return CheckedDojo(node, DojoBaySignatures.checkNode(node)).also { assertNotNull(it.verified) }
    }

    private fun pick(list: CommunityDojoListBottomSheet, item: CheckedDojo) {
        CommunityDojoListBottomSheet::class.java
            .getDeclaredMethod("selectListing", CheckedDojo::class.java)
            .apply { isAccessible = true }
            .invoke(list, item)
        idle()
    }

    private fun prePairDialog(): AlertDialog {
        val d = ShadowDialog.getLatestDialog()
        assertTrue("the pre-pair dialog is showing", d is AlertDialog && d.isShowing)
        return d as AlertDialog
    }

    private fun openList(): Pair<DojoConfigureBottomSheet, CommunityDojoListBottomSheet> {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.AppThemeV3)
        val activity = controller.setup().get()
        val sheet = DojoConfigureBottomSheet.newInstance("dont_pair_test")
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

    private fun assertNothingSaved() {
        assertNull(prefs.dojoTrustedSignerCode)
        assertNull(prefs.dojoTrustedSignerUrl)
        assertNull(prefs.dojoDisplayUrl)
        assertNull(prefs.dojoDisplayName)
        assertNull(prefs.apiEndPointTor)
        assertNull(prefs.apiEndPoint)
    }

    private fun pager(sheet: DojoConfigureBottomSheet) =
        sheet.requireView().findViewById<ViewPager2>(R.id.pager)


    private fun payloadOf(sheet: DojoConfigureBottomSheet): String =
        DojoConfigureBottomSheet::class.java.getDeclaredField("payload").apply { isAccessible = true }.get(sheet) as String

    private fun versionRow(d: AlertDialog) = d.findViewById<View>(R.id.dojoPairingVersionRow)!!
    private fun versionValue(d: AlertDialog) = d.findViewById<TextView>(R.id.dojoPairingVersionValue)!!.text.toString()

    @Test
    fun `the real sheet shows the directory version, never the signed one, and holds the signed payload byte for byte`() {
        val (sheet, list) = openList()
        val item = listing(version = "1.29.2")
        pick(list, item)
        val d = prePairDialog()
        assertEquals(View.VISIBLE, versionRow(d).visibility)
        assertEquals("1.29.2", versionValue(d))
        assertTrue(d.findViewById<View>(R.id.dojoPairingVersionSource)!!.isShown)
        // What Pair will send and store is the signed JSON, unchanged.
        assertEquals(item.verified!!.signedPayloadJson, payloadOf(sheet))
        assertTrue(payloadOf(sheet).contains("\"version\":\"1.27.0\""))
        d.onBackPressed(); idle()
    }

    @Test
    fun `the real sheet hides the row for an ill-shaped or missing directory version and never falls back`() {
        for (bad in listOf(null, "1.29.2\u202e", "a".repeat(33))) {
            val (sheet, list) = openList()
            val item = listing(version = bad)
            pick(list, item)
            val d = prePairDialog()
            assertEquals(bad.toString(), View.GONE, versionRow(d).visibility)
            assertFalse(versionValue(d).contains("1.27.0"))
            assertEquals(item.verified!!.signedPayloadJson, payloadOf(sheet))
            d.onBackPressed(); idle()
            sheet.dismissAllowingStateLoss(); idle()
        }
    }

    @Test
    fun `a scanned payload shows no version row on the real sheet`() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.AppThemeV3)
        val activity = controller.setup().get()
        val sheet = DojoConfigureBottomSheet.newInstance("pre_pair_scan_version")
        sheet.show(activity.supportFragmentManager, "dojo_configure")
        idle()
        assertTrue(sheet.onScanPayload(DojoBayTestVectors.TEST_PAIRING_JSON))
        idle()
        val d = prePairDialog()
        assertEquals(View.GONE, versionRow(d).visibility)
        assertEquals(DojoBayTestVectors.TEST_PAIRING_JSON, payloadOf(sheet))
        d.onBackPressed(); idle()
    }

    @Test
    fun `Pair commits the signed pairing version, not the directory version`() {
        val (sheet, list) = openList()
        val item = listing(version = "1.29.2")
        pick(list, item)
        val d = prePairDialog()
        val signed = com.samourai.sentinel.helpers.fromJSON<DojoPairing>(item.verified!!.signedPayloadJson)!!
        // The commit body the 200 handler posts (private; reflective seam for the test).
        DojoConfigureBottomSheet::class.java.declaredMethods.first { it.name == "commitPairing" }
            .apply { isAccessible = true }
            .invoke(sheet, signed, DojoAuthTokens("test-access", "test-refresh"))
        idle()
        val dojo = GlobalContext.get().get<DojoUtility>()
        assertEquals("1.27.0", dojo.getPairing()?.version)
        assertEquals(signed.pairing?.url, prefs.apiEndPoint)
        d.dismiss(); idle()
    }
}
