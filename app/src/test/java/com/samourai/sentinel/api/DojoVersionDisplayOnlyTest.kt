package com.samourai.sentinel.api

import android.app.Application
import android.content.Context
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.MutableLiveData
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.R
import com.samourai.sentinel.core.DojoReportedVersion
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.db.SentinelCollectionStore
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.helpers.fromJSON
import com.samourai.sentinel.helpers.toJSON
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.tor.TorState
import com.samourai.sentinel.ui.dojo.DojoCredentialsBottomSheet
import com.samourai.sentinel.ui.dojo.DojoPairing
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.dojo.buildPairingClient
import com.samourai.sentinel.ui.dojo.pairingLoginRequest
import com.samourai.sentinel.ui.dojo.readPairingLogin
import com.samourai.sentinel.ui.utils.PrefsUtil
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import java.net.InetAddress
import java.net.Proxy

/**
 * X-Dojo-Version is display only. Whatever the header says, on the pairing
 * login or on any Dojo call through ApiService.callOn, the pairing payload,
 * the Dojo URLs (Tor and clearnet), the API key and the access and refresh
 * tokens stay exactly as they were, and so does every other saved setting.
 * The only thing it may change is the stored version, and only when the
 * header passes the shape rule.
 *
 * Then end to end on the real paired Dojo screen (DojoCredentialsBottomSheet):
 * header present, missing or invalid on the login and on later calls, and
 * the value cleared by a pairing change.
 *
 * Real code against a local MockWebServer. The pairing is a placeholder
 * onion, reached as on a device through the Tor SOCKS proxy, here a local
 * stand-in that forwards every connection to the server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DojoVersionDisplayOnlyTest {

    private lateinit var server: MockWebServer
    private lateinit var prefs: PrefsUtil
    private lateinit var api: ApiService
    private lateinit var dojo: DojoUtility
    private lateinit var socks: SocksToServer
    private val savedEnableTor = mutableListOf<Pair<PrefsUtil, Boolean?>>()
    private val savedPayloads = mutableListOf<Pair<DojoUtility, Any?>>()

    private val onionUrl = "http://" + "c".repeat(56) + ".onion/v2"

    /** Header values a hostile or broken Dojo could send. */
    private fun hostileHeaders(): List<String> = listOf(
        "http://" + "e".repeat(56) + ".onion/v2", // a URL
        onionUrl,
        "e".repeat(56) + ".onion", // an onion host (too long for the shape)
        "e".repeat(16) + ".onion", // onion-like and shape-valid: shown, nothing else
        "127.0.0.1:9050",
        """{"pairing":{"type":"dojo.api","apikey":"evil","url":"http://evil.example/v2"}}""",
        """{"authorizations":{"access_token":"evil","refresh_token":"evil"}}""",
        "x".repeat(10 * 1024),
        "",
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        GlobalContext.getOrNull()?.let { stopKoin() }
        val app = ApplicationProvider.getApplicationContext<Application>()
        prefs = PrefsUtil(app)
        prefs.clearAll()
        prefs.testnet = false
        prefs.enableTor = false
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
        api = KoinJavaComponent.get(ApiService::class.java)
        dojo = KoinJavaComponent.get(DojoUtility::class.java)
        disarmStaleState()
        socks = SocksToServer(server.port)
        setTor(on = true, proxy = socks.proxy)
        DojoReportedVersion.clear(prefs)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        savedEnableTor.forEach { (p, enabled) -> p.enableTor = enabled }
        if (savedPayloads.isNotEmpty()) {
            val payload = DojoUtility::class.java.getDeclaredField("dojoPayload").apply { isAccessible = true }
            savedPayloads.forEach { (d, value) -> payload.set(d, value) }
        }
        dojo.clearDojo()
        setTor(on = false, proxy = null)
        DojoReportedVersion.clear(prefs)
        GlobalContext.getOrNull()?.let { stopKoin() }
        socks.close()
        server.shutdown()
    }

    private val pairedHost = "d".repeat(56) + ".onion"

    private fun localUrl() = "http://$pairedHost/v2"

    private fun localPayload(version: String = "1.19.1") =
        """{"pairing":{"type":"dojo.api","version":"$version","apikey":"synthetic-node-key","url":"${localUrl()}"}}"""

    /** A 200 login answer carrying [headers] as X-Dojo-Version values. */
    private fun loginAnswer(vararg headers: String): MockResponse {
        val r = MockResponse().setResponseCode(200)
            .setBody("""{"authorizations":{"access_token":"access-1","refresh_token":"refresh-1"}}""")
        headers.forEach { r.addHeaderLenient(DojoReportedVersion.HEADER, it) }
        return r
    }

    /**
     * The pairing as the sheet makes it: its login request and answer, then
     * what commitPairing does with them (commitPairedDojo, applyAuthTokens).
     */
    private fun pair(payload: String, vararg headers: String) {
        server.enqueue(loginAnswer(*headers))
        val pairing = fromJSON<DojoPairing>(payload)!!
        buildPairingClient(socks.proxy).newCall(pairingLoginRequest(pairing)).execute().use { answer ->
            val login = readPairingLogin(answer)
            assertNotNull(login)
            dojo.commitPairedDojo(payload, pairing.pairing!!.url, login!!.versionHeader)
            dojo.applyAuthTokens(login.tokens)
        }
        api.setAccessToken(prefs.authorization)
    }

    /** One Dojo call through ApiService.callOn, answered with [headers]. */
    private fun fees(vararg headers: String) {
        val r = MockResponse().setResponseCode(200).setBody("{}")
        headers.forEach { r.addHeaderLenient(DojoReportedVersion.HEADER, it) }
        server.enqueue(r)
        runBlocking { api.getFees().close() }
    }

    /** Everything the header must not touch. */
    private data class Pinned(
        val payload: String?,
        val apiKey: String?,
        val pairingUrl: String?,
        val apiUrl: String?,
        val endpoint: String?,
        val endpointTor: String?,
        val access: String?,
        val refresh: String?,
        val serviceAccess: String?,
        val proxy: Proxy?,
        val otherPrefs: Map<String, Any?>,
    )

    private fun pinned(): Pinned {
        val raw = ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(ApplicationProvider.getApplicationContext<Application>().packageName + "_preferences", Context.MODE_PRIVATE)
            .all.filterKeys { it != DojoReportedVersion.KEY }.toSortedMap()
        val serviceToken = ApiService::class.java.getDeclaredField("ACCESS_TOKEN").apply { isAccessible = true }.get(api) as String?
        return Pinned(
            payload = dojo.exportDojoPayload(),
            apiKey = dojo.getApiKey(),
            pairingUrl = dojo.getPairing()?.url,
            apiUrl = api.getAPIUrl(),
            endpoint = prefs.apiEndPoint,
            endpointTor = prefs.apiEndPointTor,
            access = prefs.authorization,
            refresh = prefs.refreshToken,
            serviceAccess = serviceToken,
            proxy = SentinelTorManager.getProxy(),
            otherPrefs = raw,
        )
    }

    private fun assertPairedTo(payload: String, p: Pinned) {
        assertEquals(fromJSON<DojoPairing>(payload)!!.toJSON(), p.payload)
        assertEquals("synthetic-node-key", p.apiKey)
        assertEquals(localUrl(), p.pairingUrl)
        assertEquals(localUrl(), p.apiUrl)
        assertEquals(localUrl(), p.endpoint)
        assertEquals(localUrl(), p.endpointTor)
        assertEquals("access-1", p.access)
        assertEquals("refresh-1", p.refresh)
    }

    //
    // 1. Display only
    //

    @Test
    fun `a hostile header on the pairing login changes nothing but the version`() {
        pair(localPayload(), "1.0.0")
        val reference = pinned()
        assertPairedTo(localPayload(), reference)
        for (h in hostileHeaders() + listOf("1.29.3")) {
            pair(localPayload(), h)
            val after = pinned()
            assertPairedTo(localPayload(), after)
            assertEquals(h.take(40), reference, after)
            assertEquals(h.take(40), DojoReportedVersion.parse(h), prefs.dojoReportedVersion)
        }
        // Several values at once: none is taken.
        pair(localPayload(), "1.29.3", onionUrl)
        assertEquals(reference, pinned())
        assertNull(prefs.dojoReportedVersion)
    }

    @Test
    fun `a hostile header on a Dojo call changes nothing but the version`() {
        pair(localPayload(), "1.0.0")
        val reference = pinned()
        for (h in hostileHeaders()) {
            fees(h)
            assertEquals(h.take(40), reference, pinned())
            val expected = DojoReportedVersion.parse(h) ?: "1.0.0"
            assertEquals(h.take(40), expected, prefs.dojoReportedVersion)
            // Back to a known value for the next one.
            fees("1.0.0")
        }
        fees("1.29.3", onionUrl)
        fees("1.29.3", "1.29.3")
        assertEquals(reference, pinned())
        assertEquals("1.0.0", prefs.dojoReportedVersion)
        // Requests still go where the pairing says, with its key.
        val last = generateSequence { server.takeRequest(0, java.util.concurrent.TimeUnit.SECONDS) }.last()
        assertEquals(pairedHost, last.getHeader("Host"))
        assertEquals(true, last.path!!.startsWith("/v2/"))
    }

    @Test
    fun `header characters OkHttp would never pass on are refused by the same rule`() {
        // A CR or LF can't arrive inside one header (it ends the header line,
        // and OkHttp refuses to build one), and OkHttp trims surrounding
        // whitespace. The rule refuses them anyway if they ever reach it.
        pair(localPayload(), "1.0.0")
        val reference = pinned()
        for (h in listOf("1.30.0\r\nX-Evil: 1", "1.30.0\n", " 1.30.0", "1.30.0 ", "\t1.30.0")) {
            DojoReportedVersion.observe(prefs, DojoReportedVersion.pairingGeneration(), true, h)
            assertEquals("1.0.0", prefs.dojoReportedVersion)
            assertEquals(reference, pinned())
        }
        DojoReportedVersion.setFromPairingLogin(prefs, "1.30.0\r\nX-Evil: 1")
        assertNull(prefs.dojoReportedVersion)
        assertEquals(reference, pinned())
    }

    //
    // 3. End to end on the real paired Dojo screen
    //

    private fun shownVersion(): String {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java)
            .also { it.get().setTheme(R.style.AppThemeV3) }.setup().get()
        val sheet = DojoCredentialsBottomSheet()
        sheet.show(activity.supportFragmentManager, "dojo_credentials")
        shadowOf(Looper.getMainLooper()).idle()
        val text = sheet.requireView().findViewById<TextView>(R.id.dojoVersionValue).text.toString()
        sheet.dismissAllowingStateLoss()
        shadowOf(Looper.getMainLooper()).idle()
        return text
    }

    @Test
    fun `the paired screen shows the login's version, then a different one a Dojo call reports`() {
        pair(localPayload(), "1.29.3")
        assertEquals("1.29.3", shownVersion())
        fees("1.30.0")
        assertEquals("1.30.0", shownVersion())
        fees("1.30.0")
        assertEquals("1.30.0", shownVersion())
    }

    @Test
    fun `a missing or invalid header keeps what the screen shows`() {
        pair(localPayload(), "1.29.3")
        fees()
        assertEquals("1.29.3", shownVersion())
        for (bad in listOf("1.30 beta", "1.30/0", "x".repeat(33), "\u0661.30.0", "")) {
            fees(bad)
            assertEquals(bad.take(40), "1.29.3", shownVersion())
        }
        fees("1.30.0", "1.31.0")
        assertEquals("1.29.3", shownVersion())
    }

    @Test
    fun `with a missing or invalid login header the screen shows the pairing code's version`() {
        for (login in listOf(arrayOf(), arrayOf("1.29 3"), arrayOf("x".repeat(33)), arrayOf("1.29.3", "1.29.4"))) {
            pair(localPayload("1.19.1"), *login)
            assertNull(prefs.dojoReportedVersion)
            assertEquals("1.19.1", shownVersion())
        }
        // The next good answer replaces the fallback.
        fees("1.29.3")
        assertEquals("1.29.3", shownVersion())
    }

    @Test
    fun `a pairing change clears the value, and the screen falls back to the new pairing code`() {
        pair(localPayload("1.19.1"), "1.29.3")
        assertEquals("1.29.3", shownVersion())
        dojo.setDojoPayload(localPayload("1.20.0"))
        assertNull(prefs.dojoReportedVersion)
        assertEquals("1.20.0", shownVersion())
        // An answer to the new pairing sets it again.
        fees("1.29.4")
        assertEquals("1.29.4", shownVersion())
        dojo.import(org.json.JSONObject(localPayload("1.21.0")))
        assertEquals("1.21.0", shownVersion())
    }

    //
    // Harness (as in DojoVersionHeaderTest)
    //

    /** A SOCKS5 stand-in for Tor: every CONNECT goes to the local server. */
    private class SocksToServer(private val serverPort: Int) : java.io.Closeable {
        private val listener = java.net.ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val proxy = Proxy(Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", listener.localPort))

        init {
            Thread {
                while (!listener.isClosed) {
                    val client = try { listener.accept() } catch (_: Exception) { break }
                    Thread { relay(client) }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true }.start()
        }

        private fun relay(client: java.net.Socket) {
            try {
                val input = java.io.DataInputStream(client.getInputStream())
                val out = client.getOutputStream()
                input.readByte(); repeat(input.readUnsignedByte()) { input.readByte() }
                out.write(byteArrayOf(5, 0)); out.flush()
                input.readByte(); input.readByte(); input.readByte()
                when (input.readUnsignedByte()) {
                    1 -> input.readFully(ByteArray(4))
                    3 -> input.readFully(ByteArray(input.readUnsignedByte()))
                    4 -> input.readFully(ByteArray(16))
                }
                input.readFully(ByteArray(2))
                out.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0)); out.flush()
                val upstream = java.net.Socket(InetAddress.getByName("127.0.0.1"), serverPort)
                Thread {
                    try { upstream.getInputStream().copyTo(out) } catch (_: Exception) {}
                    try { client.close() } catch (_: Exception) {}
                }.apply { isDaemon = true }.start()
                try { input.copyTo(upstream.getOutputStream()) } catch (_: Exception) {}
                try { upstream.close() } catch (_: Exception) {}
            } catch (_: Exception) {
                try { client.close() } catch (_: Exception) {}
            }
        }

        override fun close() = listener.close()
    }

    private fun disarmStaleState() {
        savedEnableTor.clear()
        savedPayloads.clear()
        val prefsTargets = linkedSetOf(prefs)
        statePrefs()?.let { prefsTargets.add(it) }
        prefsTargets.forEach {
            savedEnableTor += it to it.enableTor
            it.enableTor = false
        }
        val dojos = linkedSetOf<DojoUtility>()
        stateDojo()?.let { if (it !== dojo) dojos.add(it) }
        val payload = DojoUtility::class.java.getDeclaredField("dojoPayload").apply { isAccessible = true }
        dojos.forEach {
            savedPayloads += it to payload.get(it)
            payload.set(it, null)
        }
    }

    private fun statePrefs(): PrefsUtil? = stateLazy("prefsUtil\$delegate")

    private fun stateDojo(): DojoUtility? = stateLazy("dojoUtility\$delegate")

    private fun <T> stateLazy(fieldName: String): T? {
        val field = SentinelState::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        val lazy = field.get(null) as kotlin.Lazy<*>
        if (!lazy.isInitialized()) return null
        @Suppress("UNCHECKED_CAST")
        return lazy.value as T
    }

    private fun setTor(on: Boolean, proxy: Proxy?) {
        SentinelTorManager::class.java.getDeclaredField("proxy").apply { isAccessible = true }
            .set(SentinelTorManager, if (on) proxy else null)
        val liveField = SentinelTorManager::class.java.getDeclaredField("torStateLiveDataPrivate")
        liveField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val live = liveField.get(SentinelTorManager) as MutableLiveData<TorState>
        live.value = TorState().apply {
            state = if (on) EnumTorState.ON else EnumTorState.OFF
            progressIndicator = if (on) 100 else 0
        }
    }
}
