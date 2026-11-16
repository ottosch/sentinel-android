package com.samourai.sentinel.helpers

import android.app.Application
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.db.SentinelCollectionStore
import com.samourai.sentinel.data.db.SentinelRoomDb
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.data.repository.FeeRepository
import com.samourai.sentinel.data.repository.LabelRepository
import com.samourai.sentinel.data.repository.TransactionsRepository
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.dojo.RescanCooldown
import com.samourai.sentinel.ui.utils.PrefsUtil
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import timber.log.Timber
import java.io.File
import java.util.concurrent.Executor

/**
 * #567. The nested documents are deep enough to kill Android's org.json
 * and shallow enough that the unit-test jar would parse a depth of 33.
 * A refusal whose message is [SafeJson.DEPTH_MESSAGE], or an
 * [UnsafeJsonException] with that message and no cause, is the pre-scan.
 * A [JSONException] from the jar is not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OrgJsonCrashReplayTest {

    private val direct = Executor { it.run() }
    private lateinit var server: MockWebServer
    private lateinit var prefs: PrefsUtil
    private lateinit var db: SentinelRoomDb
    private lateinit var repo: TransactionsRepository
    private lateinit var fees: FeeRepository
    private lateinit var api: ApiService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        if (GlobalContext.getOrNull() != null) stopKoin()
        val app = ApplicationProvider.getApplicationContext<Application>()
        File(app.filesDir, "app_wallet/dojo.payload").delete()
        File(app.filesDir, "app_wallet/dojo.payload.bak").delete()
        DojoUtility.skipAsyncPayloadRead = true
        prefs = PrefsUtil(app)
        prefs.enableTor = false
        prefs.testnet = false
        prefs.whirlpoolAutoLabels = false
        prefs.authorization = ""
        prefs.refreshToken = ""
        prefs.apiEndPoint = server.url("/").toString().trimEnd('/')
        prefs.apiEndPointTor = null
        db = Room.inMemoryDatabaseBuilder(app, SentinelRoomDb::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(direct)
            .setTransactionExecutor(direct)
            .build()
        startKoin {
            androidContext(app)
            modules(
                module {
                    single { prefs }
                    single { SentinelCollectionStore() }
                    single { CollectionRepository() }
                    single { DojoUtility() }
                    single { ApiService() }
                    single { db }
                    single { db.txDao() }
                    single { db.utxoDao() }
                    single { db.utxoLabelDao() }
                    single { db.labelEntryDao() }
                    single { db.txEntropyDao() }
                    single { LabelRepository() }
                    single { FeeRepository() }
                    single { TransactionsRepository() }
                },
            )
        }
        repo = org.koin.java.KoinJavaComponent.get(TransactionsRepository::class.java)
        fees = org.koin.java.KoinJavaComponent.get(FeeRepository::class.java)
        api = org.koin.java.KoinJavaComponent.get(ApiService::class.java)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        if (this::repo.isInitialized) repo.walletFetchOverride = null
        DojoUtility.skipAsyncPayloadRead = false
        if (this::api.isInitialized) {
            api.beforeNewCall = null
            api.client.dispatcher.executorService.shutdown()
            api.client.connectionPool.evictAll()
        }
        if (this::server.isInitialized) server.shutdown()
        shadowOf(Looper.getMainLooper()).idle()
        if (this::db.isInitialized) db.close()
        if (GlobalContext.getOrNull() != null) stopKoin()
    }

    @Test(timeout = 60_000)
    fun walletFetchRefusesDepthThirtyThreeAndKeepsTheBalance() {
        val collection = PubKeyCollection(
            collectionLabel = "one",
            pubs = arrayListOf(
                PubKeyModel(
                    pubKey = "xpub-a",
                    label = "xpub-a",
                    type = AddressTypes.BIP84,
                    balance = 11L,
                ),
            ),
        )
        collections().addNew(collection)
        // Depth 33. The unit-test jar parses this. Gson would too, and
        // the balance below would be applied. The pre-scan must refuse
        // it first.
        repo.walletFetchOverride = { _ ->
            response(200, walletBomb(balance = 999L))
        }
        try {
            runBlocking { repo.fetchFromServer(collection.id) }
            fail("a depth-33 wallet body was applied")
        } catch (t: Throwable) {
            assertFalse(t is Error)
            assertEquals("wallet response not usable", t.message)
        }
        val stored = collections().findById(collection.id)!!
        assertEquals(11L, stored.pubs.single().balance)
    }

    @Test(timeout = 30_000)
    fun feeFetchRefusesDepthThirtyThree() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(nestedObject(33)))
        try {
            runBlocking { fees.getDynamicFees() }
            fail("a depth-33 fee body was parsed")
        } catch (t: Throwable) {
            assertFalse(t is Error)
            assertEquals(SafeJson.DEPTH_MESSAGE, t.message)
        }
    }

    @Test(timeout = 30_000)
    fun rescanCooldownDiscardsANestedStoreAndKeepsASingleBom() {
        val xpub = "xpub-replay"
        val now = 1_700_000_000_000L
        val logged = CopyOnWriteArrayList<Throwable>()
        val tree = recordingTree(logged)
        Timber.plant(tree)
        try {
            prefs.rescanCooldowns = nestedObject(33)
            shadowOf(Looper.getMainLooper()).idle()
            val discarded = RescanCooldown.load(prefs)
            assertEquals(0, discarded.length())
            val refusal = logged.filterIsInstance<UnsafeJsonException>().single()
            assertEquals(SafeJson.DEPTH_MESSAGE, refusal.message)
            assertNull(refusal.cause)

            logged.clear()
            val key = RescanCooldown.fingerprint(xpub)
            prefs.rescanCooldowns = "\uFEFF{\"$key\":$now}"
            shadowOf(Looper.getMainLooper()).idle()
            val kept = RescanCooldown.load(prefs)
            assertEquals(now, kept.getLong(key))
            assertTrue(logged.none { it is UnsafeJsonException })

            prefs.rescanCooldowns = "\uFEFF\uFEFF{\"$key\":$now}"
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, RescanCooldown.load(prefs).length())
            val second = logged.filterIsInstance<UnsafeJsonException>().single()
            assertEquals(SafeJson.SECOND_BOM_MESSAGE, second.message)
            assertNull(second.cause)
        } finally {
            Timber.uproot(tree)
        }
    }

    /**
     * Confirmed chain: a 401 makes
     * [com.samourai.sentinel.api.TokenAuthenticator.authenticate] call
     * [ApiService.refreshDojoAuth], then performDojoRefresh, then
     * [DojoUtility.setAuthToken]. The refresh body is about 4000 nested
     * arrays. On a device the parse runs on OkHttp's 1 MiB dispatcher
     * stack, and that document is enough to overflow Android's org.json
     * there. This call is made on a 256 KiB stack. The refresh must fail
     * cleanly, and the failure that was caught must be the pre-scan.
     */
    @Test(timeout = 30_000)
    fun tokenRefreshOnASmallStackRefusesFourThousandNestedArrays() {
        driveRefresh(refreshBomb(), SafeJson.DEPTH_MESSAGE)
    }

    /**
     * The same chain, with the nesting hidden from a double-quote-only
     * scan by a block comment: `{"authorizations":{"access_token":/*"*/[…]}}`.
     * AOSP org.json skips the comment and then recurses. The pre-scan
     * rejects the comment before that parser runs.
     */
    @Test(timeout = 30_000)
    fun tokenRefreshRejectsNestingHiddenByAComment() {
        driveRefresh(refreshBombHiddenInComment(), SafeJson.SYNTAX_MESSAGE)
    }

    private fun driveRefresh(refreshBody: String, expectedMessage: String) {
        prefs.refreshToken = "refresh-old"
        prefs.authorization = "expired-token"
        shadowOf(Looper.getMainLooper()).idle()
        val seen = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                seen.add(path)
                return when {
                    path.startsWith("/auth/refresh") ->
                        MockResponse().setResponseCode(200).setBody(refreshBody)
                    path.startsWith("/wallet") ->
                        MockResponse().setResponseCode(401).setBody("expired")
                    else -> error("unexpected $path")
                }
            }
        }
        val logged = CopyOnWriteArrayList<Throwable>()
        val tree = recordingTree(logged)
        Timber.plant(tree)
        val responseBox = AtomicReference<Response>()
        val problem = AtomicReference<Throwable>()
        val thread = Thread(
            null,
            Runnable {
                try {
                    responseBox.set(runBlocking { api.getWallet("xpub-test") })
                } catch (t: Throwable) {
                    problem.set(t)
                }
            },
            "token-refresh-small-stack",
            256L * 1024,
        )
        thread.isDaemon = true
        try {
            thread.start()
            thread.join(20_000)
            assertFalse("token refresh did not finish", thread.isAlive)
            assertNull(problem.get()?.toString(), problem.get())
            val response = responseBox.get()
            try {
                assertEquals(401, response.code)
            } finally {
                response.close()
            }
            assertTrue(seen.any { it.startsWith("/wallet") })
            assertTrue(seen.any { it.startsWith("/auth/refresh") })
            assertTrue(seen.none { it.startsWith("/auth/login") })
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("expired-token", prefs.authorization)
            val refusal = logged.filterIsInstance<UnsafeJsonException>().single()
            assertEquals(expectedMessage, refusal.message)
            assertNull(refusal.cause)
            assertTrue(logged.none { it is StackOverflowError })
        } finally {
            Timber.uproot(tree)
        }
    }

    private fun recordingTree(into: CopyOnWriteArrayList<Throwable>) = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (t != null) into.add(t)
        }
    }

    private fun collections(): CollectionRepository =
        org.koin.java.KoinJavaComponent.get(CollectionRepository::class.java)

    private fun response(code: Int, body: String): Response {
        return Response.Builder()
            .request(Request.Builder().url("http://127.0.0.1/wallet").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("ok")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }

    /** A wallet Gson would accept, with an extra array nested to depth 33. */
    private fun walletBomb(balance: Long): String {
        val extra = buildString {
            repeat(32) { append('[') }
            append('1')
            repeat(32) { append(']') }
        }
        return """
            {"info":{"latest_block":{"hash":"tip","height":100,"time":1700000000},"fees":null},
            "addresses":null,"txs":[],"wallet":{"final_balance":$balance},
            "unspent_outputs":null,"extra":$extra}
        """.trimIndent()
    }

    private fun nestedObject(depth: Int): String = buildString {
        repeat(depth) { append("{\"k\":") }
        append('1')
        repeat(depth) { append('}') }
    }

    /** `{"authorizations":` plus about 4000 nested arrays. */
    private fun refreshBomb(): String {
        val nested = buildString {
            repeat(4000) { append('[') }
            append('1')
            repeat(4000) { append(']') }
        }
        return """{"authorizations":$nested}"""
    }

    /** About 10,000 arrays after a comment that a double-quote scan treats as a string. */
    private fun refreshBombHiddenInComment(): String {
        val nested = buildString {
            repeat(10_000) { append('[') }
            append('1')
            repeat(10_000) { append(']') }
        }
        return """{"authorizations":{"access_token":/*"*/$nested}}"""
    }
}
