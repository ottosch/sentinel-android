package com.samourai.sentinel.api

import android.app.Application
import android.os.Looper
import androidx.lifecycle.MutableLiveData
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.core.DojoReportedVersion
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.db.SentinelCollectionStore
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.tor.TorState
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.dojo.buildPairingClient
import com.samourai.sentinel.helpers.fromJSON
import com.samourai.sentinel.ui.dojo.DojoPairing
import com.samourai.sentinel.ui.dojo.pairingLoginRequest
import com.samourai.sentinel.ui.dojo.readPairingLogin
import com.samourai.sentinel.ui.utils.Preferences
import com.samourai.sentinel.ui.utils.PrefsUtil
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.Proxy
import kotlin.reflect.KProperty

/**
 * The Dojo's X-Dojo-Version header, read through the real code paths
 * against a local MockWebServer with Tor off (harness as in
 * [GetTxHexClientTest]): Dojo calls through ApiService, the pairing
 * login's answer into the pairing commit, every pairing change clearing
 * the value, and answers that left before a re-pair being dropped.
 * No extra requests. Placeholder onions only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DojoVersionHeaderTest {

    private lateinit var server: MockWebServer
    private lateinit var prefs: PrefsUtil
    private lateinit var api: ApiService
    private lateinit var dojo: DojoUtility
    private val savedEnableTor = mutableListOf<Pair<PrefsUtil, Boolean?>>()
    private val savedPayloads = mutableListOf<Pair<DojoUtility, Any?>>()
    private var writes = 0
    private val countWrites = object : Preferences.SharedPrefsListener {
        override fun onSharedPrefChanged(property: KProperty<*>) {
            if (property.name == DojoReportedVersion.KEY) writes++
        }
    }

    private val newUrl = "http://" + "b".repeat(56) + ".onion/test/v2"
    private val newPayload =
        """{"pairing":{"type":"dojo.api","version":"1.19.1","apikey":"synthetic-node-key","url":"$newUrl"}}"""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        ensureRuntime(server.url("/").toString())
        disarmTorRequirement()
        dojo = KoinJavaComponent.get(DojoUtility::class.java)
        DojoReportedVersion.clear(prefs)
        prefs.addListener(countWrites)
        writes = 0
    }

    @After
    fun tearDown() {
        prefs.removeListener(countWrites)
        savedEnableTor.forEach { (prefs, enabled) -> prefs.enableTor = enabled }
        if (savedPayloads.isNotEmpty()) {
            val payload = DojoUtility::class.java.getDeclaredField("dojoPayload")
            payload.isAccessible = true
            savedPayloads.forEach { (dojo, value) -> payload.set(dojo, value) }
        }
        setTor(on = false, proxy = null)
        DojoReportedVersion.clear(prefs)
        GlobalContext.getOrNull()?.let { stopKoin() }
        server.shutdown()
    }

    private fun answer(code: Int = 200, version: String? = null): MockResponse {
        val response = MockResponse().setResponseCode(code).setBody("{}")
        version?.let { response.addHeader(DojoReportedVersion.HEADER, it) }
        return response
    }

    private fun fees(code: Int = 200, version: String? = null) {
        server.enqueue(answer(code, version))
        runBlocking { api.getFees().close() }
    }

    /** One fees call whose answer is held until [duringCall] has run. */
    private fun feesWhile(version: String, duringCall: () -> Unit) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                duringCall()
                return answer(version = version)
            }
        }
        runBlocking { api.getFees().close() }
    }

    //
    // Dojo calls through ApiService.callOn
    //

    @Test
    fun `every Dojo answer is read, and a different version replaces the stored one`() {
        fees(version = "1.29.0")
        assertEquals("1.29.0", prefs.dojoReportedVersion)
        fees(version = "1.30.0")
        assertEquals("1.30.0", prefs.dojoReportedVersion)
        assertEquals(2, writes)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `the same version writes nothing`() {
        fees(version = "1.29.0")
        writes = 0
        fees(version = "1.29.0")
        fees(version = "1.29.0")
        assertEquals("1.29.0", prefs.dojoReportedVersion)
        assertEquals(0, writes)
    }

    @Test
    fun `a failed call, no header or a bad header keeps the stored value`() {
        fees(version = "1.29.0")
        writes = 0
        fees(code = 500, version = "1.30.0")
        fees(version = null)
        fees(version = "1.30.0<script>")
        fees(version = "x".repeat(40) + "1")
        assertEquals("1.29.0", prefs.dojoReportedVersion)
        assertEquals(0, writes)
        assertEquals(5, server.requestCount)
    }

    //
    // The re-pair race: an answer that left for the old Dojo comes back
    // after the pairing changed.
    //

    @Test
    fun `an old Dojo's answer after a re-pair is not saved, and the login's value wins`() {
        fees(version = "1.0.0")
        feesWhile(version = "1.0.0") {
            dojo.commitPairedDojo(newPayload, newUrl, "1.29.3")
        }
        assertEquals("1.29.3", prefs.dojoReportedVersion)
        assertEquals(newUrl, prefs.apiEndPoint)
    }

    @Test
    fun `an old Dojo's answer landing between the endpoint switch and the login save is not saved`() {
        feesWhile(version = "1.0.0") {
            dojo.setDojoPayload(newPayload)
            prefs.apiEndPointTor = newUrl
            prefs.apiEndPoint = newUrl
        }
        assertNull(prefs.dojoReportedVersion)
        DojoReportedVersion.setFromPairingLogin(prefs, "1.29.3")
        assertEquals("1.29.3", prefs.dojoReportedVersion)
    }

    @Test
    fun `an answer sent before a pairing change is not saved even if the URL is unchanged`() {
        // What every pairing change does (DojoUtility.notePairingChanged),
        // here with the endpoint left as it was.
        feesWhile(version = "1.0.0") {
            DojoReportedVersion.clear(prefs)
        }
        assertEquals(server.url("/").toString(), prefs.apiEndPoint)
        assertNull(prefs.dojoReportedVersion)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `an answer is not saved when the Dojo endpoint changed while it was out`() {
        // A restore can rewrite the endpoints without a pairing change.
        val other = MockWebServer()
        other.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            feesWhile(version = "1.0.0") {
                prefs.apiEndPoint = other.url("/").toString()
            }
            assertNull(prefs.dojoReportedVersion)
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun `an answer is not saved when the endpoint moved to another path on the same host`() {
        // Same host and port (a mainnet /v2 and a testnet /test/v2 mount),
        // so only the URL itself tells the two apart.
        feesWhile(version = "1.0.0") {
            prefs.apiEndPoint = server.url("/test/v2").toString()
        }
        assertNull(prefs.dojoReportedVersion)
    }

    @Test
    fun `an answer that came from another host is not saved`() {
        val other = MockWebServer()
        other.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            other.enqueue(answer(version = "6.6.6"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/fees").toString()))
            runBlocking { api.getFees().close() }
            assertNull(prefs.dojoReportedVersion)
        } finally {
            other.shutdown()
        }
    }

    //
    // The pairing login: setDojoPost's request and answer, then the
    // pairing commit (commitPairing -> DojoUtility.commitPairedDojo).
    //

    private fun pairingLogin(version: String?): String? {
        val response = MockResponse().setResponseCode(200)
            .setBody("""{"authorizations":{"access_token":"access-1","refresh_token":"refresh-1"}}""")
        version?.let { response.addHeader(DojoReportedVersion.HEADER, it) }
        server.enqueue(response)
        val localPairing = fromJSON<DojoPairing>(
            """{"pairing":{"type":"dojo.api","version":"1.19.1","apikey":"synthetic-node-key","url":"${server.url("/v2")}"}}"""
        )!!
        val client = buildPairingClient(Proxy.NO_PROXY)
        client.newCall(pairingLoginRequest(localPairing)).execute().use { answer ->
            val login = readPairingLogin(answer)
            assertNotNull(login)
            assertEquals("access-1", login!!.tokens.accessToken)
            return login.versionHeader
        }
    }

    @Test
    fun `the pairing login's header is saved for the new pairing`() {
        DojoReportedVersion.setFromPairingLogin(prefs, "1.0.0")
        val header = pairingLogin("1.29.3")
        assertEquals("/v2/auth/login", server.takeRequest().path)
        dojo.commitPairedDojo(newPayload, newUrl, header)
        assertEquals("1.29.3", prefs.dojoReportedVersion)
        assertEquals(newUrl, prefs.apiEndPoint)
        assertEquals(newUrl, prefs.apiEndPointTor)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a pairing login with no header drops the old pairing's version`() {
        DojoReportedVersion.setFromPairingLogin(prefs, "1.0.0")
        dojo.commitPairedDojo(newPayload, newUrl, pairingLogin(null))
        assertNull(prefs.dojoReportedVersion)
        // The screen falls back to the pairing code's version.
        assertEquals("1.19.1", DojoReportedVersion.display(prefs.dojoReportedVersion, dojo.getPairing()?.version))
    }

    @Test
    fun `a rejected or failed pairing login yields nothing to commit`() {
        val client = buildPairingClient(Proxy.NO_PROXY)
        val localPairing = fromJSON<DojoPairing>(
            """{"pairing":{"type":"dojo.api","apikey":"k","url":"${server.url("/v2")}"}}"""
        )!!
        for (r in listOf(
            MockResponse().setResponseCode(401).addHeader(DojoReportedVersion.HEADER, "1.29.3"),
            MockResponse().setResponseCode(200).setBody("{}").addHeader(DojoReportedVersion.HEADER, "1.29.3"),
            MockResponse().setResponseCode(500).addHeader(DojoReportedVersion.HEADER, "1.29.3"),
        )) {
            server.enqueue(r)
            client.newCall(pairingLoginRequest(localPairing)).execute().use { assertNull(readPairingLogin(it)) }
        }
    }

    //
    // Every pairing change clears the value.
    //

    private fun seedStoredVersion() {
        DojoReportedVersion.setFromPairingLogin(prefs, "1.0.0")
    }

    private fun assertCleared(path: String, before: Long) {
        assertNull(path, prefs.dojoReportedVersion)
        assertNotEquals(path, before, DojoReportedVersion.pairingGeneration())
    }

    @Test
    fun `every pairing change clears the stored version`() {
        seedStoredVersion()
        var before = DojoReportedVersion.pairingGeneration()
        dojo.setDojoPayload(newPayload)
        assertCleared("setDojoPayload", before)

        seedStoredVersion()
        before = DojoReportedVersion.pairingGeneration()
        dojo.import(JSONObject(newPayload))
        assertCleared("import", before)

        seedStoredVersion()
        before = DojoReportedVersion.pairingGeneration()
        dojo.clearDojo()
        assertCleared("clearDojo", before)

        // Restore (setDojo): the login it makes cannot connect here; the
        // value is cleared before that login is sent.
        seedStoredVersion()
        before = DojoReportedVersion.pairingGeneration()
        val unreachable =
            """{"pairing":{"type":"dojo.api","version":"1.19.1","apikey":"synthetic-node-key","url":"https://127.0.0.1:1/v2"}}"""
        runBlocking {
            try {
                withTimeout(30_000) { dojo.setDojo(unreachable).close() }
            } catch (_: Exception) {
            }
        }
        assertCleared("setDojo", before)
    }

    private fun ensureRuntime(baseUrl: String) {
        GlobalContext.getOrNull()?.let { stopKoin() }
        val app = ApplicationProvider.getApplicationContext<Application>()
        prefs = PrefsUtil(app)
        prefs.testnet = false
        prefs.enableTor = false
        prefs.apiEndPoint = baseUrl
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
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * [SentinelState] keeps the first prefs and Dojo it resolved. A
     * previous test can leave Tor required on those instances, and
     * [ApiService.buildClient] then refuses a direct client.
     */
    private fun disarmTorRequirement() {
        savedEnableTor.clear()
        savedPayloads.clear()
        val prefsTargets = linkedSetOf(prefs)
        statePrefs()?.let { prefsTargets.add(it) }
        prefsTargets.forEach {
            savedEnableTor += it to it.enableTor
            it.enableTor = false
        }

        val dojos = linkedSetOf(KoinJavaComponent.get(DojoUtility::class.java))
        stateDojo()?.let { dojos.add(it) }
        val payload = DojoUtility::class.java.getDeclaredField("dojoPayload")
        payload.isAccessible = true
        dojos.forEach {
            savedPayloads += it to payload.get(it)
            payload.set(it, null)
        }
        setTor(on = false, proxy = null)
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
        val proxyField = SentinelTorManager::class.java.getDeclaredField("proxy")
        proxyField.isAccessible = true
        proxyField.set(SentinelTorManager, if (on) proxy else null)

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
