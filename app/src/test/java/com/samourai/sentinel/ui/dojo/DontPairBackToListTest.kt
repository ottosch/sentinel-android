package com.samourai.sentinel.ui.dojo

import android.app.Application
import android.content.Context
import android.os.Looper
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
 * DON'T PAIR on a listing picked from Community Dojos closes only the
 * pre-pair dialog: the list is still open, the same list (nothing
 * fetched or reloaded), nothing is saved, and picking again opens a
 * fresh pre-pair dialog. Throwaway test-only codes; the onions are
 * placeholders. Tor is set to ready in the state only: nothing starts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DontPairBackToListTest {

    private lateinit var prefs: PrefsUtil
    private val owner = ThrowawayPayNym(51)
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

    @Test
    fun `DON'T PAIR closes only the dialog and leaves the same list open with nothing saved`() {
        val (sheet, list) = openList()
        val listView = list.requireView()
        val loadsBefore = list.directoryLoads

        pick(list, listing())
        prePairDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()

        // The configure sheet and the list are still up, the list is the
        // same fragment with the same view, and it did not load again.
        assertTrue(sheet.isAdded && sheet.dialog?.isShowing == true)
        assertSame(list, sheet.childFragmentManager.findFragmentByTag(DojoConfigureBottomSheet.COMMUNITY_DOJO_LIST_TAG))
        assertTrue(list.isAdded && list.dialog?.isShowing == true)
        assertSame(listView, list.requireView())
        assertEquals(loadsBefore, list.directoryLoads)
        // Back on the first page, not the camera.
        assertEquals(0, sheet.requireView().findViewById<ViewPager2>(R.id.pager).currentItem)
        assertNothingSaved()
    }

    @Test
    fun `picking again after DON'T PAIR opens a fresh pre-pair dialog`() {
        val (_, list) = openList()
        pick(list, listing())
        val first = prePairDialog()
        first.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()

        pick(list, listing())
        val second = prePairDialog()
        assertTrue(second !== first)
        second.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertTrue(list.dialog?.isShowing == true)
        assertNothingSaved()
    }
}
