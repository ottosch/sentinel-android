package com.samourai.sentinel.ui.home

import com.samourai.sentinel.data.repository.ExchangeRateRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Hide-fiat must remove the line, not replace a real amount with "00.00". */
class HideFiatPlaceholderTest {

    private val rate = ExchangeRateRepository.Rate("USD", 50_000L)

    @Test
    fun `missing rate is blank and hide clears the fiat line`() {
        assertEquals("", homeFiatLine(fiatDisabled = false, balanceSats = 100_000_000L, rate = null, ::dollars))
        assertEquals("", homeFiatLine(fiatDisabled = false, balanceSats = 100_000_000L, rate = ExchangeRateRepository.Rate("USD", 0L), ::dollars))
        assertEquals("", homeFiatLine(fiatDisabled = false, balanceSats = null, rate = rate, ::dollars))
        assertEquals("", homeFiatLine(fiatDisabled = true, balanceSats = 100_000_000L, rate = rate, ::dollars))
        val shown = homeFiatLine(fiatDisabled = false, balanceSats = 100_000_000L, rate = rate, ::dollars)
        assertEquals("50000.00 USD", shown)
        assertFalse(shown == "00.00")

        val hidden = presentFiatLine(show = true, fiat = "")
        assertEquals("", hidden.text)
        assertFalse(hidden.visible)
        val suppressed = presentFiatLine(show = false, fiat = shown)
        assertEquals("", suppressed.text)
        assertFalse(suppressed.visible)
        val visible = presentFiatLine(show = true, fiat = shown)
        assertEquals(shown, visible.text)
        assertTrue(visible.visible)

        assertEquals(true, fiatDisabledPreference(true))
        assertEquals(false, fiatDisabledPreference(false))
    }

    private fun dollars(amount: Double): String = String.format(Locale.US, "%.2f", amount)
}
