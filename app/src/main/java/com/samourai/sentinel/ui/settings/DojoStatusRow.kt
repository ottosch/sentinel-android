package com.samourai.sentinel.ui.settings

import com.samourai.sentinel.core.DojoContact

internal enum class DojoRowTone {
    GREEN,
    AMBER,
    RED,
}

internal data class DojoRowPresentation(
    val text: String,
    val tone: DojoRowTone,
)

/**
 * Network screen Dojo row, from [DojoContact] rather than from pairing.
 *
 * Green only after a recent successful contact. Unknown is amber, and
 * the screen may probe while it is open. Tor not ready never paints
 * green: the check is Tor-only and fail-closed.
 */
internal object DojoStatusRow {
    const val CONNECTED = "Connected"
    const val CHECKING = "Paired, checking\u2026"
    const val NOT_CHECKED = "Paired, not checked yet"
    const val DOJO_ERROR = "Dojo returned an error"
    const val UNREACHABLE = "Could not reach Dojo"

    /** A Reachable contact older than this is shown as checking, not green. */
    const val RECENT_MS = 120_000L

    fun present(
        contact: DojoContact,
        now: Long,
        torReady: Boolean,
        checking: Boolean,
    ): DojoRowPresentation {
        if (!torReady) {
            return DojoRowPresentation(NOT_CHECKED, DojoRowTone.AMBER)
        }
        return when (contact) {
            // Tor is up and nothing has been recorded yet. Amber until
            // a check is running, then the checking line.
            is DojoContact.Unknown -> DojoRowPresentation(
                if (checking) CHECKING else NOT_CHECKED,
                DojoRowTone.AMBER,
            )
            is DojoContact.Reachable -> {
                if (now - contact.atMillis in 0..RECENT_MS) {
                    DojoRowPresentation(CONNECTED, DojoRowTone.GREEN)
                } else {
                    DojoRowPresentation(CHECKING, DojoRowTone.AMBER)
                }
            }
            is DojoContact.DojoError -> DojoRowPresentation(DOJO_ERROR, DojoRowTone.AMBER)
            is DojoContact.Unreachable -> DojoRowPresentation(UNREACHABLE, DojoRowTone.RED)
        }
    }

    /**
     * A lightweight check while the row is open. Not when Tor is down,
     * and not while a definitive error is already showing.
     */
    fun shouldProbe(contact: DojoContact, now: Long, torReady: Boolean): Boolean {
        if (!torReady) return false
        return when (contact) {
            is DojoContact.Unknown -> true
            is DojoContact.Reachable -> now - contact.atMillis !in 0..RECENT_MS
            is DojoContact.DojoError, is DojoContact.Unreachable -> false
        }
    }
}
