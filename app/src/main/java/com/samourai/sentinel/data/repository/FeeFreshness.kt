package com.samourai.sentinel.data.repository

/**
 * #77 option 4: how current the fee estimates behind the fee slider are.
 *
 * Without a Dojo (or with one that stopped answering) the estimates are a
 * cache, or the static defaults, indefinitely (#76), and nothing said so:
 * a slider topping out at 4 sat/vB looked as trustworthy as a live one on
 * a congested day. The fee selector now names the state, and points at
 * the custom rate when the estimates can't be trusted.
 *
 * Pure Kotlin.
 */
sealed class FeeFreshness {

    /** Fetched from the server within [STALE_AFTER_MS]. */
    object Fresh : FeeFreshness()

    /** Fetched from the server, [ageMs] ago. */
    data class Stale(val ageMs: Long) : FeeFreshness()

    /** Cached estimates from before fetch times were recorded. */
    object UnknownAge : FeeFreshness()

    /** No estimates at all: the static defaults are in use. */
    object Defaults : FeeFreshness()

    companion object {
        /** Mempool fee levels can move a lot within an hour. */
        const val STALE_AFTER_MS = 30L * 60L * 1000L

        /**
         * [estimatesLoaded]: tiers were ever accepted (live or from the
         * cache). [fetchedAtMs]: when the server last answered, or null
         * if unknown. A fetch time in the future (clock change) is Fresh.
         */
        fun of(estimatesLoaded: Boolean, fetchedAtMs: Long?, nowMs: Long): FeeFreshness {
            if (!estimatesLoaded) return Defaults
            if (fetchedAtMs == null) return UnknownAge
            val age = nowMs - fetchedAtMs
            return if (age <= STALE_AFTER_MS) Fresh else Stale(age)
        }

        /** "45 min", "3 h", "2 days" - the age as the note shows it. */
        fun describeAge(ageMs: Long): String {
            val minutes = ageMs / 60_000L
            val hours = minutes / 60L
            val days = hours / 24L
            return when {
                days >= 2 -> "$days days"
                days == 1L -> "1 day"
                hours >= 1 -> "$hours h"
                else -> "${minutes.coerceAtLeast(1)} min"
            }
        }
    }
}
