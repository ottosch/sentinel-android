package com.samourai.sentinel.ui.home

import com.samourai.sentinel.core.SyncState
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.repository.CollectionTxSyncResult
import com.samourai.sentinel.data.repository.SyncFailureKind
import com.samourai.sentinel.data.repository.syncFailureKind
import com.samourai.sentinel.ui.adapters.CollectionRowNetworkState
import com.samourai.sentinel.ui.adapters.collectionRowNetworkState

/**
 * Collections a sync round may attempt.
 *
 * Off-network is the #429 grey row, both badge variants, and the
 * match is the active network against the collection. It runs in
 * both directions: testnet keys stay out of a mainnet round, and
 * mainnet keys stay out of a testnet round. Nothing here names a
 * prefix or a single network. No verdict (empty, mixed, or
 * all-unknown) stays in the round — never guess.
 */
internal fun collectionsForSyncRound(
    collections: List<PubKeyCollection>,
    activeIsTestnet: Boolean,
    dojoEnabled: Boolean,
): List<PubKeyCollection> =
    collections.filter { collection ->
        collectionRowNetworkState(
            collection,
            activeIsTestnet = activeIsTestnet,
            dojoEnabled = dojoEnabled,
        ) is CollectionRowNetworkState.Normal
    }

/**
 * #499: one terminal discipline for both round kinds.
 *
 * Zero failures may produce Success. Some-but-not-all failures produce
 * the partial-Failed state and clear [Decision.clearLastRoundSignature]
 * (the external-arm signature). Total failure keeps each round's existing
 * ending: syncCollections fails immediately and clears that signature;
 * fetchBalance keeps its bounded automatic retry and leaves the signature
 * stamped so a parallel arm does not join the retry. Neither ending
 * clears the round-start signature the #490 trailing re-run compares.
 */
internal object SyncRoundTerminal {

    const val DOJO_UNREACHABLE = "Could not reach Dojo."

    fun partialReason(failed: Int, total: Int): String =
        "$failed of $total collections failed to sync."

    fun dojoErrorReason(failed: Int, total: Int): String =
        "Dojo returned an error for $failed of $total collections."

    fun syncIncompleteReason(failed: Int, total: Int): String =
        "Sync incomplete for $failed of $total collections."

    /**
     * Transport total failure is the only "Could not reach Dojo."
     * Dojo answers name themselves. Local misses say the sync is
     * incomplete. A mix keeps the partial N-of-M sentence.
     */
    fun failureReason(failed: Int, total: Int, tally: SyncFailureTally): String {
        val counted = if (tally.failed == 0 && failed > 0) {
            SyncFailureTally(local = failed)
        } else {
            tally
        }
        val everyTransport = counted.transport == failed &&
            counted.dojo == 0 &&
            counted.local == 0 &&
            failed == total &&
            total > 0
        if (everyTransport) return DOJO_UNREACHABLE
        if (counted.dojo > 0 && counted.transport == 0 && counted.local == 0) {
            return dojoErrorReason(counted.dojo, total)
        }
        if (counted.local == failed && counted.transport == 0 && counted.dojo == 0) {
            return syncIncompleteReason(counted.local, total)
        }
        return partialReason(failed, total)
    }

    enum class Kind {
        /** launchSyncRound -> syncCollections. No auto-retry, no lastSynced write. */
        COLLECTIONS,

        /** fetchBalance. Bounded auto-retry on total failure; lastSynced on clean Success. */
        BALANCE,
    }

    sealed interface Post {
        data object Success : Post
        data object Idle : Post
        data class Failed(val reason: String) : Post
    }

    /**
     * @param post null when nothing new is posted (auto-retry stays on
     *   Syncing, or a standing Failed is left alone).
     * @param clearLastRoundSignature external arms may retry. Never the
     *   round-start signature.
     * @param writeLastSynced fetchBalance's clean Success only.
     * @param staleSuccessRetired null leaves the re-pair flag alone.
     */
    data class Decision(
        val post: Post?,
        val clearLastRoundSignature: Boolean,
        val writeLastSynced: Boolean,
        val armAutoRetry: Boolean,
        val autoRetryBudget: Int,
        val staleSuccessRetired: Boolean?,
    ) {
        fun toSyncState(now: Long): SyncState? {
            val terminal = post
            return when (terminal) {
                null -> null
                Post.Success -> SyncState.Success(now)
                Post.Idle -> SyncState.Idle
                is Post.Failed -> SyncState.Failed(terminal.reason, retryable = true)
            }
        }
    }

    fun decide(
        kind: Kind,
        failed: Int,
        total: Int,
        endpointEnabled: Boolean,
        userInitiated: Boolean,
        autoRetryBudget: Int,
        autoRetryLimit: Int,
        standingFailed: Boolean,
        tally: SyncFailureTally = SyncFailureTally(),
    ): Decision {
        val reason = failureReason(failed, total, tally)
        val allTransport = tally.transport == failed &&
            tally.dojo == 0 &&
            tally.local == 0 &&
            failed > 0
        // fetchBalance total failure: bounded retry, then the cause.
        // A dead Dojo (every failure is transport) does not spend the
        // retries: each one would wait out another connect. The
        // signature stays stamped (#47 coalescing during the retry
        // window) for the failures that still retry.
        if (kind == Kind.BALANCE && total > 0 && failed == total) {
            if (!allTransport && !userInitiated && autoRetryBudget < autoRetryLimit) {
                return Decision(
                    post = null,
                    clearLastRoundSignature = false,
                    writeLastSynced = false,
                    armAutoRetry = true,
                    autoRetryBudget = autoRetryBudget + 1,
                    staleSuccessRetired = null,
                )
            }
            return Decision(
                post = Post.Failed(reason),
                clearLastRoundSignature = false,
                writeLastSynced = false,
                armAutoRetry = false,
                autoRetryBudget = autoRetryBudget,
                staleSuccessRetired = null,
            )
        }

        if (failed > 0) {
            return Decision(
                post = Post.Failed(reason),
                clearLastRoundSignature = true,
                writeLastSynced = false,
                armAutoRetry = false,
                autoRetryBudget = autoRetryBudget,
                staleSuccessRetired = null,
            )
        }

        if (!endpointEnabled) {
            // #188: a clean round whose pairing vanished. syncCollections
            // always posts Idle. fetchBalance posts Idle only when a
            // user-earned Failed is not already standing, and resets the
            // retry budget the way its success path does.
            val retire = !standingFailed
            val postIdle = kind == Kind.COLLECTIONS || retire
            return Decision(
                post = if (postIdle) Post.Idle else null,
                clearLastRoundSignature = false,
                writeLastSynced = false,
                armAutoRetry = false,
                autoRetryBudget = if (kind == Kind.BALANCE) 0 else autoRetryBudget,
                staleSuccessRetired = retire,
            )
        }

        return Decision(
            post = Post.Success,
            clearLastRoundSignature = false,
            writeLastSynced = kind == Kind.BALANCE,
            armAutoRetry = false,
            autoRetryBudget = if (kind == Kind.BALANCE) 0 else autoRetryBudget,
            staleSuccessRetired = null,
        )
    }
}

/**
 * #475/#499: per-collection failure count shared by both rounds.
 * Indeterminate, a thrown fetch, and a completion-gate miss each
 * count once. Proven-empty is not a failure, even when the tx table
 * has no row for it.
 */
internal data class SyncFailureTally(
    val transport: Int = 0,
    val dojo: Int = 0,
    val local: Int = 0,
) {
    val failed: Int get() = transport + dojo + local
}

internal class SyncRoundLedger {
    var failed: Int = 0
        private set

    private val failedIds = mutableSetOf<String>()
    private val kinds = LinkedHashMap<String, SyncFailureKind>()
    private val provenEmptyIds = mutableSetOf<String>()

    fun record(id: String, result: CollectionTxSyncResult) {
        when (result) {
            is CollectionTxSyncResult.Fetched -> Unit
            CollectionTxSyncResult.ProvenEmpty -> provenEmptyIds.add(id)
            is CollectionTxSyncResult.Indeterminate -> countFailure(id, result.kind)
        }
    }

    /** A thrown fetch. No throwable means transport: the call never returned. */
    fun recordThrown(id: String, error: Throwable? = null) {
        val kind = if (error == null) SyncFailureKind.TRANSPORT else syncFailureKind(error)
        countFailure(id, kind)
    }

    fun recordKind(id: String, kind: SyncFailureKind) {
        countFailure(id, kind)
    }

    fun tally(): SyncFailureTally {
        var transport = 0
        var dojo = 0
        var local = 0
        kinds.values.forEach { kind ->
            when (kind) {
                SyncFailureKind.TRANSPORT -> transport++
                SyncFailureKind.DOJO -> dojo++
                SyncFailureKind.LOCAL -> local++
            }
        }
        return SyncFailureTally(transport, dojo, local)
    }

    /**
     * Collections that neither failed, nor were proven empty, nor have
     * tx rows. [txCounts] is the table count per id; a missing id counts
     * as no rows. Returns the new misses so the caller can log them.
     */
    fun applyGate(ids: List<String>, txCounts: Map<String, Int>): List<String> {
        val misses = ArrayList<String>()
        ids.forEach { id ->
            if (id !in failedIds &&
                id !in provenEmptyIds &&
                (txCounts[id] ?: 0) == 0
            ) {
                countFailure(id, SyncFailureKind.LOCAL)
                misses.add(id)
            }
        }
        return misses
    }

    private fun countFailure(id: String, kind: SyncFailureKind) {
        if (failedIds.add(id)) {
            failed++
            kinds[id] = kind
        }
    }
}
