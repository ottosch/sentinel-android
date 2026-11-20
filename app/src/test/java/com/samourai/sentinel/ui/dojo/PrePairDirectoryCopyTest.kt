package com.samourai.sentinel.ui.dojo

import android.app.Application
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.MutableLiveData
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonObject
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
 * The pre-pair dialog's directory name and explorer sentence, bound by the
 * real sheet. DojoBay.org is the default directory only. A custom directory
 * is named by [DojoBaySource.label]. The block explorer is mentioned only
 * when the signed payload carries one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h400dp-xxhdpi")
class PrePairDirectoryCopyTest {

    private lateinit var prefs: PrefsUtil
    private val owner = ThrowawayPayNym(54)
    private val pairingUrl = "http://" + "a".repeat(56) + ".onion/test/v2"

    @Before
    fun setUp() {
        GlobalContext.getOrNull()?.let { stopKoin() }
        val app = ApplicationProvider.getApplicationContext<Application>()
        prefs = PrefsUtil(app)
        prefs.clearAll()
        prefs.testnet = true
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

    private fun listing(explorerUrl: String?): CheckedDojo {
        val pairing = JsonObject().apply {
            add("pairing", JsonObject().apply {
                addProperty("type", "dojo.api")
                addProperty("version", "1.27.0")
                addProperty("apikey", "testonly-apikey")
                addProperty("url", pairingUrl)
            })
            if (explorerUrl != null) {
                add("explorer", JsonObject().apply {
                    addProperty("type", "explorer.btc_rpc_explorer")
                    addProperty("url", explorerUrl)
                })
            }
        }
        val signed = owner.listing(pairing.toString())
        val nodeJson = JsonObject().apply {
            addProperty("id", "copy" + owner.code.takeLast(4))
            addProperty("network", "testnet")
            addProperty("name", "Test Dojo")
            addProperty("status", "active")
            addProperty("version", "1.29.2")
            addProperty("paymentCode", owner.code)
            add("payload", pairing)
            addProperty("signed", signed)
        }
        val node = CommunityDojoRepository.parseDirectory("""{"nodes":[$nodeJson]}""").single()
        return CheckedDojo(node, DojoBaySignatures.checkNode(node)).also { assertNotNull(it.verified) }
    }

    private fun openDialog(item: CheckedDojo): AlertDialog {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.AppThemeV3)
        val activity = controller.setup().get()
        val sheet = DojoConfigureBottomSheet.newInstance("directory_copy")
        sheet.show(activity.supportFragmentManager, "dojo_configure")
        idle()
        CommunityDojoListBottomSheet().show(
            sheet.childFragmentManager, DojoConfigureBottomSheet.COMMUNITY_DOJO_LIST_TAG
        )
        idle()
        val list = sheet.childFragmentManager
            .findFragmentByTag(DojoConfigureBottomSheet.COMMUNITY_DOJO_LIST_TAG) as CommunityDojoListBottomSheet
        CommunityDojoListBottomSheet::class.java
            .getDeclaredMethod("selectListing", CheckedDojo::class.java)
            .apply { isAccessible = true }
            .invoke(list, item)
        idle()
        val d = ShadowDialog.getLatestDialog()
        assertTrue(d is AlertDialog && d.isShowing)
        return d as AlertDialog
    }

    private fun caption(d: AlertDialog) =
        d.findViewById<TextView>(R.id.dojoPairingDirectoryCaption)!!.text.toString()

    /** Every text in the dialog, shown or hidden: none is a version. */
    private fun assertNoVersion(d: AlertDialog) {
        val parts = ArrayList<String>()
        fun walk(v: android.view.View) {
            if (v is TextView) parts.add(v.text?.toString().orEmpty())
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(d.window!!.decorView)
        val all = parts.joinToString("\n")
        assertFalse(all.lowercase().contains("version"))
        assertFalse(all.contains("1.29.2"))
        assertFalse(all.contains("1.27.0"))
        assertFalse(all.contains("Last checked"))
    }

    @Test
    fun `the default directory is DojoBay org and the explorer is mentioned only when signed`() {
        assertTrue(DojoBaySource.isDefault(DojoBaySource.effectiveBaseUrl(prefs.dojoBayUrl)))
        val withExplorer = openDialog(listing("http://explorer.example"))
        assertEquals(
            "The signature covers this Dojo's URL, connection details and block explorer. " +
                "Name and location come from the DojoBay.org listing.",
            caption(withExplorer),
        )
        assertNoVersion(withExplorer)
        withExplorer.dismiss()
        idle()

        val without = openDialog(listing(null))
        assertEquals(
            "The signature covers this Dojo's URL and connection details. " +
                "Name and location come from the DojoBay.org listing.",
            caption(without),
        )
        assertFalse(caption(without).contains("block explorer"))
        assertNoVersion(without)
        without.dismiss()
    }

    @Test
    fun `a custom directory is named by its label, not DojoBay org`() {
        val custom = "http://yfua6lm3x5rjc6v76gltnahdaf5mcgpn3z5p3pognswi4cpmmwanueqd.onion/"
        prefs.dojoBayUrl = custom
        prefs.dojoBayPinnedCode = null
        val base = DojoBaySource.effectiveBaseUrl(prefs.dojoBayUrl)
        assertFalse(DojoBaySource.isDefault(base))
        val label = DojoBaySource.label(base)
        assertFalse(label.contains("DojoBay.org"))

        val d = openDialog(listing(null))
        assertEquals(
            "The signature covers this Dojo's URL and connection details. " +
                "Name and location come from the $label listing.",
            caption(d),
        )
        assertFalse(caption(d).contains("DojoBay.org"))
        assertFalse(caption(d).contains("block explorer"))
    }

    @Test
    fun `a signed explorer with a blank url is not named in the caption`() {
        val d = openDialog(listing(""))
        assertEquals(
            "The signature covers this Dojo's URL and connection details. " +
                "Name and location come from the DojoBay.org listing.",
            caption(d),
        )
        assertFalse(caption(d).contains("block explorer"))
        assertEquals(android.view.View.GONE, d.findViewById<android.view.View>(R.id.dojoPairingExplorerRow)!!.visibility)
        d.dismiss()
        idle()
    }
}
