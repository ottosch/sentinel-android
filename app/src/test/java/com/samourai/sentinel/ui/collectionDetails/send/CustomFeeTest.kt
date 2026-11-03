package com.samourai.sentinel.ui.collectionDetails.send

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.ceil

/** #77: a hand-typed fee rate on the Send screen. Pure JVM. */
class CustomFeeTest {

    @Test
    fun rateWellAboveTheStaleEstimatesIsAccepted() {
        // The reported case: estimates top out at 4 sat/vB, the user needs 20.
        val c = CustomFee.check("20", highEstimateSatPerVb = 4)
        c as CustomFee.Check.Ok
        assertEquals(20.0, c.satPerVb, 0.0)
        assertTrue("well above High gets the hint", c.aboveHighEstimate)
    }

    @Test
    fun rateNearTheEstimatesHasNoHint() {
        val c = CustomFee.check("7.5", highEstimateSatPerVb = 5) as CustomFee.Check.Ok
        assertEquals(7.5, c.satPerVb, 0.0)
        assertFalse(c.aboveHighEstimate)
    }

    @Test
    fun unknownHighEstimateNeverHints() {
        val c = CustomFee.check("500", highEstimateSatPerVb = 0) as CustomFee.Check.Ok
        assertFalse(c.aboveHighEstimate)
    }

    @Test
    fun commaDecimalAndWhitespaceAreAccepted() {
        val c = CustomFee.check(" 12,5 ", highEstimateSatPerVb = 10) as CustomFee.Check.Ok
        assertEquals(12.5, c.satPerVb, 0.0)
    }

    @Test
    fun boundsAreInclusive() {
        assertTrue(CustomFee.check("1", 4) is CustomFee.Check.Ok)
        assertTrue(CustomFee.check("1000", 4) is CustomFee.Check.Ok)
    }

    @Test
    fun belowRelayMinimumIsTooLow() {
        assertSame(CustomFee.Check.TooLow, CustomFee.check("0.5", 4))
        assertSame(CustomFee.Check.TooLow, CustomFee.check("0", 4))
        assertSame(CustomFee.Check.TooLow, CustomFee.check("-3", 4))
    }

    @Test
    fun aboveTheSanityCeilingIsTooHigh() {
        assertSame(CustomFee.Check.TooHigh, CustomFee.check("1000.01", 4))
        assertSame(CustomFee.Check.TooHigh, CustomFee.check("20000", 4))
    }

    @Test
    fun emptyAndNonNumbersAreRejected() {
        assertSame(CustomFee.Check.Empty, CustomFee.check("", 4))
        assertSame(CustomFee.Check.Empty, CustomFee.check("   ", 4))
        assertSame(CustomFee.Check.Invalid, CustomFee.check("abc", 4))
        assertSame(CustomFee.Check.Invalid, CustomFee.check("1.2.3", 4))
        assertSame(CustomFee.Check.Invalid, CustomFee.check("NaN", 4))
        assertSame(CustomFee.Check.Invalid, CustomFee.check("Infinity", 4))
    }

    @Test
    fun perKbMatchesWhatTheSliderPasses() {
        // The slider calls setFee(satPerVb * 1000).
        assertEquals(20_000f, CustomFee.perKb(20.0), 0f)
        assertEquals(12_500f, CustomFee.perKb(12.5), 0f)
        assertEquals(1_234f, CustomFee.perKb(1.2341), 0f)
    }

    @Test
    fun estimatedBlocksIsTheSliderHeuristic() {
        val low = 2L
        val med = 4L
        val high = 8L
        for (rate in listOf(1f, 2f, 3f, 4f, 6f, 8f, 20f, 100f)) {
            assertEquals("rate $rate", sliderBlocks(rate, low, med, high), CustomFee.estimatedBlocks(rate, low, med, high))
        }
        assertEquals("never below one block", 1, CustomFee.estimatedBlocks(500f, low, med, high))
    }

    @Test
    fun sendScreenBlocksComposeOnAnUnusableTypedRate() {
        val vm = source("SendViewModel.kt")
        val prepare = vm.substringAfter("private fun prepareSpend(): Boolean {")
            .substringBefore("job = viewModelScope.launch")
        assertTrue("prepareSpend must stop on an unusable fee", prepare.contains("return block(SpendBlocker.INVALID_FEE)"))
        val setFee = vm.substringAfter("fun setFee(fee: Float) {").substringBefore("fun setFeeInvalid()")
        assertTrue("a usable rate clears the block", setFee.contains("feeInvalid = false"))

        val fragment = source("SendFragment.kt")
        val apply = fragment.substringAfter("private fun applyCustomFee() {").substringBefore("private fun rangeText()")
        assertTrue(apply.contains("viewModel.setFee(CustomFee.perKb(rate))"))
        assertTrue(apply.contains("viewModel.setFeeInvalid()"))
        val slider = fragment.substringAfter("feeSlider.addOnChangeListener {").substringBefore("viewModel.setFee(value * 1000)")
        assertTrue("the slider must not override a typed rate", slider.contains("if (customFeeActive) return@addOnChangeListener"))
    }

    private fun source(name: String): String {
        val relative = "app/src/main/java/com/samourai/sentinel/ui/collectionDetails/send/$name"
        val file = sequenceOf(File(relative), File(relative.removePrefix("app/"))).firstOrNull { it.isFile }
            ?: error("missing $relative from ${File(".").absolutePath}")
        return file.readText()
    }

    /** SendFragment's slider listener before #77, verbatim in logic. */
    private fun sliderBlocks(value: Float, feeLow: Long, feeMed: Long, feeHigh: Long): Int {
        var nbBlocks: Int
        val pct: Float
        if (value <= feeLow) {
            pct = feeLow / value
            nbBlocks = ceil(pct * 24.0).toInt()
        } else if (value >= feeHigh.toFloat()) {
            pct = feeHigh / value
            nbBlocks = ceil(pct * 2.0).toInt()
            if (nbBlocks < 1) {
                nbBlocks = 1
            }
        } else {
            pct = feeMed / value
            nbBlocks = ceil(pct * 6.0).toInt()
        }
        return nbBlocks
    }
}
