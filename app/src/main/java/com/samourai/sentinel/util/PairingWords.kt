package com.samourai.sentinel.util

import java.util.Locale
import java.util.TreeSet

/**
 * #282: checks and autocompletes the one-time pairing password.
 *
 * Ashigaru (and Samourai before it, PairingMenu.getRadomWords) builds that
 * password from three words of the EFF large wordlist joined with "-". The
 * same list ships here as [ASSET_PATH]; this class only reads it.
 *
 * Four list words contain a hyphen themselves (drop-down, felt-tip,
 * t-shirt, yo-yo), so the text is never split on every "-". It is matched
 * against the list instead. That is unambiguous because the list is
 * prefix-free: no word is the start of another word, so at any position
 * at most one word can match.
 *
 * #431: the "-" between words is entry sugar, not meaning. A list word
 * matches wherever it starts, so the three words parse just as well with
 * no separators at all ("word1word2word3") or in any mix of "-" and
 * spaces, typed or pasted. [canonical] re-joins a complete parse with
 * "-" for decryption. A hyphenated list word typed without its own "-"
 * ("tshirt" for "t-shirt") is not repaired; that stays an error.
 *
 * Pure JVM, no Android imports. The password text is never logged.
 */
class PairingWords(words: Collection<String>) {

    private val words: TreeSet<String> =
        TreeSet(words.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() })

    /** Bounds of the [matchAt] scan; an empty list matches nothing. */
    private val minWordLen: Int = words.minOfOrNull { it.length } ?: 1
    private val maxWordLen: Int = words.maxOfOrNull { it.length } ?: 0

    val size: Int get() = words.size

    fun contains(word: String): Boolean = words.contains(word)

    /** True if some list word starts with [prefix]. */
    fun hasPrefix(prefix: String): Boolean {
        val next = words.ceiling(prefix) ?: return false
        return next.startsWith(prefix)
    }

    /**
     * What the user has typed so far.
     *
     * @property words the complete list words, in order.
     * @property invalid the first token that is neither a list word nor
     *  the start of one, or null.
     * @property fragment the trailing part that is the start of a list
     *  word but not yet a whole one (what suggestions complete), or "".
     * @property tooMany more than [WORD_COUNT] words.
     */
    data class Analysis(
        val words: List<String>,
        val invalid: String?,
        val fragment: String,
        val tooMany: Boolean
    ) {
        /** Exactly three list words and nothing else: Decrypt may run. */
        val isComplete: Boolean
            get() = invalid == null && fragment.isEmpty() && !tooMany && words.size == WORD_COUNT
    }

    fun analyze(text: String): Analysis {
        val s = normalizeLive(text)
        val found = mutableListOf<String>()
        var invalid: String? = null
        var fragment = ""
        var pos = 0
        while (pos < s.length) {
            val match = matchAt(s, pos)
            if (match != null) {
                found.add(match)
                pos += match.length
                // The separator after the word, if the user typed one (#431).
                if (pos < s.length && s[pos] == '-') pos++
                continue
            }
            val rest = s.substring(pos)
            if (hasPrefix(rest)) {
                // Still being typed; may itself contain a "-" ("t-sh").
                fragment = rest
                break
            }
            val dash = s.indexOf('-', pos)
            val end = if (dash < 0) s.length else dash
            if (invalid == null) invalid = s.substring(pos, end)
            pos = if (dash < 0) s.length else dash + 1
        }
        return Analysis(found, invalid, fragment, found.size > WORD_COUNT)
    }

    /**
     * The list word starting at [pos] in [s], or null. A word may be
     * followed by a "-", by the next word directly, or by nothing (#431);
     * it only ever brings its own inner "-" with it (drop-down,
     * t-shirt...), so "tshirt" does not match "t-shirt". At most one list
     * word can start at any position: two of them would make one a prefix
     * of the other, and the list is prefix-free.
     */
    private fun matchAt(s: String, pos: Int): String? {
        val limit = minOf(maxWordLen, s.length - pos)
        for (len in minWordLen..limit) {
            val candidate = s.substring(pos, pos + len)
            if (words.contains(candidate)) return candidate
        }
        return null
    }

    /** Up to [limit] list words starting with the trailing fragment of [text]. */
    fun suggestions(text: String, limit: Int = 4): List<String> {
        val analysis = analyze(text)
        if (analysis.fragment.isEmpty() || analysis.words.size >= WORD_COUNT) return emptyList()
        return words.tailSet(analysis.fragment, true)
            .asSequence()
            .takeWhile { it.startsWith(analysis.fragment) }
            .take(limit)
            .toList()
    }

    /**
     * [text] with its trailing fragment replaced by [word], followed by a
     * "-" unless that was the last word. Returns [text] unchanged if [word]
     * is not on the list.
     */
    fun complete(text: String, word: String): String {
        if (!words.contains(word)) return text
        val live = normalizeLive(text)
        val fragment = analyze(text).fragment
        val base = live.removeSuffix(fragment)
        val withWord = base + word
        val count = analyze(withWord).words.size
        return if (count < WORD_COUNT) "$withWord-" else withWord
    }

    /**
     * Adds the "-" as soon as the last typed word is a whole list word and
     * more words are due. Safe because the list is prefix-free: an exact
     * match cannot be the start of a longer word. Callers apply it only
     * when the user has just typed, never on a deletion.
     */
    fun autoSeparate(text: String): String {
        val live = normalizeLive(text)
        if (live.isEmpty() || live.endsWith("-")) return live
        val a = analyze(live)
        return if (a.invalid == null && a.fragment.isEmpty() && a.words.size in 1 until WORD_COUNT) {
            "$live-"
        } else {
            live
        }
    }

    /**
     * #431: the dash-joined form decryption uses, for text whose three
     * words arrive in any mix of "-", spaces or nothing: a complete parse
     * is re-joined with "-". Any other text falls back to [normalize] -
     * if the words do not parse, their boundaries are not known, so only
     * the whitespace/case shaping applies.
     */
    fun canonical(raw: String): String {
        val analysis = analyze(raw)
        return if (analysis.isComplete) analysis.words.joinToString("-") else normalize(raw)
    }

    companion object {
        const val WORD_COUNT = 3
        const val ASSET_PATH = "LargeWordsList/eff_large_wordlist.txt"

        private val WHITESPACE = Regex("\\s+")
        private val DASHES = Regex("-{2,}")

        /** Reads the EFF file ("11111\tabacus" per line); bare words also work. */
        fun parseList(lines: Sequence<String>): List<String> =
            lines.map { it.substringAfter('\t').trim() }
                .filter { it.isNotEmpty() }
                .toList()

        /**
         * The form decryption uses: trimmed, lowercased, every run of
         * whitespace turned into "-", repeated "-" collapsed, and no "-" at
         * either end. "Apple  Pie\tT-SHIRT " becomes "apple-pie-t-shirt".
         */
        fun normalize(raw: String): String = normalizeLive(raw).trim('-')

        /**
         * As [normalize] but keeps a trailing "-": a trailing space or "-"
         * is the separator the user just typed.
         */
        internal fun normalizeLive(raw: String): String =
            raw.trimStart()
                .lowercase(Locale.ROOT)
                .replace(WHITESPACE, "-")
                .replace(DASHES, "-")
                .trimStart('-')
    }
}
