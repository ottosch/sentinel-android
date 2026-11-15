package com.samourai.sentinel.ui.home

import com.samourai.sentinel.core.DojoContact
import com.samourai.sentinel.core.SyncState

/**
 * One quiet round for a standing "Could not reach Dojo." banner.
 *
 * Home resume and a flip to Reachable both ask this. A round already
 * in flight is refused, so the round that just failed cannot start
 * the next one. The caller must not invoke this from the failure
 * post itself.
 */
internal object QuietResync {
    fun shouldStart(
        sync: SyncState?,
        torReady: Boolean,
        roundInFlight: Boolean,
    ): Boolean {
        if (roundInFlight || !torReady) return false
        val failed = sync as? SyncState.Failed ?: return false
        return failed.reason == SyncRoundTerminal.DOJO_UNREACHABLE
    }
}

/** A real contact that was not already a real contact. A repeat is not a flip. */
internal fun becameReachable(previous: DojoContact, next: DojoContact): Boolean =
    next is DojoContact.Reachable && previous !is DojoContact.Reachable
