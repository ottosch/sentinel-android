package com.samourai.sentinel.ui.home

import com.samourai.sentinel.core.ConnectionIndicator
import com.samourai.sentinel.core.DojoContact
import com.samourai.sentinel.core.SyncState
import com.samourai.sentinel.tor.EnumTorState

/**
 * Toolbar traffic light.
 *
 * Tor still comes first: off is red, and a climb or a round in
 * progress is yellow. The connection color after that is
 * [DojoContact], the same source as the Network Dojo row.
 * [DojoContact.Unreachable] is red, [DojoContact.DojoError] is yellow,
 * and [DojoContact.Reachable] is green. A sync failure does not by
 * itself paint the dot red: the banner keeps that result.
 */
internal fun homeConnectionIndicator(
    sync: SyncState,
    torRequired: Boolean,
    torState: EnumTorState,
    torProgress: Int,
    endpointEnabled: Boolean,
    reachability: DojoContact = DojoContact.Unknown,
): ConnectionIndicator = when {
    torRequired && torState == EnumTorState.OFF -> ConnectionIndicator.RED
    torRequired && torState == EnumTorState.STARTING -> ConnectionIndicator.YELLOW
    // ON lands at boot=5. GREEN means the climb finished.
    torRequired && torState == EnumTorState.ON && torProgress < 100 ->
        ConnectionIndicator.YELLOW
    sync is SyncState.WaitingForTor -> ConnectionIndicator.YELLOW
    sync is SyncState.Syncing -> ConnectionIndicator.YELLOW
    endpointEnabled && reachability is DojoContact.Unreachable -> ConnectionIndicator.RED
    endpointEnabled && reachability is DojoContact.DojoError -> ConnectionIndicator.YELLOW
    endpointEnabled && reachability is DojoContact.Reachable -> ConnectionIndicator.GREEN
    sync is SyncState.Success -> ConnectionIndicator.GREEN
    // Tor is up, but nothing is configured to sync against.
    torRequired && !endpointEnabled -> ConnectionIndicator.RED
    // No contact recorded. A transport banner is still red; any other
    // standing failure is yellow until a real contact lands.
    sync is SyncState.Failed && sync.reason == SyncRoundTerminal.DOJO_UNREACHABLE ->
        ConnectionIndicator.RED
    sync is SyncState.Failed -> ConnectionIndicator.YELLOW
    else -> ConnectionIndicator.NEUTRAL
}
