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

/**
 * The pre-pair dialog as DojoConfigureBottomSheet builds it, on the real
 * sheets and a short window (360x400dp):
 * - Back is Don't pair. From the Community Dojos list: the same list stays
 *   open with no reload, the sheet goes back to its first page, nothing is
 *   saved, and the next pick opens a fresh dialog. From a scan or paste: the
 *   sheet closes and nothing is saved.
 * - The lead-in message is in the scrolling body, not a setMessage panel, in
 *   the dialog's message style, and it is announced when the dialog opens;
 *   Pair and Don't pair stay on screen.
 * Tor is set to ready in the state only: nothing starts, nothing is fetched.
 * Throwaway test-only codes; the onions are placeholders.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h400dp-xxhdpi")
class PrePairDialogSheetTest {

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

    private fun listing(): CheckedDojo {
        val node = CommunityDojoNode(
            id = "dontpair" + owner.code.takeLast(4), network = "testnet", name = "Test Dojo", status = "active",
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

    @Test
    fun `Back on a listing's pre-pair dialog goes back to the same list and saves nothing`() {
        val (sheet, list) = openList()
        val listView = list.requireView()
        val loadsBefore = list.directoryLoads

        pick(list, listing())
        val first = prePairDialog()
        first.onBackPressed()
        idle()

        assertFalse(first.isShowing)
        assertTrue(sheet.isAdded && sheet.dialog?.isShowing == true)
        assertSame(list, sheet.childFragmentManager.findFragmentByTag(DojoConfigureBottomSheet.COMMUNITY_DOJO_LIST_TAG))
        assertTrue(list.isAdded && list.dialog?.isShowing == true)
        assertSame(listView, list.requireView())
        assertEquals(loadsBefore, list.directoryLoads)
        assertEquals(0, pager(sheet).currentItem)
        assertNothingSaved()

        // The next pick opens a fresh dialog, and a late tap on the old one does nothing.
        pick(list, listing())
        val second = prePairDialog()
        assertTrue(second !== first)
        first.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertTrue(second.isShowing)
        second.onBackPressed()
        idle()
        assertTrue(list.dialog?.isShowing == true)
        assertEquals(loadsBefore, list.directoryLoads)
        assertNothingSaved()
    }

    @Test
    fun `Back on a scanned pairing's pre-pair dialog closes the sheet and saves nothing`() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.AppThemeV3)
        val activity = controller.setup().get()
        val sheet = DojoConfigureBottomSheet.newInstance("pre_pair_scan_test")
        sheet.show(activity.supportFragmentManager, "dojo_configure")
        idle()
        assertTrue(sheet.onScanPayload(DojoBayTestVectors.TEST_PAIRING_JSON))
        idle()
        val dialog = prePairDialog()
        dialog.onBackPressed()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        assertFalse(dialog.isShowing)
        assertTrue(activity.supportFragmentManager.findFragmentByTag("dojo_configure") == null)
        assertNothingSaved()
    }

    @Test
    fun `the message is in the scrolling body in the dialog's message style, announced, with the buttons on screen`() {
        val accessibility = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        shadowOf(accessibility).setEnabled(true)
        val (_, list) = openList()
        pick(list, listing())
        val dialog = prePairDialog()
        idle()

        // One middle panel: no setMessage message, the lead-in is in the body.
        val platformMessage = dialog.findViewById<TextView>(android.R.id.message)
        assertTrue(platformMessage == null || !platformMessage.isShown)
        val message = dialog.findViewById<TextView>(R.id.dojoPairingMessage)!!
        assertTrue(message.isShown)
        assertTrue(message.text.startsWith(ApplicationProvider.getApplicationContext<Context>().getString(R.string.dojo_pairing_confirm_message)))
        var parent = message.parent
        while (parent != null && parent !is NestedScrollView) parent = parent.parent
        assertTrue("the message scrolls with the body", parent is NestedScrollView)

        // The same color and size a setMessage message gets in this dialog theme.
        val reference = MaterialAlertDialogBuilder(list.requireActivity())
            .setTitle("t").setMessage("m").create()
        reference.show()
        idle()
        val ref = reference.findViewById<TextView>(android.R.id.message)!!
        assertEquals(ref.textColors.defaultColor, message.textColors.defaultColor)
        assertEquals(ref.currentTextColor, message.currentTextColor)
        assertEquals(ref.textSize, message.textSize, 0.01f)
        reference.dismiss()

        // Announced when the dialog opens.
        val announced = shadowOf(accessibility).sentAccessibilityEvents
            .filter { it.eventType == AccessibilityEvent.TYPE_ANNOUNCEMENT }
            .map { it.text.joinToString("") }
        assertTrue(announced.toString(), announced.any { it == message.text.toString() })
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, message.importantForAccessibility)

        for (which in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE)) {
            val button = dialog.getButton(which)
            val loc = IntArray(2)
            button.getLocationOnScreen(loc)
            val screen = dialog.context.resources.displayMetrics.heightPixels
            assertTrue("bottom=${loc[1] + button.height} screen=$screen", button.isShown && button.height > 0 && loc[1] + button.height <= screen)
        }
        dialog.onBackPressed()
        idle()
        shadowOf(accessibility).setEnabled(false)
    }
}
