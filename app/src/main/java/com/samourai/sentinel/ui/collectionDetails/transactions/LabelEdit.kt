package com.samourai.sentinel.ui.collectionDetails.transactions

import com.samourai.sentinel.data.whirlpool.WhirlpoolLabelPolicy

/**
 * What the transaction-label and coin-label editors do with a Save
 * that did not change anything. Pure JVM.
 *
 * An unchanged save must not call the manual writer. That writer
 * stores a null origin, and a null origin stops the per-account Tx0
 * remap and phase-1 Bad Bank propagation.
 *
 * [shown] is the text on screen. It is null or blank when the row
 * has no label, and also when an automatic transaction label is
 * hidden because Whirlpool auto-labels is off. Coin labels are never
 * hidden by that setting, so for a coin [shown] is the stored text.
 */
object LabelEdit {

    /** Editor prefill: the text on screen, or empty when nothing is shown. */
    fun prefill(shown: String?): String = shown?.trim()?.takeIf { it.isNotEmpty() }.orEmpty()

    /**
     * True when Save must not write.
     *
     * On an `auto:` row, a non-empty trimmed input that matches the
     * text on screen or the stored text is unchanged, and a blank
     * input is unchanged when nothing is on screen (the auto label
     * is hidden). A blank input over a visible label is a hand clear
     * and is not a no-op. Typing a new label over a hidden auto row
     * is a real edit, even when the typed text equals the stored
     * auto label.
     *
     * On a manual or imported row, the trimmed input matching the
     * stored text is unchanged, including saving a blank clear again.
     * A missing row is never a no-op: an empty save is still the
     * existing hand clear.
     */
    fun isNoOp(
        input: String,
        stored: String?,
        origin: String?,
        shown: String?,
    ): Boolean {
        val trimmed = input.trim()
        val shownText = shown?.trim().orEmpty()
        val storedText = stored?.trim().orEmpty()
        val auto = origin?.startsWith(WhirlpoolLabelPolicy.AUTO_NAMESPACE) == true
        if (auto) {
            if (shownText.isEmpty()) return trimmed.isEmpty()
            return trimmed.isNotEmpty() && (trimmed == shownText || trimmed == storedText)
        }
        if (stored == null) return false
        return trimmed == storedText
    }
}
