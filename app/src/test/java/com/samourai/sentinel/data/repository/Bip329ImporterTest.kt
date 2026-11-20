package com.samourai.sentinel.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.data.db.SentinelRoomDb
import com.samourai.sentinel.data.db.entity.LabelEntry
import com.samourai.sentinel.data.db.entity.UtxoLabel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.PrintWriter
import java.io.StringReader
import java.io.StringWriter

/**
 * Merge-table + round-trip suite over sample.jsonl (fixture-first, same
 * pattern as Bip329ParserTest). Pure JVM: Room in-memory via bundled
 * SQLite driver; seams (db/network/clock) injected, no Koin/SentinelState.
 *
 * A blank tx or output label is stored as a manual clear. A blank
 * label on any other type still deletes, and that delete increments
 * ImportResult.updated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Bip329ImporterTest {

    private lateinit var db: SentinelRoomDb
    private lateinit var importer: Bip329Importer

    private val txid =
        "c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0"
    private val outTxid =
        "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SentinelRoomDb::class.java
        ).allowMainThreadQueries().build()
        importer = Bip329Importer(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun fixture(): List<String> =
        javaClass.getResourceAsStream("/bip329/sample.jsonl")!!
            .bufferedReader().readLines().filter { it.isNotBlank() }

    private fun import(
        lines: List<String>,
        network: String = "mainnet",
        now: Long = 1000L
    ): ImportResult = runBlocking {
        importer.import(BufferedReader(StringReader(lines.joinToString("\n"))),
                        network, now)
    }

    private fun export(network: String = "mainnet"): String {
        val sw = StringWriter()
        runBlocking {
            Bip329Exporter(db.utxoLabelDao(), db.labelEntryDao())
                .export(PrintWriter(sw), network)
        }
        return sw.toString()
    }

    @Test
    fun mergeTableOnMainnetFixture() {
        val s = import(fixture())
        assertEquals(8, s.imported)
        assertEquals(0, s.updated)
        assertEquals(5, s.skipped)
        assertEquals(1, s.noLabel)
        assertEquals(2, runBlocking { db.utxoLabelDao().getAll("mainnet").size })
        assertEquals(6, runBlocking { db.labelEntryDao().getAll("mainnet").size })
    }

    @Test
    fun crossNetworkSwapsOnTestnet() {
        val s = import(fixture(), network = "testnet")
        assertEquals(8, s.imported)
        assertEquals(5, s.skipped)
        assertEquals(1, s.noLabel)
        val entries = runBlocking { db.labelEntryDao().getAll("testnet") }
        assertTrue(entries.any { it.type == "addr" && it.ref.startsWith("tb1") })
        assertTrue(entries.any { it.type == "xpub" && it.ref.startsWith("tpub") })
        assertTrue(entries.none { it.ref.startsWith("bc1") })
        assertTrue(entries.none { it.type == "xpub" && it.ref.startsWith("xpub") })
    }

    @Test
    fun upsertPreservesCreatedAtAndMovesUpdatedAt() {
        import(fixture(), now = 1000L)
        val before = runBlocking { db.labelEntryDao().find("mainnet", "tx", txid) }!!
        val s = import(fixture(), now = 2000L)
        assertEquals(8, s.updated)
        assertEquals(0, s.imported)
        val after = runBlocking { db.labelEntryDao().find("mainnet", "tx", txid) }!!
        assertEquals(before.createdAt, after.createdAt)
        assertEquals(2000L, after.updatedAt)
    }

    @Test
    fun blankTxAndOutputImportStoresAManualClear() {
        import(fixture(), now = 1000L)
        val beforeTx = runBlocking { db.labelEntryDao().find("mainnet", "tx", txid) }!!
        val beforeOut = runBlocking { db.utxoLabelDao().find("mainnet", outTxid, 0) }!!
        val fresh = "ab".repeat(32)
        val s = import(listOf(
            """{"type":"tx","ref":"$txid","label":"","origin":"auto:whirlpool:v1"}""",
            """{"type":"output","ref":"$outTxid:0","label":"  "}""",
            """{"type":"tx","ref":"$fresh","label":""}""",
        ), now = 2000L)
        assertEquals(1, s.imported)
        assertEquals(2, s.updated)
        val tx = runBlocking { db.labelEntryDao().find("mainnet", "tx", txid) }!!
        assertEquals("", tx.label)
        assertNull(tx.origin)
        assertEquals(beforeTx.createdAt, tx.createdAt)
        assertEquals(2000L, tx.updatedAt)
        val utxo = runBlocking { db.utxoLabelDao().find("mainnet", outTxid, 0) }!!
        assertEquals("", utxo.label)
        assertNull(utxo.origin)
        assertEquals(beforeOut.createdAt, utxo.createdAt)
        val created = runBlocking { db.labelEntryDao().find("mainnet", "tx", fresh) }!!
        assertEquals("", created.label)
        assertNull(created.origin)
    }

    @Test
    fun blankAddressImportStillDeletes() {
        import(fixture())
        val addr = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjtx0v3q"
        val xpub = "xpub6BgBge6esM6jW6g5G6J5G6K5G6J5G6K5G6J5G6K5G6J5G6K5G6J5G6K5G6J5G6K5G6"
        val s = import(listOf(
            """{"type":"addr","ref":"$addr","label":""}""",
            """{"type":"xpub","ref":"$xpub","label":""}""",
        ))
        assertEquals(0, s.imported)
        assertEquals(2, s.updated)
        assertNull(runBlocking { db.labelEntryDao().find("mainnet", "addr", addr) })
        assertNull(runBlocking { db.labelEntryDao().find("mainnet", "xpub", xpub) })
    }

    @Test
    fun absentLabelIsNoOpNotError() {
        import(fixture())
        val before = runBlocking { db.labelEntryDao().getAll("mainnet").size }
        val s = import(listOf(fixture()[12]))
        assertEquals(1, s.noLabel)
        assertEquals(0, s.imported + s.updated + s.skipped)
        assertEquals(before, runBlocking { db.labelEntryDao().getAll("mainnet").size })
    }

    @Test
    fun exporterOmitsABlankManualClear() {
        import(fixture())
        runBlocking {
            db.labelEntryDao().upsert(
                LabelEntry(
                    network = "mainnet",
                    type = "tx",
                    ref = "ab".repeat(32),
                    label = "",
                    origin = null,
                    createdAt = 1,
                    updatedAt = 1,
                )
            )
        }
        val lines = export().lineSequence().filter { it.isNotBlank() }.toList()
        assertEquals(7, lines.size)
        assertTrue(lines.none { org.json.JSONObject(it).optString("label").isEmpty() })
    }

    @Test
    fun exporterRoundTripIsFixedPointAndPreservesOrigin() {
        import(fixture())
        val first = export()
        val lines = first.lineSequence().filter { it.isNotBlank() }.toList()
        assertEquals(7, lines.size)
        val s = import(lines)
        assertEquals(7, s.updated)
        assertEquals(0, s.imported + s.skipped + s.noLabel)
        assertEquals(first, export())
 // Android org.json escapes forward slashes on serialization
 // (AOSP JSONStringer), so raw string matching on origin is
 // escaping-fragile. Assert preservation semantically: parse
 // the exported line and compare the decoded origin value.
 val originLine = lines.first { org.json.JSONObject(it).has("origin") }
 assertEquals(
 "[f79a0d3d/84'/0'/0']",
 org.json.JSONObject(originLine).optString("origin")
 )
    }

    @Test
    fun exportKeepsAutomaticTransactionLabelsWithTheirOrigin() {
        val exporter = listOf(
            File("src/main/java/com/samourai/sentinel/data/repository/Bip329Exporter.kt"),
            File("app/src/main/java/com/samourai/sentinel/data/repository/Bip329Exporter.kt"),
        ).first { it.isFile }.readText()
        assertFalse(
            "export is a backup and must not consult the display toggle",
            exporter.contains("whirlpoolAutoLabels"),
        )
        val origin = "auto:whirlpool:v1"
        val autoTx = "11".repeat(32)
        val mixTx = "22".repeat(32)
        val manualTx = "33".repeat(32)
        val importedTx = "44".repeat(32)
        val coinTx = "55".repeat(32)
        runBlocking {
            db.labelEntryDao().upsert(txEntry(autoTx, "Whirlpool Tx0", origin))
            db.labelEntryDao().upsert(txEntry(mixTx, "Whirlpool Mix", origin))
            db.labelEntryDao().upsert(txEntry(manualTx, "rent", null))
            db.labelEntryDao().upsert(txEntry(importedTx, "from sparrow", "sparrow"))
            db.utxoLabelDao().upsert(
                UtxoLabel(
                    network = "mainnet",
                    txid = coinTx,
                    vout = 1,
                    label = "Bad Bank",
                    origin = origin,
                    createdAt = 1,
                    updatedAt = 1,
                )
            )
        }
        val first = export()
        val lines = first.lineSequence().filter { it.isNotBlank() }.map { JSONObject(it) }.toList()
        fun row(type: String, ref: String) =
            lines.single { it.getString("type") == type && it.getString("ref") == ref }
        assertEquals("Whirlpool Tx0", row("tx", autoTx).getString("label"))
        assertEquals(origin, row("tx", autoTx).getString("origin"))
        assertEquals("Whirlpool Mix", row("tx", mixTx).getString("label"))
        assertEquals(origin, row("tx", mixTx).getString("origin"))
        assertEquals("rent", row("tx", manualTx).getString("label"))
        assertFalse(row("tx", manualTx).has("origin"))
        assertEquals("from sparrow", row("tx", importedTx).getString("label"))
        assertEquals("sparrow", row("tx", importedTx).getString("origin"))
        assertEquals("Bad Bank", row("output", "$coinTx:1").getString("label"))
        assertEquals(origin, row("output", "$coinTx:1").getString("origin"))

        val round = import(first.lineSequence().filter { it.isNotBlank() }.toList())
        assertEquals(0, round.skipped + round.noLabel)
        val stored = runBlocking { db.labelEntryDao().find("mainnet", "tx", autoTx) }!!
        assertEquals("Whirlpool Tx0", stored.label)
        assertEquals(origin, stored.origin)
        val mix = runBlocking { db.labelEntryDao().find("mainnet", "tx", mixTx) }!!
        assertEquals(origin, mix.origin)
        val coin = runBlocking { db.utxoLabelDao().find("mainnet", coinTx, 1) }!!
        assertEquals("Bad Bank", coin.label)
        assertEquals(origin, coin.origin)
        assertEquals(first, export())
    }

    private fun txEntry(ref: String, label: String, origin: String?) = LabelEntry(
        network = "mainnet",
        type = "tx",
        ref = ref,
        label = label,
        origin = origin,
        createdAt = 1,
        updatedAt = 1,
    )
}
