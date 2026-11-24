package com.samourai.sentinel.api

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.data.db.SentinelCollectionStore
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.helpers.SafeJson
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.util.ImportTooLargeException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
import java.io.File
import java.net.InetAddress

/**
 * /txs uses the same body cap as /wallet. A /wallet path is reported
 * as [ApiService.DojoReplyTooLarge]. /txs is not that path, so the cap
 * is reported as [ImportTooLargeException].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GetTxsResponseLimitTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ApiService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        GlobalContext.getOrNull()?.let { stopKoin() }
        val app = ApplicationProvider.getApplicationContext<Application>()
        File(app.filesDir, "app_wallet/dojo.payload").delete()
        File(app.filesDir, "app_wallet/dojo.payload.bak").delete()
        DojoUtility.skipAsyncPayloadRead = true
        val prefs = PrefsUtil(app)
        prefs.enableTor = false
        prefs.testnet = false
        prefs.authorization = ""
        prefs.refreshToken = ""
        prefs.apiEndPointTor = null
        prefs.apiEndPoint = server.url("/").toString().trimEnd('/')
        startKoin {
            androidContext(app)
            modules(
                module {
                    single { prefs }
                    single { SentinelCollectionStore() }
                    single { CollectionRepository() }
                    single { DojoUtility() }
                    single { ApiService() }
                },
            )
        }
        api = KoinJavaComponent.get(ApiService::class.java)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        DojoUtility.skipAsyncPayloadRead = false
        if (this::api.isInitialized) {
            runCatching {
                api.client.dispatcher.executorService.shutdown()
                api.client.connectionPool.evictAll()
            }
        }
        if (this::server.isInitialized) server.shutdown()
        shadowOf(Looper.getMainLooper()).idle()
        if (GlobalContext.getOrNull() != null) stopKoin()
    }

    @Test(timeout = 30_000)
    fun getTxsRejectsABodyOverTheWalletCap() {
        val cap = SafeJson.WALLET_MAX_BYTES
        server.enqueue(MockResponse().setBody("x".repeat(cap + 1)))
        try {
            runBlocking { api.getTxs("xpub-test", 0, 1) }
            fail("oversized txs body should be refused")
        } catch (e: ImportTooLargeException) {
            assertEquals(cap.toLong(), e.limit)
            assertEquals("bytes", e.unit)
        }
    }

    @Test(timeout = 30_000)
    fun getTxsAcceptsABodyInsideTheWalletCap() {
        val inside = "a".repeat(SafeJson.TX_MAX_BYTES + 1)
        assertTrue(inside.length < SafeJson.WALLET_MAX_BYTES)
        server.enqueue(MockResponse().setBody(inside))
        val response = runBlocking { api.getTxs("xpub-test", 0, 1) }
        try {
            assertTrue(response.isSuccessful)
            assertEquals(inside, response.body!!.string())
        } finally {
            response.close()
        }
    }
}
