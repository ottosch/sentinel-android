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
        val text = stored.trim()
        // Linear scan from the end. A regex find() here is quadratic on
        // long whitespace runs and on repeated suffixes (ICU and JVM),
        // and labels come from BIP-329 files of up to 32 MiB.
        var end = text.length
        while (true) {
            while (end > 0 && text[end - 1].isWhitespace()) end--
            if (!endsWithMark(text, end)) break
            end -= MARK.length
        }
        val user = text.substring(0, end)
        if (user.equals(BAD_BANK, ignoreCase = true)) return ""
        return user
    }

    /** ASCII-only case-insensitive "(bad bank)" ending at [end], like the old (?i) regex. */
    private fun endsWithMark(text: String, end: Int): Boolean {
        val start = end - MARK.length
        if (start < 0) return false
        for (i in MARK.indices) {
            var c = text[start + i]
            if (c in 'A'..'Z') c = (c.code + 32).toChar()
            if (c != MARK[i]) return false
        }
        return true
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

    private const val MARK = "(bad bank)"
}
