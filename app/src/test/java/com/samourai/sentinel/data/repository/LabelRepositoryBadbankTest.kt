package com.samourai.sentinel.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.db.SentinelRoomDb
import com.samourai.sentinel.data.whirlpool.LabelBadbankAccountSink
import com.samourai.sentinel.data.whirlpool.WhirlpoolConfig
import com.samourai.sentinel.data.whirlpool.WhirlpoolLabelSink
import com.samourai.sentinel.ui.utils.PrefsUtil
import kotlinx.coroutines.runBlocking
import org.bitcoinj.params.MainNetParams
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The sticky flag on the repository write paths. A Bad Bank coin
 * stores the user part. A deposit coin stores the typed text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LabelRepositoryBadbankTest {

    private val tx = "ab".repeat(32)
    private val origin = WhirlpoolConfig.DEFAULT.origin
    private lateinit var db: SentinelRoomDb
    private lateinit var labels: LabelRepository

    @Before
    fun setUp() {
        if (GlobalContext.getOrNull() != null) stopKoin()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = PrefsUtil(app)
        prefs.clearAll()
        prefs.testnet = false
        prefs.autoOriginHandEditsMigrated = true
        db = Room.inMemoryDatabaseBuilder(app, SentinelRoomDb::class.java)
            .allowMainThreadQueries()
            .build()
        startKoin {
            androidContext(app)
            modules(module {
                single { prefs }
                single { db }
                single { db.utxoDao() }
                single { db.utxoLabelDao() }
                single { db.labelEntryDao() }
                single { db.txDao() }
                single { db.txEntropyDao() }
            })
        }
        val field = SentinelState::class.java.getDeclaredField("networkParams")
        field.isAccessible = true
        field.set(null, MainNetParams.get())
        labels = LabelRepository()
    }

    @After
    fun tearDown() {
        if (this::db.isInitialized) db.close()
        if (GlobalContext.getOrNull() != null) stopKoin()
    }

    @Test
    fun setLabelStripsOnlyABadBankCoinAndKeepsTheFlag() = runBlocking {
        labels.setLabel(tx, 0, "x (Bad Bank)")
        val plain = db.utxoLabelDao().find("mainnet", tx, 0)!!
        assertEquals("x (Bad Bank)", plain.label)
        assertEquals(0, plain.badbank)
        assertNull(plain.origin)

        labels.setLabel(tx, 1, "Cold storage (Bad Bank)", markBadbank = true)
        val marked = db.utxoLabelDao().find("mainnet", tx, 1)!!
        assertEquals("Cold storage", marked.label)
        assertEquals(1, marked.badbank)
        assertNull(marked.origin)

        labels.setLabel(tx, 1, "Vault (Bad Bank)")
        val edited = db.utxoLabelDao().find("mainnet", tx, 1)!!
        assertEquals("Vault", edited.label)
        assertEquals(1, edited.badbank)
        assertEquals(marked.createdAt, edited.createdAt)
    }

    @Test
    fun autoWriteSetsTheFlagAndDoesNotBlankTheAutoLabel() = runBlocking {
        WhirlpoolLabelSink(labels).writeAutoUtxoLabel(tx, 2, "Bad Bank", origin)
        val row = db.utxoLabelDao().find("mainnet", tx, 2)!!
        assertEquals("Bad Bank", row.label)
        assertEquals(origin, row.origin)
        assertEquals(1, row.badbank)

        labels.setAutoUtxoLabel(tx, 3, "Bad Bank", origin)
        val plain = db.utxoLabelDao().find("mainnet", tx, 3)!!
        assertEquals(0, plain.badbank)

        labels.setAutoUtxoLabel(tx, 2, "Bad Bank", origin, markBadbank = false)
        assertEquals(1, db.utxoLabelDao().find("mainnet", tx, 2)!!.badbank)
    }

    @Test
    fun accountSinkInsertsAndDoesNotChurnAFlaggedRow() = runBlocking {
        val sink = LabelBadbankAccountSink(labels)
        sink.insertAuto("mainnet", tx, 4, 10)
        val inserted = labels.readUtxoLabel("mainnet", tx, 4)!!
        assertEquals("Bad Bank", inserted.label)
        assertEquals(origin, inserted.origin)
        assertEquals(1, inserted.badbank)
        assertEquals(10L, inserted.updatedAt)
        sink.setFlag("mainnet", tx, 4, 99)
        assertEquals(10L, labels.readUtxoLabel("mainnet", tx, 4)!!.updatedAt)
        assertNull(labels.readUtxoLabel("testnet", tx, 4))

        labels.setLabel(tx, 5, "Keep")
        sink.setFlag("mainnet", tx, 5, 40)
        val stuck = labels.readUtxoLabel("mainnet", tx, 5)!!
        assertEquals("Keep", stuck.label)
        assertNull(stuck.origin)
        assertEquals(1, stuck.badbank)
        assertEquals(40L, stuck.updatedAt)
    }
}
