package com.samourai.sentinel.ui.home

import com.samourai.sentinel.data.repository.ExchangeRateRepository

/**
 * The fiat amount under a balance. A missing or non-positive rate is
 * blank, and so is a hidden fiat preference. Callers hide the line
 * instead of painting a placeholder amount.
 */
internal fun homeFiatLine(
    fiatDisabled: Boolean,
    balanceSats: Long?,
    rate: ExchangeRateRepository.Rate?,
    formatFiat: (Double) -> String,
): String {
    if (fiatDisabled) return ""
    if (rate == null || rate.rate <= 0L) return ""
    val sats = balanceSats ?: return ""
    return try {
        val amount = formatFiat((sats / 1e8) * rate.rate)
        "$amount ${rate.currency}"
    } catch (e: Exception) {
        ""
    }
}

/** Blank text is hidden. A hidden line is blank, never a stand-in amount. */
internal data class PresentedFiatLine(val text: String, val visible: Boolean)

internal fun presentFiatLine(show: Boolean, fiat: String?): PresentedFiatLine =
    if (!show || fiat.isNullOrBlank()) {
        PresentedFiatLine(text = "", visible = false)
    } else {
        PresentedFiatLine(text = fiat, visible = true)
    }

/** The Settings switch writes this boolean into the fiat preference. */
internal fun fiatDisabledPreference(newValue: Any?): Boolean = newValue as Boolean
