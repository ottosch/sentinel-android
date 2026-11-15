package com.samourai.sentinel.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.core.SyncState
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.Tx
import com.samourai.sentinel.data.Utxo
import com.samourai.sentinel.data.db.SentinelCollectionStore
import com.samourai.sentinel.data.db.SentinelRoomDb
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.home.SyncRoundLedger
import com.samourai.sentinel.ui.home.SyncRoundTerminal
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.util.UtxoMetaUtil
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

/**
 * #530: one failing pub must not throw away the rest of the collection.
 *
 * The reconcile deletes every tx and UTXO of the collection that is
 * absent from this round's lists, and the do-not-spend cleanup walks
 * every pub. A pub that threw has to contribute its existing rows as
 * a keep-set, or the round deletes them and clears the mark. Fixtures
 * are dummy strings only (xpub-a, old-b). No real keys or onion hosts.
 *
 * The Dojo status=error throw itself is pinned by
 * DojoErrorEnvelopeRoundTest (#534). This class does not repeat it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PubSyncIsolationTest {

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
        File(app.filesDir, "app_wallet/utxo.meta.payload").delete()
        File(app.filesDir, "app_wallet/utxo.meta.payload.bak").delete()
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
        shadowOf(android.os.Looper.getMainLooper()).idle()
        prefs.apiEndPoint = server.url("/").toString().trimEnd('/')
        prefs.apiEndPointTor = null
        seen.clear()
        settleDoNotSpendRead()
        UtxoMetaUtil.clearAll()
    }

    @After
    fun tearDown() {
        server.shutdown()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        if (this::db.isInitialized) db.close()
        if (GlobalContext.getOrNull() != null) stopKoin()
    }

    @Test(timeout = 60_000)
    fun `middle pub failure keeps its rows and is reported partial`() {
        val collection = seedCollection()
        seedRows(collection.id)
        markDoNotSpend(collection.id, "old-a")
        markDoNotSpend(collection.id, "old-b")
        server.dispatcher = answering(faultPubs = setOf("xpub-b"))

        val result = runBlocking { repo.fetchFromServer(collection.id) }

        assertTrue(result is CollectionTxSyncResult.Indeterminate)
        val stored = collections().findById(collection.id)!!
        assertEquals(111L, balance(stored, "xpub-a"))
        assertEquals(2222L, balance(stored, "xpub-b"))
        assertEquals(333L, balance(stored, "xpub-c"))
        assertEquals(listOf("new-a-${collection.id}"), hashes(collection.id, "xpub-a"))
        assertEquals(listOf("old-b"), hashes(collection.id, "xpub-b"))
        assertEquals(listOf("new-c-${collection.id}"), hashes(collection.id, "xpub-c"))
        val utxos = db.utxoDao().getUTXObyCollectionAsList(collection.id)
        assertTrue(utxos.any { it.idx == "new-a:0" && it.pubKey == "xpub-a" })
        assertTrue(utxos.any { it.idx == "old-b:0" && it.pubKey == "xpub-b" })
        assertTrue(utxos.any { it.idx == "new-c:0" && it.pubKey == "xpub-c" })
        assertFalse(utxos.any { it.idx == "old-a:0" })
        assertFalse(utxos.any { it.idx == "old-c:0" })
        // The failed pub's mark is untouched. The healthy pub's mark
        // referred to a UTXO the new list does not contain, so it goes.
        assertTrue(UtxoMetaUtil.has("old-b", 0))
        assertFalse(UtxoMetaUtil.has("old-a", 0))
        assertEquals(1, walletHits("xpub-a"))
        assertEquals(1, walletHits("xpub-b"))
        assertEquals(1, walletHits("xpub-c"))

        val ledger = SyncRoundLedger()
        ledger.record(collection.id, result)
        ledger.record("other", CollectionTxSyncResult.Fetched(1))
        assertEquals(1, ledger.failed)
        val decision = SyncRoundTerminal.decide(
            kind = SyncRoundTerminal.Kind.COLLECTIONS,
            failed = ledger.failed,
            total = 2,
            endpointEnabled = true,
            userInitiated = false,
            autoRetryBudget = 0,
            autoRetryLimit = 2,
            standingFailed = false,
            tally = ledger.tally(),
        )
        assertEquals(
            SyncState.Failed(SyncRoundTerminal.dojoErrorReason(1, 2), retryable = true),
            decision.toSyncState(1L),
        )
    }

    @Test(timeout = 60_000)
    fun `every pub failing throws and leaves stored rows in place`() {
        val collection = seedCollection()
        seedRows(collection.id)
        markDoNotSpend(collection.id, "old-b")
        server.dispatcher = answering(faultPubs = setOf("xpub-a", "xpub-b", "xpub-c"))

        try {
            runBlocking { repo.fetchFromServer(collection.id) }
            fail("expected every pub failing to throw")
        } catch (e: ApiService.DojoApiError) {
            // #539: the throwable keeps a fixed local string. The
            // server's "rejected" text is not the message.
            assertEquals(ApiService.DojoApiError.MESSAGE, e.message)
            assertFalse(e.message!!.contains("rejected"))
        }

        val stored = collections().findById(collection.id)!!
        assertEquals(11L, balance(stored, "xpub-a"))
        assertEquals(2222L, balance(stored, "xpub-b"))
        assertEquals(33L, balance(stored, "xpub-c"))
        assertEquals(listOf("old-a"), hashes(collection.id, "xpub-a"))
        assertEquals(listOf("old-b"), hashes(collection.id, "xpub-b"))
        assertEquals(listOf("old-c"), hashes(collection.id, "xpub-c"))
        val utxos = db.utxoDao().getUTXObyCollectionAsList(collection.id)
        assertEquals(3, utxos.size)
        assertTrue(UtxoMetaUtil.has("old-b", 0))
    }

    @Test(timeout = 60_000)
    fun `a non-2xx wallet body keeps that pub's rows`() {
        val collection = seedCollection()
        seedRows(collection.id)
        markDoNotSpend(collection.id, "old-a")
        markDoNotSpend(collection.id, "old-b")
        // A 500 whose body is a normal wallet. Applying it would
        // replace old-b. It must be kept instead.
        server.dispatcher = answering(faultPubs = setOf("xpub-b")) { pub ->
            MockResponse().setResponseCode(500).setBody(healthy(pub))
        }

        val result = runBlocking { repo.fetchFromServer(collection.id) }

        assertTrue(result is CollectionTxSyncResult.Indeterminate)
        assertKeptMiddle(collection)
        assertTrue(UtxoMetaUtil.has("old-b", 0))
        assertFalse(UtxoMetaUtil.has("old-a", 0))
        assertEquals(1, walletHits("xpub-a"))
        assertEquals(1, walletHits("xpub-b"))
        assertEquals(1, walletHits("xpub-c"))
    }

    @Test(timeout = 60_000)
    fun `a null body keeps that pub's rows`() {
        val collection = seedCollection()
        seedRows(collection.id)
        markDoNotSpend(collection.id, "old-b")
        repo.walletFetchOverride = { pub ->
            if (pub == "xpub-b") bareResponse(200, null) else bareResponse(200, healthy(pub))
        }
        try {
            val result = runBlocking { repo.fetchFromServer(collection.id) }

            assertTrue(result is CollectionTxSyncResult.Indeterminate)
            assertKeptMiddle(collection)
            assertTrue(UtxoMetaUtil.has("old-b", 0))
        } finally {
            repo.walletFetchOverride = null
        }
    }

    @Test(timeout = 60_000)
    fun `an unparseable body keeps that pub's rows`() {
        val collection = seedCollection()
        seedRows(collection.id)
        markDoNotSpend(collection.id, "old-b")
        server.dispatcher = answering(faultPubs = setOf("xpub-b")) { _ ->
            MockResponse().setResponseCode(200).setBody("not-json")
        }

        val result = runBlocking { repo.fetchFromServer(collection.id) }

        assertTrue(result is CollectionTxSyncResult.Indeterminate)
        assertKeptMiddle(collection)
        assertTrue(UtxoMetaUtil.has("old-b", 0))
        assertEquals(1, walletHits("xpub-b"))
    }

    @Test(timeout = 60_000)
    fun `ApiNotConfigured aborts the collection without a wipe`() {
        val collection = seedCollection()
        seedRows(collection.id)
        markDoNotSpend(collection.id, "old-b")
        prefs.apiEndPoint = null

        val result = runBlocking { repo.fetchFromServer(collection.id) }

        assertTrue(result is CollectionTxSyncResult.Indeterminate)
        assertEquals(listOf("old-a"), hashes(collection.id, "xpub-a"))
        assertEquals(listOf("old-b"), hashes(collection.id, "xpub-b"))
        assertEquals(listOf("old-c"), hashes(collection.id, "xpub-c"))
        assertEquals(3, db.utxoDao().getUTXObyCollectionAsList(collection.id).size)
        assertTrue(UtxoMetaUtil.has("old-b", 0))
        val stored = collections().findById(collection.id)!!
        assertEquals(2222L, balance(stored, "xpub-b"))
        assertEquals(0, walletHits("xpub-a"))
    }

    @Test(timeout = 60_000)
    fun `cancellation propagates out of a pub fetch`() {
        val collection = seedCollection()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen.add(request.path.orEmpty())
                started.countDown()
                release.await(20, TimeUnit.SECONDS)
                return MockResponse().setResponseCode(500)
                    .setBody("""{"status":"error","error":{"message":"late"}}""")
            }
        }
        val returned = AtomicBoolean(false)
        val parent = SupervisorJob()
        val job = CoroutineScope(parent + Dispatchers.IO).launch {
            repo.fetchFromServer(collection.id)
            returned.set(true)
        }
        try {
            assertTrue("wallet call did not start", started.await(15, TimeUnit.SECONDS))
            job.cancel(CancellationException("stop"))
            release.countDown()
            runBlocking { job.join() }
        } finally {
            release.countDown()
            parent.cancel()
        }
        assertFalse("cancellation returned a result", returned.get())
        assertTrue(job.isCancelled)
    }

    @Test(timeout = 60_000)
    fun `a clean three-pub round still replaces rows`() {
        val collection = seedCollection()
        seedRows(collection.id)
        markDoNotSpend(collection.id, "old-a")
        server.dispatcher = answering(faultPubs = emptySet())

        val result = runBlocking { repo.fetchFromServer(collection.id) }

        assertEquals(CollectionTxSyncResult.Fetched(3), result)
        val stored = collections().findById(collection.id)!!
        assertEquals(111L, balance(stored, "xpub-a"))
        assertEquals(222L, balance(stored, "xpub-b"))
        assertEquals(333L, balance(stored, "xpub-c"))
        assertEquals(listOf("new-a-${collection.id}"), hashes(collection.id, "xpub-a"))
        assertEquals(listOf("new-b-${collection.id}"), hashes(collection.id, "xpub-b"))
        assertEquals(listOf("new-c-${collection.id}"), hashes(collection.id, "xpub-c"))
        assertFalse(UtxoMetaUtil.has("old-a", 0))
    }

    @Test
    fun `a pub failure rethrows cancellation before it is counted`() {
        val src = source(
            "app/src/main/java/com/samourai/sentinel/data/repository/TransactionsRepository.kt",
        )
        val start = src.indexOf("suspend fun fetchFromServer(collectionId: String)")
        val end = src.indexOf("private suspend fun saveTx")
        assertTrue(start >= 0 && end > start)
        val fn = src.substring(start, end).lineSequence()
            .map { it.substringBefore("//") }
            .joinToString("\n")
        val loop = fn.substring(fn.indexOf("val pubFailures"), fn.indexOf("val partialPubs"))
        val ce = loop.indexOf("catch (e: CancellationException)")
        val ex = loop.indexOf("catch (e: Exception)")
        assertTrue(ce >= 0 && ex > ce)
        assertTrue(loop.contains("throw e"))
        assertTrue(fn.contains("keepTxHashes"))
        assertTrue(fn.contains("txDao.getHashesForPub"))
        assertFalse(loop.contains("throw CancellationException"))
    }

    private fun walletHits(pub: String): Int =
        seen.count { it.startsWith("/wallet") && it.contains("active=$pub") }

    private fun collections(): CollectionRepository =
        org.koin.java.KoinJavaComponent.get(CollectionRepository::class.java)

    private fun balance(collection: PubKeyCollection, pub: String): Long =
        collection.pubs.first { it.pubKey == pub }.balance

    private fun hashes(collectionId: String, pub: String): List<String> = runBlocking {
        db.txDao().getHashesForPub(collectionId, pub).sorted()
    }

    private fun seedCollection(): PubKeyCollection {
        val collection = PubKeyCollection(
            collectionLabel = "three",
            pubs = arrayListOf(
                pub("xpub-a", 11L),
                pub("xpub-b", 2222L),
                pub("xpub-c", 33L),
            ),
        )
        collections().addNew(collection)
        return collection
    }

    private fun pub(key: String, balance: Long) = PubKeyModel(
        pubKey = key,
        label = key,
        type = AddressTypes.BIP84,
        balance = balance,
    )

    private fun seedRows(collectionId: String) = runBlocking {
        for (key in listOf("xpub-a", "xpub-b", "xpub-c")) {
            val slug = key.takeLast(1)
            db.txDao().insertAll(listOf(tx("old-$slug", collectionId, key)))
            db.utxoDao().insert(utxo("old-$slug", collectionId, key))
        }
    }

    private fun markDoNotSpend(collectionId: String, hash: String) {
        val pub = "xpub-${hash.takeLast(1)}"
        UtxoMetaUtil.put(utxo(hash, collectionId, pub))
    }

    private fun tx(hash: String, collectionId: String, pub: String) = Tx(
        hash = hash,
        time = 1_700_000_000L,
        version = 1,
        locktime = 0,
        result = 100L,
        inputs = emptyList(),
        out = emptyList(),
        block_height = 90L,
        confirmations = 6,
        associatedPubKey = pub,
        collectionId = collectionId,
    )

    private fun utxo(hash: String, collectionId: String, pub: String) = Utxo(
        idx = "$hash:0",
        txHash = hash,
        txOutputN = 0,
        value = 50L,
        confirmations = 1,
        pubKey = pub,
        collectionId = collectionId,
    )

    private fun assertKeptMiddle(collection: PubKeyCollection) {
        val stored = collections().findById(collection.id)!!
        assertEquals(111L, balance(stored, "xpub-a"))
        assertEquals(2222L, balance(stored, "xpub-b"))
        assertEquals(333L, balance(stored, "xpub-c"))
        assertEquals(listOf("new-a-${collection.id}"), hashes(collection.id, "xpub-a"))
        assertEquals(listOf("old-b"), hashes(collection.id, "xpub-b"))
        assertEquals(listOf("new-c-${collection.id}"), hashes(collection.id, "xpub-c"))
        val utxos = db.utxoDao().getUTXObyCollectionAsList(collection.id)
        assertTrue(utxos.any { it.idx == "new-a:0" && it.pubKey == "xpub-a" })
        assertTrue(utxos.any { it.idx == "old-b:0" && it.pubKey == "xpub-b" })
        assertTrue(utxos.any { it.idx == "new-c:0" && it.pubKey == "xpub-c" })
        assertFalse(utxos.any { it.idx == "old-a:0" })
        assertFalse(utxos.any { it.idx == "old-c:0" })
    }

    private fun bareResponse(code: Int, body: String?): Response {
        val builder = Response.Builder()
            .request(Request.Builder().url("http://127.0.0.1/wallet").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("ok")
        if (body != null) {
            builder.body(body.toResponseBody("application/json".toMediaType()))
        }
        return builder.build()
    }

    private fun answering(
        faultPubs: Set<String>,
        fault: (String) -> MockResponse = { _ ->
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"status":"error","error":{"message":"rejected"}}""")
        },
    ): Dispatcher =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                seen.add(path)
                if (!path.startsWith("/wallet")) {
                    return MockResponse().setResponseCode(200).setBody("""{"status":"ok"}""")
                }
                val pub = listOf("xpub-a", "xpub-b", "xpub-c").firstOrNull {
                    path.contains("active=$it")
                } ?: return MockResponse().setResponseCode(404)
                if (pub in faultPubs) return fault(pub)
                return MockResponse().setResponseCode(200).setBody(healthy(pub))
            }
        }

    private fun healthy(pub: String): String {
        val slug = pub.takeLast(1)
        val balance = when (slug) {
            "a" -> 111
            "b" -> 222
            "c" -> 333
            else -> 1
        }
        return """
            {"info":{"latest_block":{"hash":"tip","height":100,"time":1700000000},"fees":null},
            "addresses":null,
            "txs":[{"hash":"new-$slug","time":1700000000,"version":1,"locktime":0,
            "result":100,"inputs":[],"out":[],"block_height":90}],
            "wallet":{"final_balance":$balance},
            "unspent_outputs":[{"tx_hash":"new-$slug","tx_output_n":0,"value":$balance}]}
        """.trimIndent()
    }

    private fun source(relative: String): String {
        val file = sequenceOf(File(relative), File(relative.removePrefix("app/")))
            .firstOrNull { it.isFile }
            ?: error("missing $relative from ${File(".").absolutePath}")
        return file.readText()
    }

    companion object {
        private var doNotSpendReadSettled = false

        /**
         * UtxoMetaUtil.read() replaces in-memory marks when it finds a
         * payload. Let that first read finish against a missing file
         * before any test writes a mark.
         */
        private fun settleDoNotSpendRead() {
            if (doNotSpendReadSettled) return
            UtxoMetaUtil.has("none", 0)
            runBlocking { delay(400) }
            doNotSpendReadSettled = true
        }
    }
}
