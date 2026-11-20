package com.samourai.sentinel.data.whirlpool

/**
 * Manual-provenance guard for the auto-writer (issue #6, part 2).
 * Pure JVM — no Android, Koin or Room dependencies.
 *
 * Truth table, on an EXISTING label row:
 *  - no row at all         -> Write    (fresh: auto may label)
 *  - origin starts auto:   -> Write    (our namespace, safe to refresh)
 *  - origin null           -> HandsOff (manual user label, or a hand clear)
 *  - origin foreign        -> HandsOff (BIP-329 / Sparrow import)
 *  - origin empty string   -> HandsOff (never widen our namespace to it)
 *
 * The null ambiguity is load-bearing: a missing row and a row with a
 * null origin are DIFFERENT facts. Callers must distinguish them —
 * see WhirlpoolAutoWriter.Sink. A hand clear keeps the row (blank
 * label, null origin) so the next sync still sees HandsOff and does
 * not write the auto label back.
 */
object WhirlpoolLabelPolicy {

    const val AUTO_NAMESPACE = "auto:"

    sealed interface Decision {
        object Write : Decision
        object HandsOff : Decision
    }

    fun decide(existing: ExistingLabel?): Decision = when {
        existing == null -> Decision.Write
        existing.origin?.startsWith(AUTO_NAMESPACE) == true -> Decision.Write
        else -> Decision.HandsOff
    }
}

/**
 * A pre-existing label row, as the writer sees it.
 * [badbank] is the sticky coin flag. Tx rows leave it at 0.
 */
data class ExistingLabel(
    val label: String,
    val origin: String?,
    val badbank: Int = 0,
)

/**
 * What a hand edit or a hand clear stores. [origin] is null: the
 * manual origin. An auto origin on the previous row is not kept.
 * A blank [label] is stored, not deleted, so auto-labelling still
 * sees a row and does not write one back.
 */
data class ManualLabelWrite(val label: String, val origin: String?)

fun manualLabelWrite(label: String): ManualLabelWrite =
    ManualLabelWrite(label = label.trim(), origin = null)
