package com.samourai.sentinel.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.db.SentinelCollectionStore
import com.samourai.sentinel.data.db.SentinelRoomDb
import com.samourai.sentinel.data.db.dao.TxDao
import com.samourai.sentinel.data.db.dao.UtxoDao
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.util.ExportImportUtil
import com.samourai.sentinel.util.dataBaseScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.java.KoinJavaComponent.inject
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * Regression suite for the import persistence race: a queued write-side
 * worker used to carry a list snapshot captured at CALL time, so a
 * worker dispatched late could overwrite the complete file with an
 * outdated copy (visible as a lost collection after restart).
 *
 * The contract pinned here: every worker persists the list as of its
 * WRITE. The discriminating event is an unqueued live mutation made
 * while the workers are held in the queue - a call-time snapshot misses
 * it, a write-time snapshot includes it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CollectionsRestoreAfterImportTest {

    private lateinit var db: SentinelRoomDb
    private var startedKoin = false

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SentinelRoomDb::class.java
        ).allowMainThreadQueries().build()
        if (GlobalContext.getOrNull() == null) {
            startKoin {
                androidContext(ApplicationProvider.getApplicationContext())
                modules(
                    module {
                        single { PrefsUtil(ApplicationProvider.getApplicationContext()) }
                        single { SentinelCollectionStore() }
                        single<TxDao> { db.txDao() }
                        single<UtxoDao> { db.utxoDao() }
                        single { CollectionRepository() }
                    }
                )
            }
            startedKoin = true
        }
    }

    @After
    fun tearDown() {
        db.close()
        if (startedKoin) {
            stopKoin()
            startedKoin = false
        }
    }

    private fun collection(label: String): PubKeyCollection = PubKeyCollection(
        collectionLabel = label,
        pubs = arrayListOf(
            PubKeyModel(
                pubKey = "xpub-$label",
                label = "$label key",
                type = AddressTypes.BIP84
            )
        )
    )

    private fun payloadFile() =
        (inject(SentinelCollectionStore::class.java).value as SentinelCollectionStore)
            .getCollectionStore().file

    /**
     * The user scenario: import a two-collection backup with the
     * "clear existing" tick, then restart. A fresh repository read must
     * restore both collections.
     */
    @Test
    fun twoCollectionImportWithClearPersistsBothForRestart() = runBlocking {
        ExportImportUtil().startImportCollections(
            arrayListOf(collection("A"), collection("B")),
            replace = true
        )

        val file = payloadFile()
        withTimeout(10_000) {
            while (decode(file.readText()).second != 2) {
                delay(50)
            }
        }

        // A restart: a brand-new repository instance reading the same
        // file, like HomeViewModel.load() does on relaunch.
        val fresh = CollectionRepository()
        fresh.read()
        val labels = fresh.collectionsSnapshot().map { it.collectionLabel }.sorted()
        assertEquals(listOf("A", "B"), labels)
    }

    /**
     * The regression leg. Writers are held in the queue by saturation;
     * an unqueued mutation lands on the live list while they wait; the
     * released writer must persist the list as of its WRITE (mutation
     * included), not as of its CALL (mutation missed).
     */
    @Test
    fun queuedWriterPersistsTruthAsOfItsWriteNotItsCall() = runBlocking {
        val repository = inject(CollectionRepository::class.java).value
        val file = payloadFile()

        // Hold every dataBaseScope worker on a latch, so the writers
        // queued below cannot dispatch yet.
        val canaries = max(64, Runtime.getRuntime().availableProcessors())
        val gate = CountDownLatch(1)
        val running = AtomicInteger(0)
        repeat(canaries) {
            dataBaseScope.launch(Dispatchers.IO) {
                running.incrementAndGet()
                gate.await()
                running.decrementAndGet()
            }
        }
        // A canary that RUNS despite the hold means saturation failed -
        // skip rather than fake a verdict.
        val probeRan = AtomicInteger(0)
        dataBaseScope.launch(Dispatchers.IO) { probeRan.incrementAndGet() }
        delay(300)
        assumeTrue("could not saturate dataBaseScope; strict leg skipped", probeRan.get() == 0)

        repository.reset()
        repository.addNew(collection("A"))
        repository.addNew(collection("B"))

        // The discriminating event: mutate the live list without
        // queueing a writer (findById returns the live object), so only
        // a write-time snapshot can see it.
        val liveBId = repository.collectionsSnapshot().first { it.collectionLabel == "B" }.id
        val liveB = repository.findById(liveBId)!!
        liveB.pubs.add(PubKeyModel(pubKey = "xpub-B2", label = "B key 2", type = AddressTypes.BIP84))

        // Free one worker and let it run the queued writers. The last
        // writer's snapshot decides the file.
        gate.countDown()
        withTimeout(10_000) {
            while (running.get() >= canaries) delay(25)
        }
        delay(250)

        val (collections, pubs) = decode(file.readText())
        assertEquals("collection count", 2, collections)
        // A + B with the live mutation included = 3 keys.
        assertEquals(3, pubs)

        // Drain everything, then a fresh read (the restart) on the file.
        repeat(canaries) { gate.countDown() }
        withTimeout(10_000) {
            while (running.get() > 0) delay(25)
        }
        val fresh = CollectionRepository()
        fresh.read()
        val restored = fresh.collectionsSnapshot()
        assertEquals(2, restored.size)
        val bPubs = restored.single { it.collectionLabel == "B" }.pubs
        assertEquals(2, bPubs.size)
        assertTrue(bPubs.any { it.pubKey == "xpub-B2" })
    }

    /** (collection count, total pub count) decoded straight from the file. */
    private fun decode(payload: String): Pair<Int, Int> {
        val parsed = com.samourai.sentinel.helpers.fromJSON<ArrayList<PubKeyCollection>>(
            payload,
            object : com.google.gson.reflect.TypeToken<ArrayList<PubKeyCollection?>>() {}.type
        ) ?: return -1 to -1
        return parsed.size to parsed.sumOf { it.pubs.size }
    }
}
