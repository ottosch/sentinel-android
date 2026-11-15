package com.samourai.sentinel.api

import android.app.Application
import android.os.Looper
import androidx.lifecycle.MutableLiveData
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.db.SentinelCollectionStore
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.tor.TorState
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.utils.PrefsUtil
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
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
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * getTxHex used to build a 120s timeout client and discard it. The call
 * has to keep the client [ApiService.buildClient] returns: clearnet
 * timeouts when Tor is off, and the SOCKS proxy plus the Tor ceilings
 * when Tor is on. A second client with those 120s timeouts would replace
 * the Tor settings. The body cap is covered by [PrevTxFetchLimitTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GetTxHexClientTest {

    private lateinit var server: MockWebServer
    private lateinit var prefs: PrefsUtil
    private lateinit var api: ApiService
    private val savedEnableTor = mutableListOf<Pair<PrefsUtil, Boolean?>>()
    private val savedPayloads = mutableListOf<Pair<DojoUtility, Any?>>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        ensureRuntime(server.url("/").toString())
        disarmTorRequirement()
    }

    @After
    fun tearDown() {
        if (this::api.isInitialized) api.beforeNewCall = null
        savedEnableTor.forEach { (prefs, enabled) -> prefs.enableTor = enabled }
        if (savedPayloads.isNotEmpty()) {
            val payload = DojoUtility::class.java.getDeclaredField("dojoPayload")
            payload.isAccessible = true
            savedPayloads.forEach { (dojo, value) -> payload.set(dojo, value) }
        }
        setTor(on = false, proxy = null)
        GlobalContext.getOrNull()?.let { stopKoin() }
        server.shutdown()
    }

    @Test
    fun clearnetFetchUsesTheBuiltClientTimeouts() {
        val body = """{"status":"ok","data":"51"}"""
        server.enqueue(MockResponse().setBody(body))
        val seen = captureClient()
        val response = runBlocking { api.getTxHex("ab".repeat(32)) }
        assertEquals(body, response.body!!.string())
        val client = seen()
        assertSame(api.client, client)
        assertEquals(60_000, client.callTimeoutMillis)
        assertEquals(90_000, client.readTimeoutMillis)
        assertEquals(120_000, client.connectTimeoutMillis)
        assertNull(client.proxy)
    }

    @Test
    fun torFetchKeepsTheProxyAndTorTimeouts() {
        val socks = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 9050))
        // Tor-on getAPIUrl reads the onion endpoint. The hook stops the
        // call before that URL is dialed, so it only has to parse.
        prefs.apiEndPointTor = server.url("/").toString()
        setTor(on = true, proxy = socks)
        val seen = captureClient(stop = true)
        try {
            runBlocking { api.getTxHex("cd".repeat(32)) }
            fail("the hook should stop the call before a socket opens")
        } catch (expected: StopBeforeSocket) {
            // The client is chosen before the hook.
        }
        val client = seen()
        assertSame(api.client, client)
        assertEquals(socks, client.proxy)
        assertEquals(0, client.callTimeoutMillis)
        assertEquals(180_000, client.readTimeoutMillis)
        assertEquals(180_000, client.writeTimeoutMillis)
        assertEquals(45_000, client.connectTimeoutMillis)
        assertFalse(client.retryOnConnectionFailure)
    }

    private fun captureClient(stop: Boolean = false): () -> OkHttpClient {
        var seen: OkHttpClient? = null
        api.beforeNewCall = { client, _ ->
            seen = client
            if (stop) throw StopBeforeSocket()
        }
        return { seen ?: error("getTxHex did not capture a client") }
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

    private class StopBeforeSocket : IOException("stop before socket")
}
