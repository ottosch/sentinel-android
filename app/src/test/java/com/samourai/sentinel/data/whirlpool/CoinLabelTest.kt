package com.samourai.sentinel.data.whirlpool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * User part and composed Bad Bank display. The suffix is render-only.
 */
class CoinLabelTest {

    @Test
    fun userPartStripsTheSuffixAndTheExactBadBankLabel() {
        assertEquals("", CoinLabel.userPart(null))
        assertEquals("", CoinLabel.userPart(""))
        assertEquals("", CoinLabel.userPart("Bad Bank"))
        assertEquals("", CoinLabel.userPart("bad bank"))
        assertEquals("x", CoinLabel.userPart("x (Bad Bank)"))
        assertEquals("x", CoinLabel.userPart("x (Bad Bank) (Bad Bank)"))
        assertEquals("x", CoinLabel.userPart("x  (bad bank) "))
        assertEquals("x", CoinLabel.userPart("x(Bad Bank)"))
    }

    @Test
    fun displayComposesOnlyWhenTheCoinIsBadBank() {
        assertEquals("x (Bad Bank)", CoinLabel.display("x (Bad Bank)", false))
        assertEquals("  kept  ", CoinLabel.display("  kept  ", false))
        assertNull(CoinLabel.display(null, false))
        assertEquals("Bad Bank", CoinLabel.display(null, true))
        assertEquals("Bad Bank", CoinLabel.display("", true))
        assertEquals("Bad Bank", CoinLabel.display("Bad Bank", true))
        assertEquals("Cold storage (Bad Bank)", CoinLabel.display("Cold storage", true))
        assertEquals("Cold storage (Bad Bank)", CoinLabel.display("Cold storage (Bad Bank)", true))
    }

    @Test
    fun displayIsIdempotentForABadBankCoin() {
        val samples = listOf(
            null,
            "",
            "Bad Bank",
            "x",
            "x (Bad Bank)",
            "x (Bad Bank) (Bad Bank)",
            "x  (bad bank) ",
            "x(Bad Bank)",
        )
        for (sample in samples) {
            assertEquals(CoinLabel.display(sample, true), CoinLabel.display(CoinLabel.display(sample, true), true))
        }
    }

    @Test(timeout = 2_000)
    fun userPartIsLinearOnHostileLabels() {
        val inputs = listOf(
            "a" + " ".repeat(200_000) + "b",
            "a" + " ".repeat(200_000) + "(Bad Bank",
            "(Bad Bank)".repeat(20_000),
            "x" + " (bad bank)".repeat(20_000),
        )
        for (s in inputs) CoinLabel.display(s, true)
        assertEquals("x (Bad Bank)", CoinLabel.display("x" + " (bad bank)".repeat(20_000), true))
    }
}
