package com.samourai.sentinel.ui.dojo

/**
 * The directory display snapshot (name, flag, jurisdiction, URL, network)
 * of the Community Dojos listing the user picked, held by the pairing
 * sheet until the pairing commits. Only then is it saved to prefs, so
 * DON'T PAIR, a failed pairing, or closing the sheet leaves the saved
 * snapshot as it was. A payload from a scan or paste carries none.
 */
class PendingDisplaySnapshot {
    private var pending: DojoDisplaySnapshot? = null

    /** A new payload arrived: [snapshot] for a directory listing, null for a scan or paste. */
    fun select(snapshot: DojoDisplaySnapshot?) {
        pending = snapshot
    }

    /** What the pre-pair dialog reads: the picked listing's snapshot, else the saved one. */
    fun forDialog(saved: () -> DojoDisplaySnapshot): DojoDisplaySnapshot = pending ?: saved()

    /** The pairing committed: the snapshot to save (null: keep what is saved), handed out once. */
    fun takeForCommit(): DojoDisplaySnapshot? = pending.also { pending = null }

    /** Declined or abandoned: nothing is saved. */
    fun discard() {
        pending = null
    }
}
