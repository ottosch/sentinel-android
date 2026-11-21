package com.samourai.sentinel.ui.home

import com.samourai.sentinel.core.SyncState

/**
 * Decides what an auto sync round does with the current collections list.
 *
 * Possible values:
 * - [Full]: every collection should be fetched.
 * - [Skip]: nothing to fetch, collections were deleted or reordered.
 * - [Delta]: a partial sync; contains the ids that need syncing.
 *
 * [Delta] requires a standing clean Success.
 * If we are in any different state, [Full] sync is required.
 */
internal sealed class SyncRoundPlan {
    object Full : SyncRoundPlan()
    object Skip : SyncRoundPlan()
    data class Delta(val ids: Set<String>) : SyncRoundPlan()

    companion object {
        fun decide(
            lastSignature: String?,
            currentSignature: String,
            stateIsSuccess: Boolean,
        ): SyncRoundPlan {
            val changedIds = SyncRoundDiff.changedCollectionIds(lastSignature, currentSignature)
            return when {
                changedIds == null -> Full
                changedIds.isEmpty() -> Skip
                !stateIsSuccess -> Full
                else -> Delta(changedIds)
            }
        }
    }
}

/**
 * Effect of a [SyncRoundPlan.Skip] at the arm site. Both signatures
 * are stamped. The sync state is left as it is, except a standing
 * WaitingForTor becomes Idle, because nothing else will replace that
 * banner. A skip never posts Success.
 */
internal data class DiffEmptySkip(
    val signature: String,
    val postedState: SyncState?,
)

internal fun diffEmptySkip(signature: String, state: SyncState?): DiffEmptySkip =
    DiffEmptySkip(
        signature = signature,
        postedState = if (state is SyncState.WaitingForTor) SyncState.Idle else null,
    )
