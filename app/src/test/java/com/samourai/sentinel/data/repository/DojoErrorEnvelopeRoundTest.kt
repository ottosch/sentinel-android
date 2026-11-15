package com.samourai.sentinel.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.core.SyncState
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.db.SentinelCollectionStore
import com.samourai.sentinel.data.db.SentinelRoomDb
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.home.SyncRoundLedger
import com.samourai.sentinel.ui.home.SyncRoundTerminal
import com.samourai.sentinel.ui.utils.PrefsUtil
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #522 (fixed in #531): a Dojo body whose status is "error" used to be
 * laundered through a fake CancellationException, which the round loop's
 * real-cancellation arm rethrew - one failing collection aborted the whole
 * round and the finally block posted "Sync interrupted." over the #506
 * partial-failure banner.
 *
 * That path had zero CI coverage: none of the 1030 green tests in #531's
 * run exercised the new DojoApiError throw. This suite pins it four ways:
 *
 *  1. behavioral - a REAL TransactionsRepository.fetchFromServer against a
 *     loopback MockWebServer (the DojoAuthLoginLoopTest harness) answering
 *     500 {"status":"error",...} for exactly one pub: the throw escapes as
 *     ApiService.DojoApiError whose message is the fixed local string.
 *     The server's error text is not in the message or toString.
 *  2. round accounting, BOTH round orders - ledger + SyncRoundTerminal over
 *     two collections, faulted first and faulted last (the device leg's two
 *     cases): failed=1 of total=2 posts the Dojo-error banner and clears
 *     the external-arm signature.
 *  3. the healthy sibling still lands - its row reaches the real (in-memory)
 *     tx table while the faulted collection writes nothing.
 *  4. source gates (EmptyReconcileTest / TxHistoryPagingTest style) - the
 *     envelope arm stays a DojoApiError throw, nothing in fetchFromServer
 *     throws CancellationException, and syncCollections keeps its
 *     CancellationException arm ahead of the Exception arm.
 *
 * Limitation, on record (#103 catch-ladder precedent): the round loop in
 * legs 2-3 is a shape-verbatim replica of HomeViewModel.syncCollections,
 * not the executed loop - no test in the tree constructs HomeViewModel.
 * The loop body's own text is pinned by SyncRoundNetworkScopeTest and
 * SyncRoundOwnEmissionTest; the replica cannot go red against it. The
 * repository, ledger, terminal, Room tables, and HTTP are all real.
 *
 * Lifecycle, learned the honest way: JUnit builds a fresh instance of
 * this class for every test method and Robolectric a fresh Application
 * per test, so the fixture is per-test. setUp rebuilds the server, the
 * prefs, the in-memory Room and the Koin graph for every leg, and
 * tearDown unwinds all of it - a class-level "start Koin once" guard
 * once left every leg after the first dereferencing a prefs slot its
 * own instance never assigned.
 *
 * Fixtures are dummy strings only ("xpub-a", "tx-a-1", and a fake
 * "xpub-not-a-key custom.onion" server sentence) - house test
 * convention; no real keys, addresses, txids, or onion hosts.
 * No payload or keychain machinery: on a fresh CollectionRepository
 * read() has not run, isPayloadUsable is false, and addNew()'s sync()
 * write is suppressed - the round reads the live in-memory list, which
 * is what production reads mid-process.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DojoErrorEnvelopeRoundTest {

    companion object {
        /** Arbitrary server text. It must not surface on the throwable. */
        private const val SERVER_TEXT = "xpub-not-a-key custom.onion"
        private const val AUTO_SYNC_RETRIES = 2
    }

    private val direct = Executor { it.run() }

    private lateinit var server: MockWebServer
    private lateinit var prefs: PrefsUtil
    private lateinit var db: SentinelRoomDb
    private lateinit var repo: TransactionsRepository
    private val seen = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        if (GlobalContext.getOrNull() != null) stopKoin()
        val app = ApplicationProvider.getApplicationContext<Application>()
        File(app.filesDir, "app_wallet/dojo.payload").delete()
        File(app.filesDir, "app_wallet/dojo.payload.bak").delete()
        prefs = PrefsUtil(app)
        prefs.enableTor = false
        prefs.testnet = false
        prefs.whirlpoolAutoLabels = false
        prefs.authorization = ""
        prefs.refreshToken = ""
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
        // SentinelState posts its keychain observer on first use.
        shadowOf(android.os.Looper.getMainLooper()).idle()
        prefs.apiEndPoint = server.url("/").toString().trimEnd('/')
        prefs.apiEndPointTor = null
        seen.clear()
    }

    /** Per-test unwind: the server, the Room instance, and Koin's graph. */
    @After
    fun tearDown() {
        server.shutdown()
        if (this::db.isInitialized) db.close()
        if (GlobalContext.getOrNull() != null) stopKoin()
    }

    /**
     * Leg 1 - the throw identity. The envelope arm must surface
     * ApiService.DojoApiError with the fixed local message. The
     * server's text is not the message and not toString. A regressed
     * fake CancellationException rethrows here and fails the test
     * with the CE itself.
     */
    @Test(timeout = 60_000)
    fun `the envelope throw is a DojoApiError with a fixed local message`() {
        val collection = seedCollection("direct", "xpub-a")
        server.dispatcher = dojoAnswering(faultPub = "xpub-a")

        val thrown: Exception? = runBlocking {
            try {
                repo.fetchFromServer(collection.id)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
        }

        assertTrue("fetchFromServer swallowed the envelope", thrown != null)
        assertTrue(
            "expected ApiService.DojoApiError, got ${thrown?.javaClass}",
            thrown is ApiService.DojoApiError,
        )
        assertTrue(thrown !is CancellationException)
        val error = thrown!!
        assertEquals(ApiService.DojoApiError.MESSAGE, error.message)
        assertEquals("Dojo returned an error", error.message)
        assertFalse(error.message!!.contains(SERVER_TEXT))
        assertFalse(error.message!!.contains("xpub"))
        assertFalse(error.message!!.contains("onion"))
        assertFalse(error.toString().contains(SERVER_TEXT))
        assertFalse(error.toString().contains("xpub-not-a-key"))
        assertFalse(error.toString().contains("custom.onion"))
        val seen = HashSet<Throwable>()
        fun walk(t: Throwable?) {
            if (t == null || !seen.add(t)) return
            val text = (t.message ?: "") + "\n" + t.toString()
            assertFalse(text.contains(SERVER_TEXT))
            assertFalse(text.contains("xpub-not-a-key"))
            assertFalse(text.contains("custom.onion"))
            if (t is ApiService.DojoApiError) {
                assertEquals(ApiService.DojoApiError.MESSAGE, t.message)
            }
            t.suppressed.forEach { walk(it) }
            walk(t.cause)
        }
        walk(error)
        assertEquals(1, walletHits("xpub-a"))
    }

    /**
     * Legs 2+3 - the round accounting. The faulted collection is FIRST
     * in round order here (the device leg's case 1).
     */
    @Test(timeout = 60_000)
    fun `faulted first - one of two collections fails and the verdict is partial`() {
        assertPartialRound(faultPub = "xpub-a", case = "first")
    }

    /** The failing collection is LAST in round order (case 2). */
    @Test(timeout = 60_000)
    fun `faulted last - the same partial verdict with the failure last`() {
        assertPartialRound(faultPub = "xpub-b", case = "last")
    }

    private fun assertPartialRound(faultPub: String, case: String) {
        val first = seedCollection("$case-a", "xpub-a")
        val second = seedCollection("$case-b", "xpub-b")
        val inRound = listOf(first, second)
        val faulted = if (faultPub == "xpub-a") first else second
        val healthy = if (faultPub == "xpub-a") second else first
        server.dispatcher = dojoAnswering(faultPub)

        val ledger = SyncRoundLedger()
        val thrownTypes = CopyOnWriteArrayList<String>()
        var failedCount = 0
        var healthyRows = 0
        var faultedRows = 0
        runBlocking {
            // Shape-verbatim replica of HomeViewModel.syncCollections'
            // per-collection loop (#522's own path), Timber lines dropped.
            inRound.forEach { collection ->
                try {
                    val result = repo.fetchFromServer(collection.id)
                    ledger.record(collection.id, result)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ledger.recordThrown(collection.id, e)
                    thrownTypes.add(e.javaClass.simpleName)
                }
            }
            // #475/#499 completion gate, same call shape as gateTxHistory.
            val counts = HashMap<String, Int>(inRound.size)
            inRound.forEach { c -> counts[c.id] = repo.countTxForCollection(c.id) }
            ledger.applyGate(inRound.map { it.id }, counts)
            failedCount = ledger.failed
            healthyRows = repo.countTxForCollection(healthy.id)
            faultedRows = repo.countTxForCollection(faulted.id)
        }

        // #287/#499/#522: the terminal decision, same arguments the
        // syncCollections call site passes.
        val decision = SyncRoundTerminal.decide(
            kind = SyncRoundTerminal.Kind.COLLECTIONS,
            failed = failedCount,
            total = inRound.size,
            endpointEnabled = prefs.isAPIEndpointEnabled(),
            userInitiated = false,
            autoRetryBudget = 0,
            autoRetryLimit = AUTO_SYNC_RETRIES,
            standingFailed = false,
            tally = ledger.tally(),
        )
        val posted = decision.toSyncState(System.currentTimeMillis())

        assertEquals(1, failedCount)
        assertEquals(listOf("DojoApiError"), thrownTypes.toList())
        // Both collections' pubs were actually requested - the round did
        // not abort at the first failure.
        assertEquals(1, walletHits("xpub-a"))
        assertEquals(1, walletHits("xpub-b"))
        // The healthy sibling landed its row; the faulted one wrote nothing.
        assertEquals(1, healthyRows)
        assertEquals(0, faultedRows)
        // The #506 partial banner, never "Sync interrupted."
        assertEquals(
            SyncState.Failed(SyncRoundTerminal.dojoErrorReason(1, 2), retryable = true),
            posted,
        )
        // A partial failure clears the external-arm signature, and the
        // collections round never arms the auto-retry.
        assertTrue(decision.clearLastRoundSignature)
        assertFalse(decision.armAutoRetry)
    }

    /**
     * Leg 4 - source gates (EmptyReconcileTest / TxHistoryPagingTest
     * style). The replica above cannot go red against the repository,
     * so the fixed shapes are pinned textually.
     */
    @Test
    fun `the envelope arm and the loop catch order are pinned at source`() {
        val repo = source(
            "app/src/main/java/com/samourai/sentinel/data/repository/TransactionsRepository.kt"
        )
        val start = repo.indexOf("suspend fun fetchFromServer(collectionId: String)")
        val end = repo.indexOf("private suspend fun saveTx")
        assertTrue("fetchFromServer not found", start >= 0)
        assertTrue("saveTx not found after fetchFromServer", end > start)
        // Strip // comments before the needles: #531's own explanatory
        // prose ("Both round loops rethrow CancellationException") is the
        // only CE text in this window - the first version of this gate
        // tripped on exactly that comment.
        val fnCode = repo.substring(start, end).lineSequence()
            .map { it.substringBefore("//") }
            .joinToString("\n")
        // #522: the envelope surfaces as a real Exception subclass.
        // The message is the fixed local constant, not the server text.
        assertTrue(fnCode.contains("throw ApiService.DojoApiError(ApiService.DojoApiError.MESSAGE)"))
        assertFalse(fnCode.contains("optString(\"message\")"))
        assertFalse(fnCode.contains("optJSONObject(\"error\")"))
        // The old bug laundered the envelope through a fake CE; nothing
        // in this function may throw CancellationException again.
        assertFalse(fnCode.contains("throw CancellationException"))

        val home = source(
            "app/src/main/java/com/samourai/sentinel/ui/home/HomeViewModel.kt"
        )
        val syncStart = home.indexOf("private suspend fun syncCollections(")
        val syncEnd = home.indexOf("private suspend fun gateTxHistory(")
        assertTrue("syncCollections not found", syncStart >= 0)
        assertTrue("gateTxHistory not found after syncCollections", syncEnd > syncStart)
        val loopCode = home.substring(syncStart, syncEnd).lineSequence()
            .map { it.substringBefore("//") }
            .joinToString("\n")
        // Real cancellation must stay ahead of the Exception wall: a fake
        // CE thrown by a fetch must reach recordThrown, never the rethrow
        // arm, or one failure aborts the round again.
        val ceArm = loopCode.indexOf("catch (e: CancellationException)")
        val exArm = loopCode.indexOf("catch (e: Exception)")
        assertTrue("syncCollections CE arm missing", ceArm >= 0)
        assertTrue("syncCollections Exception arm missing", exArm >= 0)
        assertTrue("CE arm must precede the Exception arm", ceArm < exArm)
        assertTrue(loopCode.contains("ledger.recordThrown(collection.id, e)"))
    }

    private fun walletHits(pub: String): Int =
        seen.count { it.startsWith("/wallet") && it.contains("active=$pub") }

    private fun seedCollection(label: String, pubKey: String): PubKeyCollection {
        val collection = PubKeyCollection(
            collectionLabel = label,
            pubs = arrayListOf(
                PubKeyModel(pubKey = pubKey, label = "$label key", type = AddressTypes.BIP84),
            ),
        )
        org.koin.java.KoinJavaComponent.get(CollectionRepository::class.java).addNew(collection)
        return collection
    }

    private fun dojoAnswering(faultPub: String): Dispatcher =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                seen.add(path)
                return when {
                    path.startsWith("/wallet") && path.contains("active=$faultPub") ->
                        MockResponse()
                            .setResponseCode(500)
                            .setBody(
                                """{"status":"error","error":{"message":"$SERVER_TEXT"}}"""
                            )
                    path.startsWith("/wallet") ->
                        MockResponse().setResponseCode(200).setBody(healthyWalletBody())
                    else ->
                        // Auxiliary calls (fees, entropy /tx fetches) stay
                        // benign so contained side-arms never fail the round.
                        MockResponse().setResponseCode(200).setBody("""{"status":"ok"}""")
                }
            }
        }

    private fun healthyWalletBody(): String = """
        {"info":{"latest_block":{"hash":"tip","height":100,"time":1700000000},
        "fees":null,"addresses":null},
        "txs":[{"hash":"tx-a-1","time":1700000000,"version":1,"locktime":0,
        "result":100,"inputs":[],"out":[],"block_height":90,"confirmations":11}],
        "wallet":{"final_balance":123},"unspent_outputs":null}
    """.trimIndent()

    private fun source(relative: String): String {
        val file = sequenceOf(File(relative), File(relative.removePrefix("app/")))
            .firstOrNull { it.isFile }
            ?: error("missing $relative from ${File(".").absolutePath}")
        return file.readText()
    }
}
