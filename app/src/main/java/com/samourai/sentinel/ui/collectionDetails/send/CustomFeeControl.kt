package com.samourai.sentinel.ui.collectionDetails.send

import android.text.InputType
import android.view.View
import androidx.core.widget.addTextChangedListener
import com.samourai.sentinel.R
import com.samourai.sentinel.data.repository.FeeFreshness
import com.samourai.sentinel.databinding.FeeSelectorBinding
import com.samourai.sentinel.ui.utils.hideKeyboard
import java.text.DecimalFormat

/**
 * #77: the typed fee rate and the estimate-freshness note, on the shared
 * fee selector (fee_selector.xml) used by Send and the Sweep preview.
 *
 * "Custom" swaps the slider for a sat/vB field judged by [CustomFee];
 * "Use slider" goes back. The screen supplies what is its own: what a
 * usable rate does ([onRate], in per-kB as the slider passes it), how to
 * block spending while the field holds no usable rate ([onUnusable]), and
 * how to put the slider's rate back ([onBackToSlider]).
 */
class CustomFeeControl(
    private val feeSelector: FeeSelectorBinding,
    /** Sweep's fee math takes whole sat/vB only. */
    private val wholeOnly: Boolean,
    /** The tiers the slider uses, sat/vB: low, normal, high. */
    private val tiers: () -> Triple<Long, Long, Long>,
    /** The slider's current rate, sat/vB. */
    private val sliderRate: () -> Float,
    private val onRate: (perKb: Float) -> Unit,
    private val onUnusable: () -> Unit,
    private val onBackToSlider: () -> Unit,
) {

    /** True while a typed rate owns the fee; the slider must then stay out. */
    var active: Boolean = false
        private set

    private val context get() = feeSelector.root.context
    private val format = DecimalFormat("0.##")

    fun attach() {
        feeSelector.customFeeToggle.visibility = View.VISIBLE
        feeSelector.customFeeToggle.setOnClickListener { setActive(!active) }
        if (wholeOnly) {
            feeSelector.customFeeInput.inputType = InputType.TYPE_CLASS_NUMBER
        }
        feeSelector.customFeeInput.addTextChangedListener {
            if (active) apply()
        }
        feeSelector.customFeeInput.setOnEditorActionListener { view, _, _ ->
            view.hideKeyboard()
            false
        }
    }

    /** #77 option 4: names old or default estimates; hidden when fresh. */
    fun showFreshness(freshness: FeeFreshness) {
        val note = feeSelector.feeFreshnessNote
        val text = when (freshness) {
            FeeFreshness.Fresh -> null
            is FeeFreshness.Stale -> context.getString(
                R.string.fee_freshness_stale,
                FeeFreshness.describeAge(freshness.ageMs)
            )
            FeeFreshness.UnknownAge -> context.getString(R.string.fee_freshness_unknown)
            FeeFreshness.Defaults -> context.getString(R.string.fee_freshness_defaults)
        }
        note.text = text.orEmpty()
        note.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun setActive(value: Boolean) {
        active = value
        feeSelector.customFeeLayout.visibility = if (value) View.VISIBLE else View.GONE
        feeSelector.feeSlider.isEnabled = !value
        feeSelector.feeSlider.alpha = if (value) 0.4f else 1f
        feeSelector.customFeeToggle.text =
            context.getString(if (value) R.string.fee_use_slider else R.string.fee_custom)
        if (value) {
            val input = feeSelector.customFeeInput
            if (input.text.isNullOrBlank()) {
                // Start from the slider's rate, so turning Custom on changes nothing yet.
                val start = sliderRate().toDouble()
                input.setText(if (wholeOnly) Math.ceil(start).toLong().toString() else format.format(start))
            }
            input.setSelection(input.text?.length ?: 0)
            input.requestFocus()
            apply()
        } else {
            feeSelector.customFeeLayout.error = null
            feeSelector.customFeeLayout.helperText = null
            onBackToSlider()
        }
    }

    private fun apply() {
        val (low, med, high) = tiers()
        val layout = feeSelector.customFeeLayout
        val text = feeSelector.customFeeInput.text?.toString().orEmpty()
        when (val check = CustomFee.check(text, high, wholeOnly)) {
            is CustomFee.Check.Ok -> {
                layout.error = null
                layout.helperText = if (check.aboveHighEstimate) {
                    context.getString(R.string.fee_custom_above_high, high)
                } else {
                    null
                }
                val rate = check.satPerVb
                feeSelector.selectedFeeRate.text = "${format.format(rate)} sat/vB"
                feeSelector.selectedFeeRateLayman.setText(R.string.fee_custom)
                val blocks = CustomFee.estimatedBlocks(rate.toFloat(), low, med, high)
                feeSelector.estBlockTime.text = blocksText(blocks)
                onRate(CustomFee.perKb(rate))
            }
            else -> {
                layout.helperText = null
                layout.error = when (check) {
                    CustomFee.Check.TooLow -> context.getString(R.string.custom_fee_too_low) + " " + rangeText()
                    CustomFee.Check.TooHigh -> context.getString(R.string.custom_fee_too_high) + " " + rangeText()
                    CustomFee.Check.NotWhole -> context.getString(R.string.fee_custom_whole_only)
                    CustomFee.Check.Invalid -> context.getString(R.string.fee_custom_invalid)
                    // Nothing typed yet: no error, but nothing to spend with either.
                    else -> null
                }
                feeSelector.selectedFeeRateLayman.setText(R.string.fee_custom)
                feeSelector.selectedFeeRate.text = "-"
                feeSelector.estBlockTime.text = "-"
                onUnusable()
            }
        }
    }

    private fun rangeText(): String = context.getString(
        R.string.fee_custom_range,
        format.format(CustomFee.MIN_SAT_PER_VB),
        format.format(CustomFee.MAX_SAT_PER_VB)
    )

    companion object {
        fun blocksText(blocks: Int): String = if (blocks > 50) "50+ blocks" else "$blocks blocks"
    }
}
