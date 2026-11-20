package com.samourai.sentinel.ui.dojo

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.MutableLiveData
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
import org.koin.java.KoinJavaComponent
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.Collections

/**
 * Sentinel shows a Dojo version only after pairing: the one the Dojo itself
 * reports (DojoReportedVersion), on the paired-Dojo screen. The pre-pair
 * dialog shows none, neither the directory's listing version nor the
 * pairing code's, and nothing is sent to the Dojo before Pair. Pair still
 * stores the signed payload, whatever version the directory lists.
 *
 * Runs the real sheet, the real Community Dojos list and its selectListing,
 * with a signed listing whose unsigned top-level version was edited.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h400dp-xxhdpi")
class PrePairNoVersionTest {

    private lateinit var prefs: PrefsUtil
    private val owner = ThrowawayPayNym(52)

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
        setTor(null)
    }

    @After
    fun tearDown() {
        setTorProxy(null)
        GlobalContext.getOrNull()?.let { stopKoin() }
    }

    private fun setTor(proxy: Proxy?) {
        setTorProxy(proxy)
        val f = SentinelTorManager::class.java.getDeclaredField("torStateLiveDataPrivate").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (f.get(SentinelTorManager) as MutableLiveData<TorState>).value =
            TorState().apply { state = EnumTorState.ON; progressIndicator = 100 }
    }

    private fun setTorProxy(proxy: Proxy?) {
        SentinelTorManager::class.java.getDeclaredField("proxy").apply { isAccessible = true }
            .set(SentinelTorManager, proxy)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun listing(version: String?): CheckedDojo {
        val node = CommunityDojoNode(
            id = "noversion" + owner.code.takeLast(4), network = "testnet", name = "Test Dojo", status = "active",
            version = version,
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

    private fun activity(): AppCompatActivity {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.AppThemeV3)
        return controller.setup().get()
    }

    private fun openList(): Pair<DojoConfigureBottomSheet, CommunityDojoListBottomSheet> {
        val sheet = DojoConfigureBottomSheet.newInstance("pre_pair_no_version")
        sheet.show(activity().supportFragmentManager, "dojo_configure")
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

    private fun payloadOf(sheet: DojoConfigureBottomSheet): String =
        DojoConfigureBottomSheet::class.java.getDeclaredField("payload").apply { isAccessible = true }.get(sheet) as String

    private fun visibleText(root: View): String {
        val parts = ArrayList<String>()
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE) return
            v.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            if (v is TextView) v.text?.toString()?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return parts.joinToString("\n")
    }

    /** Every text in the dialog, shown or hidden: no version may sit in a hidden view either. */
    private fun allText(root: View): String {
        val parts = ArrayList<String>()
        fun walk(v: View) {
            if (v is TextView) v.text?.toString()?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return parts.joinToString("\n")
    }

    private fun assertNoVersion(d: AlertDialog, vararg versions: String) {
        val shown = visibleText(d.window!!.decorView)
        val all = allText(d.window!!.decorView)
        for (text in listOf(shown, all)) {
            assertFalse(text.lowercase().contains("version"))
            assertFalse(text.contains("Last checked"))
            for (v in versions) assertFalse(v, text.contains(v))
        }
        // The caption says what the signature covers, and no longer claims a version.
        val caption = d.findViewById<TextView>(R.id.dojoPairingDirectoryCaption)!!.text.toString()
        assertFalse(caption, caption.contains("version"))
    }

    @Test
    fun `the layout and strings have no pre-pair version row`() {
        val res = ApplicationProvider.getApplicationContext<Application>().resources
        val pkg = ApplicationProvider.getApplicationContext<Application>().packageName
        for (id in listOf("dojoPairingVersionRow", "dojoPairingVersionValue", "dojoPairingVersionSource", "dojoPairingDirectoryVersion")) {
            assertEquals(id, 0, res.getIdentifier(id, "id", pkg))
        }
        for (name in listOf("dojo_pairing_row_version", "dojo_pairing_version_source", "dojo_pairing_directory_version")) {
            assertEquals(name, 0, res.getIdentifier(name, "string", pkg))
        }
    }

    @Test
    fun `a directory pick shows no version, and Pair would send the signed payload byte for byte`() {
        val (sheet, list) = openList()
        val item = listing(version = "1.29.2")
        pick(list, item)
        val d = prePairDialog()
        assertNoVersion(d, "1.29.2", "1.27.0")
        val text = visibleText(d.window!!.decorView).lowercase()
        assertFalse(text.contains("not signed"))
        assertFalse(text.contains("unsigned"))
        assertEquals(item.verified!!.signedPayloadJson, payloadOf(sheet))
        assertTrue(payloadOf(sheet).contains("\"version\":\"1.27.0\""))
        d.onBackPressed(); idle()
    }

    @Test
    fun `an ill-shaped or missing directory version shows nothing either`() {
        for (bad in listOf(null, "1.29.2\u202e", "<b>9.9.9</b>", "a".repeat(33))) {
            val (sheet, list) = openList()
            val item = listing(version = bad)
            pick(list, item)
            val d = prePairDialog()
            assertNoVersion(d, "1.27.0", "9.9.9")
            assertEquals(item.verified!!.signedPayloadJson, payloadOf(sheet))
            d.onBackPressed(); idle()
            sheet.dismissAllowingStateLoss(); idle()
        }
    }

    @Test
    fun `a scanned payload shows no version`() {
        val sheet = DojoConfigureBottomSheet.newInstance("pre_pair_scan_no_version")
        sheet.show(activity().supportFragmentManager, "dojo_configure")
        idle()
        assertTrue(sheet.onScanPayload(DojoBayTestVectors.TEST_PAIRING_JSON))
        idle()
        val d = prePairDialog()
        assertNoVersion(d, "1.27.0")
        assertEquals(DojoBayTestVectors.TEST_PAIRING_JSON, payloadOf(sheet))
        d.onBackPressed(); idle()
    }

    @Test
    fun `nothing is sent to the Dojo before Pair`() {
        // A stand-in SOCKS proxy that records where each connection asks to go.
        val asked = Collections.synchronizedList(ArrayList<String>())
        val socks = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val accept = Thread {
            while (!socks.isClosed) {
                try {
                    socks.accept().use { s ->
                        val input = DataInputStream(s.getInputStream())
                        val out = s.getOutputStream()
                        input.readByte(); val n = input.readUnsignedByte(); repeat(n) { input.readByte() }
                        out.write(byteArrayOf(5, 0)); out.flush()
                        input.readByte(); input.readByte(); input.readByte()
                        when (input.readUnsignedByte()) {
                            3 -> { val len = input.readUnsignedByte(); val b = ByteArray(len); input.readFully(b); asked.add(String(b)) }
                            else -> asked.add("(address)")
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }.apply { isDaemon = true; start() }
        val api = KoinJavaComponent.get(ApiService::class.java)
        var apiCalls = 0
        api.beforeNewCall = { _, _ -> apiCalls++ }
        try {
            setTor(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socks.localPort)))
            val (sheet, list) = openList()
            val item = listing(version = "1.29.2")
            pick(list, item)
            val d = prePairDialog()
            Thread.sleep(500)
            idle()
            val dojoHost = com.samourai.sentinel.helpers.fromJSON<DojoPairing>(payloadOf(sheet))!!
                .pairing!!.url!!.let { java.net.URI(it).host }
            assertFalse(asked.any { it.equals(dojoHost, ignoreCase = true) })
            assertEquals(0, apiCalls)
            d.onBackPressed(); idle()
        } finally {
            api.beforeNewCall = null
            socks.close()
            accept.join(2_000)
        }
    }

    @Test
    fun `Pair stores the signed payload and the Dojo's reported version, never the directory's`() {
        val (sheet, list) = openList()
        val item = listing(version = "1.29.2")
        pick(list, item)
        val d = prePairDialog()
        val signed = com.samourai.sentinel.helpers.fromJSON<DojoPairing>(item.verified!!.signedPayloadJson)!!
        // The commit body the 200 handler posts (private; reflective seam for the test).
        val commit = DojoConfigureBottomSheet::class.java.declaredMethods.first { it.name == "commitPairing" }
            .apply { isAccessible = true }
        commit.invoke(sheet, signed, PairingLogin(DojoAuthTokens("test-access", "test-refresh"), "1.29.3"))
        idle()
        val dojo = GlobalContext.get().get<DojoUtility>()
        assertEquals("1.27.0", dojo.getPairing()?.version)
        assertEquals(signed.pairing?.url, prefs.apiEndPoint)
        assertEquals("1.29.3", prefs.dojoReportedVersion)
        d.dismiss(); idle()
    }

    @Test
    fun `with no reported version the paired screen shows the pairing code's, never the directory's`() {
        val (sheet, list) = openList()
        val item = listing(version = "1.29.2")
        pick(list, item)
        val d = prePairDialog()
        val signed = com.samourai.sentinel.helpers.fromJSON<DojoPairing>(item.verified!!.signedPayloadJson)!!
        DojoConfigureBottomSheet::class.java.declaredMethods.first { it.name == "commitPairing" }
            .apply { isAccessible = true }
            .invoke(sheet, signed, PairingLogin(DojoAuthTokens("test-access", "test-refresh"), null))
        idle()
        d.dismiss(); idle()
        assertNull(prefs.dojoReportedVersion)
        val credentials = DojoCredentialsBottomSheet()
        credentials.show(activity().supportFragmentManager, "dojo_credentials")
        idle()
        val shown = credentials.requireView().findViewById<TextView>(R.id.dojoVersionValue).text.toString()
        assertEquals("1.27.0", shown)
        assertFalse(shown.contains("1.29.2"))
        credentials.dismiss(); idle()
    }

    @Test
    fun `a hostile login header changes nothing the real commit stores but the version`() {
        val hostile = listOf(
            "http://" + "e".repeat(56) + ".onion/v2",
            "e".repeat(16) + ".onion",
            """{"pairing":{"type":"dojo.api","apikey":"evil","url":"http://evil.example/v2"}}""",
            "x".repeat(10 * 1024),
            "1.29.3\r\nX-Evil: 1",
        )
        var reference: List<String?>? = null
        for (h in hostile) {
            val (sheet, list) = openList()
            val item = listing(version = "1.29.2")
            pick(list, item)
            val d = prePairDialog()
            val signed = com.samourai.sentinel.helpers.fromJSON<DojoPairing>(item.verified!!.signedPayloadJson)!!
            DojoConfigureBottomSheet::class.java.declaredMethods.first { it.name == "commitPairing" }
                .apply { isAccessible = true }
                .invoke(sheet, signed, PairingLogin(DojoAuthTokens("test-access", "test-refresh"), h))
            idle()
            val dojo = GlobalContext.get().get<DojoUtility>()
            val stored = listOf(
                dojo.exportDojoPayload(), dojo.getApiKey(), dojo.getPairing()?.url,
                prefs.apiEndPoint, prefs.apiEndPointTor, prefs.authorization, prefs.refreshToken,
            )
            assertEquals(signed.pairing?.url, prefs.apiEndPoint)
            assertEquals(signed.pairing?.url, prefs.apiEndPointTor)
            assertEquals(signed.pairing?.apikey, dojo.getApiKey())
            assertEquals("test-access", prefs.authorization)
            assertEquals("test-refresh", prefs.refreshToken)
            reference?.let { assertEquals(h.take(40), it, stored) }
            reference = stored
            assertEquals(h.take(40), com.samourai.sentinel.core.DojoReportedVersion.parse(h), prefs.dojoReportedVersion)
            d.dismiss(); idle()
            sheet.dismissAllowingStateLoss(); idle()
        }
    }
}
