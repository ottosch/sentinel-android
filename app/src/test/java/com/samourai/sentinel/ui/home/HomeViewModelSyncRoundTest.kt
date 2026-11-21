package com.samourai.sentinel.ui.home

import android.app.Application
import android.os.Looper
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.core.SyncState
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.db.SentinelCollectionStore
import com.samourai.sentinel.data.db.SentinelRoomDb
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.data.repository.ExchangeRateRepository
import com.samourai.sentinel.data.repository.FeeRepository
import com.samourai.sentinel.data.repository.LabelRepository
import com.samourai.sentinel.data.repository.TransactionsRepository
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.utils.PrefsUtil
import java.io.File
import java.net.InetAddress
import java.util.concurrent.Executor
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
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
 * The real [HomeViewModel.launchSyncRound] against a loopback Dojo.
 * Tor-not-ready must post WaitingForTor and make no HTTP call, for a
 * full round and for a delta round. A clean Success plus one added
 * collection fetches only that collection. A removal-only change
 * stamps both signatures and does not fetch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeViewModelSyncRoundTest {

    private val direct = Executor { it.run() }
    private lateinit var server: MockWebServer
    private lateinit var prefs: PrefsUtil
    private lateinit var db: SentinelRoomDb
    private lateinit var store: ViewModelStore
    private val seen = ArrayList<String>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen.add(request.path.orEmpty())
                return MockResponse().setResponseCode(200).setBody(WALLET)
            }
        }
        if (GlobalContext.getOrNull() != null) stopKoin()
        val app = ApplicationProvider.getApplicationContext<Application>()
        File(app.filesDir, "app_wallet/dojo.payload").delete()
        File(app.filesDir, "app_wallet/dojo.payload.bak").delete()
        prefs = PrefsUtil(app)
        prefs.clearAll()
        prefs.enableTor = false
        prefs.testnet = false
        prefs.whirlpoolAutoLabels = false
        prefs.authorization = ""
        prefs.refreshToken = ""
        val base = server.url("/").toString().trimEnd('/')
        prefs.apiEndPoint = base
        prefs.apiEndPointTor = base
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
                    single { ExchangeRateRepository() }
                },
            )
        }
        shadowOf(Looper.getMainLooper()).idle()
        seen.clear()
    }

    @After
    fun tearDown() {
        if (this::store.isInitialized) store.clear()
        server.shutdown()
        if (this::db.isInitialized) db.close()
        if (GlobalContext.getOrNull() != null) stopKoin()
    }

    @Test(timeout = 20_000)
    fun torNotReadyPostsWaitingForTorAndMakesNoRequestForFullAndDelta() {
        // SentinelState caches the PrefsUtil it first injected. A previous
        // test's instance is not this setUp's prefs, so the flag is set on
        // every instance the process still holds.
        everyPrefs().forEach { it.enableTor = true }
        resetTor(EnumTorState.OFF, progress = 0, clearProxy = true)
        val vm = newModel()
        val a = collection("A", "xpub-a")
        val b = collection("B", "xpub-b")
        val c = collection("C", "xpub-c")
        // In the repository, so a gate that is forced off would actually
        // reach the loopback server. The gate must stop that.
        seed(a, b, c)

        launch(vm, arrayListOf(a, b))
        awaitSync(vm) { it is SyncState.WaitingForTor }
        awaitRoundFinished(vm)
        assertTrue(
            "full state=${vm.syncState().value}",
            vm.syncState().value is SyncState.WaitingForTor,
        )
        assertEquals(0, server.requestCount)

        setState(vm, SyncState.Success(1L))
        setField(vm, "lastRoundSignature", SyncRerunPolicy.signatureOf(listOf(a, b)))
        seen.clear()
        launch(vm, arrayListOf(a, b, c))
        awaitSync(vm) { it is SyncState.WaitingForTor }
        assertTrue(
            "delta state=${vm.syncState().value} last=${field(vm, "lastRoundSignature")} round=${field(vm, "syncRound")}",
            vm.syncState().value is SyncState.WaitingForTor,
        )
        assertEquals(0, server.requestCount)
        assertEquals(0, seen.count { it.contains("xpub-") })
    }

    @Test(timeout = 20_000)
    fun deltaOverACleanSuccessFetchesOnlyTheAddedXpub() {
        everyPrefs().forEach { it.enableTor = false }
        resetTor(EnumTorState.OFF, progress = 0, clearProxy = true)
        val vm = newModel()
        val a = collection("A", "xpub-a")
        val b = collection("B", "xpub-b")
        val c = collection("C", "xpub-c")
        // fetchFromServer looks the id up. addNew rewrites the id, so the
        // standing Success signature is taken after that rewrite.
        seed(a, b, c)
        setState(vm, SyncState.Success(1L))
        setField(vm, "lastRoundSignature", SyncRerunPolicy.signatureOf(listOf(a, b)))
        seen.clear()
        launch(vm, arrayListOf(a, b, c))
        awaitRoundFinished(vm)
        val paths = seen.toList()
        assertTrue(
            "state=${vm.syncState().value} requests=$paths last=${field(vm, "lastRoundSignature")}",
            paths.any { it.contains("xpub-c") } &&
                paths.none { it.contains("xpub-a") } &&
                paths.none { it.contains("xpub-b") },
        )
    }

    @Test(timeout = 20_000)
    fun skipStampsBothSignaturesAndDoesNotFetch() {
        everyPrefs().forEach { it.enableTor = false }
        resetTor(EnumTorState.OFF, progress = 0, clearProxy = true)
        val vm = newModel()
        val a = collection("A", "xpub-a")
        val b = collection("B", "xpub-b")
        val before = SyncRerunPolicy.signatureOf(listOf(a, b))
        val after = SyncRerunPolicy.signatureOf(listOf(a))
        setState(vm, SyncState.Failed("Could not reach Dojo.", retryable = true))
        setField(vm, "lastRoundSignature", before)
        setField(vm, "roundStartSignature", before)
        launch(vm, arrayListOf(a))
        assertEquals(after, field(vm, "lastRoundSignature"))
        assertEquals(after, field(vm, "roundStartSignature"))
        assertTrue(vm.syncState().value is SyncState.Failed)
        assertEquals(0, server.requestCount)
    }

    private fun seed(vararg collections: PubKeyCollection) {
        val repo = org.koin.java.KoinJavaComponent.get(CollectionRepository::class.java)
        collections.forEach { repo.addNew(it) }
    }

    private fun everyPrefs(): List<PrefsUtil> {
        val found = LinkedHashSet<PrefsUtil>()
        found.add(prefs)
        val state = com.samourai.sentinel.core.SentinelState::class.java
        state.declaredFields.forEach { field ->
            if (!field.name.contains("prefsUtil")) return@forEach
            field.isAccessible = true
            val value = runCatching { field.get(null) }.getOrNull()
            when (value) {
                is PrefsUtil -> found.add(value)
                is Lazy<*> -> (value.value as? PrefsUtil)?.let { found.add(it) }
            }
        }
        return found.toList()
    }

    private fun resetTor(state: com.samourai.sentinel.tor.EnumTorState, progress: Int, clearProxy: Boolean) {
        val manager = com.samourai.sentinel.tor.SentinelTorManager
        val cls = manager.javaClass
        val live = cls.getDeclaredField("torStateLiveDataPrivate").apply { isAccessible = true }
            .get(manager) as MutableLiveData<*>
        val tor = com.samourai.sentinel.tor.TorState().apply {
            this.state = state
            this.progressIndicator = progress
        }
        @Suppress("UNCHECKED_CAST")
        (live as MutableLiveData<Any>).value = tor
        if (clearProxy) {
            cls.getDeclaredField("proxy").apply { isAccessible = true }.set(manager, null)
        }
    }

    private fun newModel(): HomeViewModel {
        store = ViewModelStore()
        val vm = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return HomeViewModel() as T
                }
            },
        )[HomeViewModel::class.java]
        val deadline = System.currentTimeMillis() + 5_000
        val repo = org.koin.java.KoinJavaComponent.get(CollectionRepository::class.java)
        while (!repo.isPayloadLoaded() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        return vm
    }

    private fun collection(label: String, pub: String) = PubKeyCollection(
        collectionLabel = label,
        pubs = arrayListOf(PubKeyModel(pubKey = pub, label = label, type = AddressTypes.BIP84)),
    ).also { it.id = label }

    private fun launch(vm: HomeViewModel, collections: ArrayList<PubKeyCollection>) {
        val method = HomeViewModel::class.java.getDeclaredMethod(
            "launchSyncRound",
            ArrayList::class.java,
        )
        method.isAccessible = true
        method.invoke(vm, collections)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun setState(vm: HomeViewModel, state: SyncState) {
        @Suppress("UNCHECKED_CAST")
        val live = HomeViewModel::class.java.getDeclaredField("syncState")
            .apply { isAccessible = true }
            .get(vm) as MutableLiveData<SyncState>
        live.value = state
    }

    private fun setField(vm: HomeViewModel, name: String, value: Any?) {
        HomeViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)
    }

    private fun field(vm: HomeViewModel, name: String): Any? =
        HomeViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm)

    private fun awaitRoundFinished(vm: HomeViewModel) {
        val deadline = System.currentTimeMillis() + 5_000
        while (field(vm, "syncRound") != null && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    private fun awaitSync(vm: HomeViewModel, ready: (SyncState?) -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!ready(vm.syncState().value) && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    private fun awaitRequests(atLeast: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (server.requestCount < atLeast && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    private companion object {
        val WALLET = """
            {"info":{"latest_block":{"hash":"tip","height":100,"time":1700000000},
            "fees":null,"addresses":null},
            "txs":[{"hash":"tx-a-1","time":1700000000,"version":1,"locktime":0,
            "result":100,"inputs":[],"out":[],"block_height":90,"confirmations":11}],
            "wallet":{"final_balance":123},"unspent_outputs":null}
        """.trimIndent()
    }
}
