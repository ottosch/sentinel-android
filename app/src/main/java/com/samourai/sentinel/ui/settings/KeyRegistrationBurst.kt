package com.samourai.sentinel.ui.settings

import java.util.concurrent.CopyOnWriteArrayList

/**
 * #409: what the holder announces, and when. Progress fires per item
 * (attempts counted - the #158 option B vocabulary); Finished fires once
 * per completed round with its tally. The empty-wallet silent reset
 * (#98's silence leg) publishes nothing by design.
 */
sealed interface BurstEvent {
    /** A round is running; done counts attempts, failures included. */
    data class Progress(val done: Int, val total: Int) : BurstEvent

    /** A round completed; every round reports its own delta (#158 leg 2). */
    data class Finished(val registered: Int, val failed: Int, val total: Int) : BurstEvent
}

/**
 * #158: burst state + vocabulary for the dojo derivation-path
 * registration burst (NetworkActivity.importAllXpubs).
 *
 * The holder outlives the screen: the burst runs on apiScope and survives
 * the activity (#85 containment semantics, untouched), so a re-entered
 * NetworkActivity renders from here instead of a dead activity's last view
 * write.
 *
 * #409: it also SPEAKS now. Progress used to reach the row only through
 * the launching activity instance's view writes; once that instance was
 * destroyed behind the pairing sheet, its destroy-guarded writes no-op'd
 * and a re-created screen showed a frozen counter until its next onResume
 * re-snapshot. Ticks and completions now publish from here to whoever is
 * subscribed (subscribe/unsubscribe) - the subscriber owns all
 * main-thread and view discipline, so this stays pure Kotlin.
 *
 * Single-flight (#158 leg 3 / #159): one live burst at a time. The pairing
 * sheet can fire its onDismiss listener more than once per pairing; a
 * second launch while one is live is skipped, never interleaved onto the
 * same row.
 *
 * Every round reports (#158 leg 2 + the maintainer's every-time condition):
 * completions land in a finally, and the gate re-arms on completion - each
 * registration round, retries included, states its own delta so "did it
 * finish, was a derivation path left behind?" is never left open. A
 * partial round's residue stays on the row until a full pass or a newer
 * round displaces it.
 *
 * Pure Kotlin - no Android imports: the unit seam for the vocabulary
 * (#158 leg 1's test).
 */
object KeyRegistrationBurst {

    @Volatile
    var inFlight: Boolean = false
        private set

    @Volatile
    var done: Int = 0
        private set

    @Volatile
    var total: Int = 0
        private set

    @Volatile
    private var registered: Int = 0

    @Volatile
    private var failed: Int = 0

    @Volatile
    private var hasResidue: Boolean = false

    // #409: the observer seam. CopyOnWrite because ticks arrive from the
    // apiScope worker while subscribe/unsubscribe arrive from the main
    // thread.
    private val listeners = CopyOnWriteArrayList<(BurstEvent) -> Unit>()

    /**
     * #409: subscribe for live ticks. A mid-burst subscriber immediately
     * receives the current progress as its first event, so a screen that
     * appears mid-burst renders the real count at once - no blank row
     * waiting for the first new tick.
     */
    @Synchronized
    fun subscribe(listener: (BurstEvent) -> Unit) {
        listeners.add(listener)
        if (inFlight && total > 0) {
            listener(BurstEvent.Progress(done, total))
        }
    }

    /** #409: detach. The burst itself keeps running - only delivery stops. */
    fun unsubscribe(listener: (BurstEvent) -> Unit) {
        listeners.remove(listener)
    }

    private fun publish(event: BurstEvent) {
        listeners.forEach { it(event) }
    }

    /**
     * Single-flight gate. True = the caller owns the burst and MUST run it
     * to a completion call (complete/markFinished); false = a burst is
     * already live, skip the launch entirely.
     */
    @Synchronized
    fun markStarted(): Boolean {
        if (inFlight) {
            return false
        }
        inFlight = true
        return true
    }

    /** Items known; zeroes the counters for the round about to run. */
    fun begin(totalCount: Int) {
        total = totalCount
        done = 0
        registered = 0
        failed = 0
        publish(BurstEvent.Progress(0, totalCount))
    }

    /** The attempt counter advances before each item's call (option B). */
    fun onItemStarted(attemptsDone: Int) {
        done = attemptsDone
        publish(BurstEvent.Progress(done, total))
    }

    /**
     * Round finished: latch the outcome - partial rounds keep their
     * residue, full passes clear it - and re-arm the gate so the NEXT
     * retry round can run and report again. Every round fires; none is
     * swallowed. #409: the tally also publishes, so the completion report
     * lands on whichever screen is live, not only the launcher.
     */
    @Synchronized
    fun complete(registeredCount: Int, failedCount: Int, totalCount: Int) {
        registered = registeredCount
        failed = failedCount
        total = totalCount
        hasResidue = failedCount > 0 || registeredCount < totalCount
        inFlight = false
        publish(BurstEvent.Finished(registeredCount, failedCount, totalCount))
    }

    /** Empty-wallet launch: silent reset, no residue (the #98 silence leg). */
    @Synchronized
    fun markFinished() {
        inFlight = false
        done = 0
        total = 0
        registered = 0
        failed = 0
        hasResidue = false
    }

    /**
     * The row's text from burst state: in-flight renders the re-entry
     * marker, a partial last round renders its residue, otherwise the
     * caller's rest-state label.
     */
    fun statusText(restLabel: String): String = when {
        inFlight -> progressText(done, total)
        hasResidue -> residueText(registered, total)
        else -> restLabel
    }

    /** Attempt-counted progress - failures advance it too (option B). */
    fun progressText(attemptsDone: Int, totalCount: Int): String =
        "Registering derivation paths with Dojo\u2026 $attemptsDone of $totalCount"

    /**
     * Completion vocabulary, delta stated out loud (option B): the partial
     * note names the failed count when there is one; the erase-skip shape
     * (registered < total, failed == 0) gets the retry note without a
     * "0 failed" wart.
     */
    fun resultText(registeredCount: Int, failedCount: Int, totalCount: Int): String {
        val partial = failedCount > 0 || registeredCount < totalCount
        return if (partial) {
            val failedNote = if (failedCount > 0) ", $failedCount failed" else ""
            "Dojo connected ($registeredCount of $totalCount derivation paths " +
                "registered$failedNote - retry pairing to re-register)"
        } else {
            "Dojo connected ($registeredCount derivation paths registered)"
        }
    }

    fun residueText(registeredCount: Int, totalCount: Int): String =
        "Enabled ($registeredCount of $totalCount registered)"
}
