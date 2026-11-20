package com.samourai.sentinel.data.whirlpool

import com.samourai.wallet.util.XPUB
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Always-on account labeller. A live Bad Bank coin is flagged.
 * Every other account, and every key that is not an account xpub,
 * is left alone. Writes are scoped to the network argument.
 */
class BadbankAccountLabelerTest {

    private val cfg = WhirlpoolConfig.DEFAULT
    private val txid = "ab".repeat(32)

    private class Fake : BadbankAccountLabeler.Sink {
        val rows = HashMap<Key, BadbankAccountLabeler.Row>()
        val inserts = ArrayList<Key>()
        val flags = ArrayList<Key>()

        override suspend fun find(network: String, txid: String, vout: Int) =
            rows[Key(network, txid, vout)]

        override suspend fun insertAuto(network: String, txid: String, vout: Int, now: Long) {
            val key = Key(network, txid, vout)
            inserts += key
            rows[key] = BadbankAccountLabeler.Row(
                WhirlpoolConfig.DEFAULT.labels.badbank,
                WhirlpoolConfig.DEFAULT.origin,
                1,
            )
        }

        override suspend fun setFlag(network: String, txid: String, vout: Int, now: Long) {
            val key = Key(network, txid, vout)
            flags += key
            val existing = rows.getValue(key)
            rows[key] = existing.copy(badbank = 1)
        }

        data class Key(val network: String, val txid: String, val vout: Int)
    }

    private fun coin(pub: String, id: String = txid, vout: Int = 0) =
        BadbankAccountLabeler.Coin(id, vout, pub)

    @Test
    fun noRowInsertsTheAutoLabelAndTheFlag() = runBlocking {
        val sink = Fake()
        BadbankAccountLabeler(sink).apply(listOf(coin(accountXpub(cfg.badbankAccount))), "mainnet", now = 7)
        val row = sink.rows.getValue(Fake.Key("mainnet", txid, 0))
        assertEquals("Bad Bank", row.label)
        assertEquals(cfg.origin, row.origin)
        assertEquals(1, row.badbank)
        assertEquals(1, sink.inserts.size)
        assertTrue(sink.flags.isEmpty())
    }

    @Test
    fun manualRowGainsTheFlagAndKeepsItsLabel() = runBlocking {
        val sink = Fake()
        val key = Fake.Key("mainnet", txid, 0)
        sink.rows[key] = BadbankAccountLabeler.Row("Cold storage", null, 0)
        BadbankAccountLabeler(sink).apply(listOf(coin(accountXpub(cfg.badbankAccount))), "mainnet", now = 8)
        val row = sink.rows.getValue(key)
        assertEquals("Cold storage", row.label)
        assertEquals(null, row.origin)
        assertEquals(1, row.badbank)
        assertTrue(sink.inserts.isEmpty())
        assertEquals(listOf(key), sink.flags)
    }

    @Test
    fun importedRowGainsTheFlagAndKeepsLabelAndOrigin() = runBlocking {
        val sink = Fake()
        val key = Fake.Key("mainnet", txid, 0)
        sink.rows[key] = BadbankAccountLabeler.Row("Exchange", "sparrow", 0)
        BadbankAccountLabeler(sink).apply(listOf(coin(accountXpub(cfg.badbankAccount))), "mainnet", now = 8)
        val row = sink.rows.getValue(key)
        assertEquals("Exchange", row.label)
        assertEquals("sparrow", row.origin)
        assertEquals(1, row.badbank)
    }

    @Test
    fun autoRowGainsTheFlagWithoutARewriteWhenAlreadySet() = runBlocking {
        val sink = Fake()
        val key = Fake.Key("mainnet", txid, 0)
        sink.rows[key] = BadbankAccountLabeler.Row("Bad Bank", cfg.origin, 0)
        val labeler = BadbankAccountLabeler(sink)
        labeler.apply(listOf(coin(accountXpub(cfg.badbankAccount))), "mainnet", now = 8)
        assertEquals(BadbankAccountLabeler.Row("Bad Bank", cfg.origin, 1), sink.rows.getValue(key))
        assertEquals(1, sink.flags.size)
        labeler.apply(listOf(coin(accountXpub(cfg.badbankAccount))), "mainnet", now = 99)
        assertEquals(1, sink.flags.size)
        assertTrue(sink.inserts.isEmpty())
    }

    @Test
    fun depositPremixAndPostmixAreUntouched() = runBlocking {
        val sink = Fake()
        val coins = listOf(
            coin(accountXpub(cfg.depositAccount), id = "d".repeat(64)),
            coin(accountXpub(cfg.premixAccount), id = "e".repeat(64)),
            coin(accountXpub(cfg.postmixAccount), id = "f".repeat(64)),
        )
        BadbankAccountLabeler(sink).apply(coins, "mainnet")
        assertTrue(sink.rows.isEmpty())
        assertTrue(sink.inserts.isEmpty())
        assertTrue(sink.flags.isEmpty())
    }

    @Test
    fun addressesOddDepthAndUndecodableKeysAreSkipped() = runBlocking {
        val sink = Fake()
        val coins = listOf(
            coin("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"),
            coin("not-an-xpub"),
            coin(""),
            coin(accountXpub(cfg.badbankAccount, depth = 2)),
            coin(accountXpub(cfg.badbankAccount, depth = 4)),
            coin("z".repeat(121)),
            coin("z".repeat(10_000)),
        )
        BadbankAccountLabeler(sink).apply(coins, "mainnet")
        assertTrue(sink.rows.isEmpty())
    }

    @Test
    fun zpubVpubAndTpubFormsAreTheSameAccount() = runBlocking {
        val sink = Fake()
        val versions = listOf(
            XPUB.MAGIC_XPUB,
            XPUB.MAGIC_YPUB,
            XPUB.MAGIC_ZPUB,
            XPUB.MAGIC_TPUB,
            XPUB.MAGIC_UPUB,
            XPUB.MAGIC_VPUB,
        )
        val coins = versions.mapIndexed { index, version ->
            coin(accountXpub(cfg.badbankAccount, version = version), id = "ab".repeat(31) + "0$index", vout = index)
        }
        BadbankAccountLabeler(sink).apply(coins, "testnet", now = 3)
        assertEquals(versions.size, sink.inserts.size)
        assertTrue(sink.inserts.all { it.network == "testnet" })
    }

    @Test
    fun networkScopingDoesNotTouchTheOtherNetwork() = runBlocking {
        val sink = Fake()
        val key = Fake.Key("mainnet", txid, 0)
        sink.rows[key] = BadbankAccountLabeler.Row("Keep", "sparrow", 0)
        val labeler = BadbankAccountLabeler(sink)
        val bad = coin(accountXpub(cfg.badbankAccount))
        labeler.apply(listOf(bad), "mainnet", now = 50)
        assertEquals(1, sink.rows.getValue(key).badbank)
        assertEquals("Keep", sink.rows.getValue(key).label)
        labeler.apply(listOf(bad), "testnet", now = 80)
        val testnet = sink.rows.getValue(Fake.Key("testnet", txid, 0))
        assertEquals("Bad Bank", testnet.label)
        assertEquals(cfg.origin, testnet.origin)
        assertEquals(1, testnet.badbank)
        assertEquals("Keep", sink.rows.getValue(key).label)
        assertEquals("sparrow", sink.rows.getValue(key).origin)
        assertEquals(listOf(key), sink.flags)
    }

    @Test(timeout = 2_000)
    fun keysLongerThan120CharsAreRejectedBeforeBase58() {
        val valid = accountXpub(cfg.badbankAccount)
        assertTrue(valid.length <= AccountKey.MAX_KEY_CHARS)
        assertEquals(cfg.badbankAccount, AccountKey.accountOf(valid))
        assertEquals(null, AccountKey.accountOf("x".repeat(AccountKey.MAX_KEY_CHARS)))
        assertEquals(null, AccountKey.accountOf("x".repeat(AccountKey.MAX_KEY_CHARS + 1)))
        val pastTheCap = "z".repeat(AccountKey.MAX_KEY_CHARS) + valid
        assertTrue(pastTheCap.length > AccountKey.MAX_KEY_CHARS)
        assertEquals(null, AccountKey.accountOf(pastTheCap))
        assertEquals(null, AccountKey.accountOf("z".repeat(100_000)))
    }

    @Test
    fun saveUtxosHooksTheLabellerWithNoPreferenceGate() {
        val src = source(
            "src/main/java/com/samourai/sentinel/data/repository/TransactionsRepository.kt",
            "app/src/main/java/com/samourai/sentinel/data/repository/TransactionsRepository.kt",
        )
        val fn = src.substring(
            src.indexOf("suspend fun saveUtxos"),
            src.indexOf("suspend fun removeTxsRelatedToPubKey"),
        )
        val upsert = fn.indexOf("upsertCollectionUtxos")
        assertTrue(upsert >= 0)
        val after = fn.substring(upsert)
        val hook = after.indexOf("badbankAccountLabeler.apply")
        assertTrue(hook > 0)
        val wrapped = after.substring(0, hook)
        assertTrue(wrapped.contains("runCatching"))
        assertFalse(after.contains("whirlpoolAutoLabels"))
    }

    private fun accountXpub(account: Long, version: Int = XPUB.MAGIC_ZPUB, depth: Int = 3): String {
        val child = (account - 2_147_483_648L).toInt()
        return XPUB.makeXPUB(
            version,
            depth.toByte(),
            0,
            child,
            ByteArray(32) { 0x11 },
            ByteArray(33) { 0x02 },
        )
    }

    private fun source(vararg relative: String): String {
        val file = relative.map { File(it) }.firstOrNull { it.isFile }
            ?: error("source not found: ${relative.toList()}")
        return file.readText()
    }
}
