package com.samourai.sentinel.ui.home

import com.samourai.sentinel.data.PubKeyCollection

/**
 * #489: the trailing re-run decision for a finished sync round,
 * extracted from HomeViewModel so it is unit-testable without the
 * Koin graph.
 *
 * signatureOf is collection ids + pubkeys ONLY. The round's own
 * writes - per-pub balance, lastRefreshed, account index - never
 * change it, so the emission storm a round produces (~20
 * collectionsLiveData emissions per round, every one arming the
 * re-run) stops counting as change, while a genuine mid-round add,
 * remove or replace-on-import always does (#47).
 *
 * shouldRerun fails OPEN on a null baseline: fresh boot, or a round
 * that deferred before stamping (Tor climb, payload not yet loaded) -
 * in those states the trailing re-run is the only arm left holding a
 * pending list change, and dropping it would strand the change until
 * the next external arm.
 */
internal object SyncRerunPolicy {

    /** Content signature: collection ids + pubkeys, in list order. */
    fun signatureOf(list: List<PubKeyCollection>): String =
        list.joinToString("|") { c ->
            c.id + ":" + c.pubs.joinToString(",") { it.pubKey }
        }

    /**
     * True when the current list differs from the round's start
     * baseline - or when no baseline was stamped (fail open).
     */
    fun shouldRerun(baseline: String?, current: List<PubKeyCollection>): Boolean =
        baseline == null || signatureOf(current) != baseline

    /**
     * A collectionsLiveData emission is the round's own write when a
     * finished round has cleared the external-arm signature and the
     * list still matches the round-start signature.
     *
     * Balance and lastRefreshed are not part of [signatureOf], so
     * those writes used to pass launchSyncRound's null check and
     * start the next round on the main thread (device leg
     * 2026-11-10: syncCollections, both on-network collections
     * indeterminate, a new round ~15-30ms after "list unchanged").
     * The trailing re-run already ignores this list. Tor re-ready
     * and pull-to-refresh do not come through here.
     *
     * [blockOwnUnchanged] is set only when a round clears the
     * external-arm signature. A Tor deferral leaves it clear so the
     * next emission can still recover the round.
     */
    fun isOwnListEmission(
        blockOwnUnchanged: Boolean,
        roundStartSignature: String?,
        collections: List<PubKeyCollection>,
    ): Boolean = blockOwnUnchanged && !shouldRerun(roundStartSignature, collections)
}

/**
 * Which arm consults [SyncRerunPolicy.isOwnListEmission]. Only a
 * collections-list emission does. Tor re-ready and a user refresh
 * start a round without that gate.
 */
internal enum class SyncArm { LIST_EMISSION, TOR_READY, USER_REFRESH }

internal fun blocksAsOwnEmission(
    arm: SyncArm,
    blockOwnUnchanged: Boolean,
    roundStartSignature: String?,
    collections: List<PubKeyCollection>,
): Boolean = when (arm) {
    SyncArm.LIST_EMISSION -> SyncRerunPolicy.isOwnListEmission(
        blockOwnUnchanged,
        roundStartSignature,
        collections,
    )
    SyncArm.TOR_READY, SyncArm.USER_REFRESH -> false
}

/** A user pull-to-refresh or Retry replaces any in-flight round. */
internal fun userRefreshReplacesRound(): Boolean = true

/**
 * The trailing re-run compares the list to the round-start signature,
 * never to the external-arm signature a failure clears.
 */
internal fun trailingRerunWanted(
    roundStartSignature: String?,
    current: List<PubKeyCollection>,
): Boolean = SyncRerunPolicy.shouldRerun(roundStartSignature, current)

/**
 * How a round updates the own-emission block. Admit and a Tor deferral
 * leave the next list emission free to start a round. Clearing the
 * external-arm signature blocks that emission. The round-start
 * signature is never cleared here: the trailing re-run still needs it.
 */
internal enum class OwnEmissionStamp { ADMIT, TOR_DEFER, CLEAR }

internal data class OwnEmissionFlags(
    val blockOwnListEmission: Boolean,
    val clearRoundStartSignature: Boolean,
)

internal fun ownEmissionFlags(stamp: OwnEmissionStamp): OwnEmissionFlags = when (stamp) {
    OwnEmissionStamp.ADMIT, OwnEmissionStamp.TOR_DEFER ->
        OwnEmissionFlags(blockOwnListEmission = false, clearRoundStartSignature = false)
    OwnEmissionStamp.CLEAR ->
        OwnEmissionFlags(blockOwnListEmission = true, clearRoundStartSignature = false)
}
