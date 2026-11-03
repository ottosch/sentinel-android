package com.samourai.sentinel.ui.collectionDetails.send

import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * #77: the rules for a fee rate typed by hand on the Send screen.
 *
 * The slider only spans 1 sat/vB to 1.5 x the High estimate. When the
 * estimates are stale defaults (no Dojo, #76) or simply low, that range can
 * be far below what a congested mempool needs, and a transaction sent at
 * it gets stuck. A typed rate has no such ceiling, only a sanity range.
 *
 * Pure Kotlin, no Android imports.
 */
object CustomFee {

    /** Below this, nodes won't relay the transaction (default minrelaytxfee). */
    const val MIN_SAT_PER_VB = 1.0

    /** Sanity ceiling against a typo (an extra zero) burning the balance. */
    const val MAX_SAT_PER_VB = 1000.0

    /** A typed rate this many times the High estimate gets a gentle hint. */
    const val ABOVE_HIGH_FACTOR = 2

    sealed class Check {
        /** Nothing typed yet. */
        object Empty : Check()
        /** Not a number. */
        object Invalid : Check()
        object TooLow : Check()
        object TooHigh : Check()

        /**
         * Usable. [aboveHighEstimate] flags a rate well above the current
         * High estimate: allowed, but worth a hint in case it's a typo.
         */
        data class Ok(val satPerVb: Double, val aboveHighEstimate: Boolean) : Check()
    }

    /**
     * Judges [text] as a sat/vB rate. A comma is accepted as the decimal
     * separator. [highEstimateSatPerVb] is the High tier the slider uses;
     * 0 or less means unknown, and no hint is given.
     */
    fun check(text: String, highEstimateSatPerVb: Long): Check {
        val trimmed = text.trim().replace(',', '.')
        if (trimmed.isEmpty()) return Check.Empty
        val rate = trimmed.toDoubleOrNull() ?: return Check.Invalid
        if (rate.isNaN() || rate.isInfinite()) return Check.Invalid
        if (rate < MIN_SAT_PER_VB) return Check.TooLow
        if (rate > MAX_SAT_PER_VB) return Check.TooHigh
        val aboveHigh = highEstimateSatPerVb > 0 && rate > highEstimateSatPerVb * ABOVE_HIGH_FACTOR
        return Check.Ok(rate, aboveHigh)
    }

    /** The per-kB value SendViewModel.setFee() takes, as the slider passes it. */
    fun perKb(satPerVb: Double): Float = (satPerVb * 1000.0).roundToLong().toFloat()

    /**
     * Estimated confirmation in blocks for [rate] sat/vB against the three
     * estimate tiers - the Send slider's existing heuristic, unchanged, so a
     * typed rate and a slid rate read the same.
     */
    fun estimatedBlocks(rate: Float, low: Long, med: Long, high: Long): Int {
        val blocks = when {
            rate <= low -> ceil(low / rate * 24.0).toInt()
            rate >= high.toFloat() -> ceil(high / rate * 2.0).toInt().coerceAtLeast(1)
            else -> ceil(med / rate * 6.0).toInt()
        }
        return blocks
    }
}
