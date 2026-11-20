package com.samourai.sentinel.data.whirlpool

/**
 * Composes a coin label from the stored user part and the Bad Bank
 * fact. The suffix is never stored, so an edit or import cannot drop
 * the marking and cannot double it.
 *
 * Pure JVM. No Android, Room, or network.
 */
object CoinLabel {

    const val BAD_BANK = "Bad Bank"
    private const val SUFFIX = " (Bad Bank)"

    /**
     * The text the user owns. A trailing "(Bad Bank)" is removed,
     * repeatedly, case-insensitively, with the spaces around it
     * optional. An exact "Bad Bank" is an empty user part: that is
     * what the auto-writer stores, and what the editor shows as blank.
     */
    fun userPart(stored: String?): String {
        if (stored == null) return ""
        var text = stored.trim()
        while (true) {
            val match = TRAILING_SUFFIX.find(text) ?: break
            text = text.substring(0, match.range.first).trim()
        }
        if (text.equals(BAD_BANK, ignoreCase = true)) return ""
        return text
    }

    /**
     * Text to show.
     *
     * Not Bad Bank: [stored] unchanged, including a Deposit coin
     * someone labelled "x (Bad Bank)" by hand.
     * Bad Bank with an empty user part: "Bad Bank".
     * Otherwise: "<user part> (Bad Bank)".
     *
     * Idempotent for a Bad Bank coin:
     * `display(display(x, true), true) == display(x, true)`.
     */
    fun display(stored: String?, isBadBank: Boolean): String? {
        if (!isBadBank) return stored
        val user = userPart(stored)
        return if (user.isEmpty()) BAD_BANK else user + SUFFIX
    }

    /** True when the row flag is set or the key is the Bad Bank account. */
    fun isMarked(badbank: Int, pubKey: String?): Boolean =
        badbank == 1 || AccountKey.isBadBank(pubKey)

    private val TRAILING_SUFFIX = Regex("(?i)\\s*\\(bad bank\\)\\s*$")
}
