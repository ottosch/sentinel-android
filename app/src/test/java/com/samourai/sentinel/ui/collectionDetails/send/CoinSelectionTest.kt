package com.samourai.sentinel.ui.collectionDetails.send

import com.samourai.sentinel.data.Utxo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #190: the pinned-selection rules. Pure JVM.
 */
class CoinSelectionTest {

    private fun utxo(txid: String, vout: Int, value: Long) =
        Utxo(idx = "$txid-$vout", txHash = txid, txOutputN = vout, value = value)

    private val a = utxo("aa", 0, 10_000L)
    private val b = utxo("bb", 1, 20_000L)
    private val c = utxo("cc", 0, 30_000L)
    private val pool = listOf(a, b, c)
    private val nothingBlocked: (Utxo) -> Boolean = { false }

    @Test
    fun emptyPinIsAutomaticSelection() {
        assertSame(
            CoinSelection.Resolution.Auto,
            CoinSelection.resolve(emptySet(), pool, nothingBlocked)
        )
    }

    @Test
    fun pinnedSetIsExactlyThePinnedCoinsInPoolOrder() {
        val r = CoinSelection.resolve(setOf("cc:0", "aa:0"), pool, nothingBlocked)
        r as CoinSelection.Resolution.Pinned
        assertEquals(listOf(a, c), r.utxos)
        assertEquals(40_000L, r.totalSats)
    }

    @Test
    fun keyIsTxidColonVout() {
        assertEquals("bb:1", CoinSelection.keyOf(b))
        // Same txid, other vout, must not match.
        val r = CoinSelection.resolve(setOf("bb:0"), pool, nothingBlocked)
        assertEquals(CoinSelection.Resolution.Missing(setOf("bb:0")), r)
    }

    @Test
    fun aPinnedCoinMarkedDoNotSpendFailsTheWholeSelection() {
        // Hard strip: a frozen coin never reaches compose, and the rest of
        // the pin is not composed in its place.
        val r = CoinSelection.resolve(setOf("aa:0", "bb:1"), pool) { it === b }
        assertEquals(CoinSelection.Resolution.Blocked(setOf("bb:1")), r)
    }

    @Test
    fun aPinnedCoinNoLongerInThePoolFailsTheWholeSelection() {
        val r = CoinSelection.resolve(setOf("aa:0", "dd:2"), pool, nothingBlocked)
        assertEquals(CoinSelection.Resolution.Missing(setOf("dd:2")), r)
    }

    @Test
    fun missingIsReportedBeforeBlocked() {
        val r = CoinSelection.resolve(setOf("aa:0", "dd:2"), pool) { it === a }
        assertTrue(r is CoinSelection.Resolution.Missing)
    }

    @Test
    fun blockedCoinsOutsideThePinDoNotMatter() {
        val r = CoinSelection.resolve(setOf("aa:0"), pool) { it === c }
        assertEquals(CoinSelection.Resolution.Pinned(listOf(a), 10_000L), r)
    }

    @Test
    fun coSpendWarningOnlyWhenBadBankIsMixedWithOtherCoins() {
        val labels = mapOf("aa:0" to "Bad Bank", "bb:1" to "Bad Bank", "cc:0" to "salary")
        assertTrue(CoinSelection.mixesBadBank(listOf(a, c), labels))
        assertTrue(CoinSelection.mixesBadBank(listOf(a, b, c), labels))
        assertFalse("all badbank: nothing new is linked", CoinSelection.mixesBadBank(listOf(a, b), labels))
        assertFalse("no badbank", CoinSelection.mixesBadBank(listOf(c), labels))
        assertFalse("single coin", CoinSelection.mixesBadBank(listOf(a), labels))
    }

    @Test
    fun unlabelledCoinsCountAsNotBadBank() {
        val labels = mapOf("aa:0" to "Bad Bank")
        assertTrue(CoinSelection.mixesBadBank(listOf(a, b), labels))
    }

    @Test
    fun badBankMatchIsCaseAndWhitespaceTolerantAndTxidCaseTolerant() {
        val upper = utxo("AA", 0, 1L)
        val labels = mapOf("aa:0" to " bad bank ")
        assertTrue(CoinSelection.isBadBank(upper, labels))
        assertEquals(" bad bank ", CoinSelection.labelFor(upper, labels))
    }
}
