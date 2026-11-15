package com.samourai.sentinel.ui.home

import com.samourai.sentinel.core.SyncState
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.repository.CollectionTxSyncResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #499: both round kinds share one terminal discipline.
 *
 * Zero failures may produce Success. Some-but-not-all failures produce
 * the partial-Failed state. Total failure keeps the bounded automatic
 * retry that only fetchBalance has. Any partial failure clears the
 * external-arm signature and must not open a trailing-sync loop: the
 * trailing re-run still compares the round's start signature (#490).
 */
class SyncRoundTerminalTest {

    private val previousSync = 50L
    private val now = 1_700_000_000_000L
    private val retryLimit = 2

    @Test
    fun syncCollections_zeroFailures_postsSuccessWithoutWritingLastSynced() {
        val list = wallets()
        val effect = finish(
            kind = SyncRoundTerminal.Kind.COLLECTIONS,
            results = listOf(fetched("a"), fetched("b")),
            autoRetryBudget = 2,
        )
        assertEquals(SyncState.Success(now), effect.state)
        assertEquals(previousSync, effect.lastSynced)
        assertEquals(SyncRerunPolicy.signatureOf(list), effect.signature)
        assertFalse(effect.armAutoRetry)
        assertEquals(2, effect.budget)
        assertNoTrailingLoop(list)
    }

    @Test
    fun fetchBalance_zeroFailures_postsSuccessAndWritesLastSynced() {
        val list = wallets()
        val effect = finish(
            kind = SyncRoundTerminal.Kind.BALANCE,
            results = listOf(fetched("a"), fetched("b")),
            autoRetryBudget = 2,
        )
        assertEquals(SyncState.Success(now), effect.state)
        assertEquals(now, effect.lastSynced)
        assertEquals(SyncRerunPolicy.signatureOf(list), effect.signature)
        assertFalse(effect.armAutoRetry)
        assertEquals(0, effect.budget)
        assertNoTrailingLoop(list)
    }

    @Test
    fun provenEmptyIsZeroFailuresEvenWhenTheTxTableHasNoRow() {
        SyncRoundTerminal.Kind.entries.forEach { kind ->
            val effect = finish(
                kind = kind,
                results = listOf(provenEmpty("a"), provenEmpty("b")),
                txCounts = mapOf("a" to 0, "b" to 0),
            )
            assertEquals(kind.name, SyncState.Success(now), effect.state)
            assertFalse(effect.decision.clearLastRoundSignature)
            assertNoTrailingLoop(wallets())
        }
    }

    @Test
    fun syncCollections_partialIndeterminate_isFailedAndClearsTheSignature() {
        val list = wallets()
        val effect = finish(
            kind = SyncRoundTerminal.Kind.COLLECTIONS,
            results = listOf(fetched("a"), indeterminate("b")),
            txCounts = mapOf("a" to 4, "b" to 0),
            autoRetryBudget = 1,
        )
        assertPartial(effect, list)
        assertEquals(1, effect.budget)
        assertEquals(1, effect.failed)
    }

    @Test
    fun fetchBalance_partialIndeterminate_isFailedAndClearsTheSignature() {
        val list = wallets()
        val effect = finish(
            kind = SyncRoundTerminal.Kind.BALANCE,
            results = listOf(fetched("a"), indeterminate("b")),
            txCounts = mapOf("a" to 4, "b" to 0),
            autoRetryBudget = 1,
        )
        assertPartial(effect, list)
        assertEquals(1, effect.budget)
        assertEquals(1, effect.failed)
    }

    @Test
    fun syncCollections_gateMiss_isPartialFailedNotSuccess() {
        val list = wallets()
        val effect = finish(
            kind = SyncRoundTerminal.Kind.COLLECTIONS,
            results = listOf(fetched("a"), fetched("b")),
            txCounts = mapOf("a" to 2, "b" to 0),
        )
        assertPartial(effect, list)
        assertEquals(1, effect.failed)
    }

    @Test
    fun fetchBalance_gateMiss_isPartialFailedNotSuccess() {
        val list = wallets()
        val effect = finish(
            kind = SyncRoundTerminal.Kind.BALANCE,
            results = listOf(fetched("a"), fetched("b")),
            txCounts = mapOf("a" to 2, "b" to 0),
        )
        assertPartial(effect, list)
        assertEquals(previousSync, effect.lastSynced)
        assertEquals(1, effect.failed)
    }

    @Test
    fun indeterminateWithAnEmptyTableIsNotCountedTwice() {
        val ledger = SyncRoundLedger()
        ledger.record("b", CollectionTxSyncResult.Indeterminate())
        val misses = ledger.applyGate(listOf("b"), mapOf("b" to 0))
        assertTrue(misses.isEmpty())
        assertEquals(1, ledger.failed)
    }

    @Test
    fun syncCollections_totalFailure_postsServerUnreachableAndClearsTheSignature() {
        val list = wallets()
        val effect = finish(
            kind = SyncRoundTerminal.Kind.COLLECTIONS,
            results = listOf(indeterminate("a"), indeterminate("b")),
            txCounts = mapOf("a" to 0, "b" to 0),
        )
        assertEquals(
            SyncState.Failed(SyncRoundTerminal.syncIncompleteReason(2, 2), retryable = true),
            effect.state
        )
        assertEquals(previousSync, effect.lastSynced)
        assertNull(effect.signature)
        assertFalse(effect.armAutoRetry)
        assertNoTrailingLoop(list)
    }

    @Test
    fun fetchBalance_totalTransportFailure_doesNotAutoRetry() {
        val list = wallets()
        val effect = finish(
            kind = SyncRoundTerminal.Kind.BALANCE,
            results = listOf(thrown("a"), thrown("b")),
            autoRetryBudget = 0,
        )
        assertEquals(
            SyncState.Failed(SyncRoundTerminal.DOJO_UNREACHABLE, retryable = true),
            effect.state
        )
        assertFalse(effect.armAutoRetry)
        assertEquals(0, effect.budget)
        assertEquals(SyncRerunPolicy.signatureOf(list), effect.signature)
        assertNoTrailingLoop(list)
    }

    @Test
    fun fetchBalance_totalLocalFailure_retriesWhileBudgetRemains() {
        val list = wallets()
        val first = finish(
            kind = SyncRoundTerminal.Kind.BALANCE,
            results = listOf(indeterminate("a"), indeterminate("b")),
            autoRetryBudget = 0,
        )
        assertNull(first.state)
        assertTrue(first.armAutoRetry)
        assertEquals(1, first.budget)
        assertEquals(previousSync, first.lastSynced)
        assertEquals(SyncRerunPolicy.signatureOf(list), first.signature)
        assertNoTrailingLoop(list)

        val second = finish(
            kind = SyncRoundTerminal.Kind.BALANCE,
            results = listOf(indeterminate("a"), indeterminate("b")),
            autoRetryBudget = first.budget,
        )
        assertNull(second.state)
        assertTrue(second.armAutoRetry)
        assertEquals(2, second.budget)
        assertEquals(SyncRerunPolicy.signatureOf(list), second.signature)
        assertNoTrailingLoop(list)
    }

    @Test
    fun fetchBalance_totalGateMiss_stopsRetryingWhenTheBudgetIsSpent() {
        val list = wallets()
        val effect = finish(
            kind = SyncRoundTerminal.Kind.BALANCE,
            results = listOf(fetched("a"), fetched("b")),
            txCounts = mapOf("a" to 0, "b" to 0),
            autoRetryBudget = retryLimit,
        )
        assertEquals(
            SyncState.Failed(SyncRoundTerminal.syncIncompleteReason(2, 2), retryable = true),
            effect.state
        )
        assertFalse(effect.armAutoRetry)
        assertEquals(retryLimit, effect.budget)
        assertEquals(previousSync, effect.lastSynced)
        assertEquals(SyncRerunPolicy.signatureOf(list), effect.signature)
        assertEquals(2, effect.failed)
        assertNoTrailingLoop(list)
    }

    @Test
    fun fetchBalance_userInitiatedTotalFailure_doesNotAutoRetry() {
        val list = wallets()
        val effect = finish(
            kind = SyncRoundTerminal.Kind.BALANCE,
            results = listOf(thrown("a"), thrown("b")),
            userInitiated = true,
            autoRetryBudget = 0,
        )
        assertEquals(
            SyncState.Failed(SyncRoundTerminal.DOJO_UNREACHABLE, retryable = true),
            effect.state
        )
        assertFalse(effect.armAutoRetry)
        assertEquals(0, effect.budget)
        assertEquals(previousSync, effect.lastSynced)
        assertEquals(SyncRerunPolicy.signatureOf(list), effect.signature)
        assertNoTrailingLoop(list)
    }

    @Test
    fun partialFailureStillFailsWhenTheEndpointWasRemoved() {
        SyncRoundTerminal.Kind.entries.forEach { kind ->
            val effect = finish(
                kind = kind,
                results = listOf(fetched("a"), indeterminate("b")),
                endpointEnabled = false,
            )
            assertEquals(
                kind.name,
                SyncState.Failed(SyncRoundTerminal.syncIncompleteReason(1, 2), retryable = true),
                effect.state
            )
            assertEquals(previousSync, effect.lastSynced)
            assertNull(effect.signature)
            assertFalse(effect.armAutoRetry)
            assertNoTrailingLoop(wallets())
        }
    }

    @Test
    fun zeroFailuresWithNoEndpointPostsIdleAndDoesNotWriteLastSynced() {
        val collections = finish(
            kind = SyncRoundTerminal.Kind.COLLECTIONS,
            results = listOf(fetched("a"), fetched("b")),
            endpointEnabled = false,
            standingFailed = true,
            autoRetryBudget = 2,
        )
        assertEquals(SyncState.Idle, collections.state)
        assertEquals(false, collections.decision.staleSuccessRetired)
        assertEquals(previousSync, collections.lastSynced)
        assertEquals(2, collections.budget)

        val balance = finish(
            kind = SyncRoundTerminal.Kind.BALANCE,
            results = listOf(fetched("a"), fetched("b")),
            endpointEnabled = false,
            standingFailed = false,
            autoRetryBudget = 2,
        )
        assertEquals(SyncState.Idle, balance.state)
        assertEquals(true, balance.decision.staleSuccessRetired)
        assertEquals(previousSync, balance.lastSynced)
        assertEquals(0, balance.budget)

        val standing = finish(
            kind = SyncRoundTerminal.Kind.BALANCE,
            results = listOf(fetched("a"), fetched("b")),
            endpointEnabled = false,
            standingFailed = true,
        )
        assertNull(standing.state)
        assertEquals(false, standing.decision.staleSuccessRetired)
        assertEquals(previousSync, standing.lastSynced)
    }

    @Test
    fun failureReason_namesTransportDojoAndLocalSeparately() {
        assertEquals(
            SyncRoundTerminal.DOJO_UNREACHABLE,
            SyncRoundTerminal.failureReason(2, 2, SyncFailureTally(transport = 2)),
        )
        assertEquals(
            SyncRoundTerminal.partialReason(1, 2),
            SyncRoundTerminal.failureReason(1, 2, SyncFailureTally(transport = 1)),
        )
        assertEquals(
            SyncRoundTerminal.dojoErrorReason(2, 2),
            SyncRoundTerminal.failureReason(2, 2, SyncFailureTally(dojo = 2)),
        )
        assertEquals(
            SyncRoundTerminal.dojoErrorReason(1, 2),
            SyncRoundTerminal.failureReason(1, 2, SyncFailureTally(dojo = 1)),
        )
        assertEquals(
            SyncRoundTerminal.syncIncompleteReason(2, 2),
            SyncRoundTerminal.failureReason(2, 2, SyncFailureTally(local = 2)),
        )
        assertEquals(
            SyncRoundTerminal.syncIncompleteReason(1, 2),
            SyncRoundTerminal.failureReason(1, 2, SyncFailureTally(local = 1)),
        )
        assertEquals(
            SyncRoundTerminal.partialReason(2, 2),
            SyncRoundTerminal.failureReason(2, 2, SyncFailureTally(transport = 1, dojo = 1)),
        )
        assertEquals(
            SyncRoundTerminal.partialReason(2, 3),
            SyncRoundTerminal.failureReason(2, 3, SyncFailureTally(transport = 1, local = 1)),
        )
        // A caller that does not pass a tally must not claim the Dojo was unreachable.
        assertEquals(
            SyncRoundTerminal.syncIncompleteReason(2, 2),
            SyncRoundTerminal.failureReason(2, 2, SyncFailureTally()),
        )
    }

    @Test
    fun bothRoundsShareTheTerminalDecisionAndLeaveTheTrailingBaselineAlone() {
        val src = source("app/src/main/java/com/samourai/sentinel/ui/home/HomeViewModel.kt")
        val collections = between(src, "private suspend fun syncCollections", "private suspend fun gateTxHistory")
        val balance = between(src, "fun fetchBalance", "fun getErrorMessage")
        val rerun = between(src, "private fun maybeTrailingRerun", "fun getBalance")

        assertTrue(collections.contains("SyncRoundTerminal.Kind.COLLECTIONS"))
        assertTrue(collections.contains("decision.clearLastRoundSignature"))
        assertTrue(collections.contains("lastRoundSignature = null"))
        assertTrue(collections.contains("autoRetryLimit = AUTO_SYNC_RETRIES"))
        assertFalse(collections.contains("lastSynced"))
        assertFalse(collections.contains("armAutoRetry"))

        assertTrue(balance.contains("SyncRoundTerminal.Kind.BALANCE"))
        assertTrue(balance.contains("decision.clearLastRoundSignature"))
        assertTrue(balance.contains("if (decision.writeLastSynced)"))
        assertTrue(balance.contains("prefsUtil.lastSynced = System.currentTimeMillis()"))
        assertEquals(1, Regex("prefsUtil\\.lastSynced\\s*=").findAll(balance).count())
        assertTrue(balance.contains("if (decision.armAutoRetry)"))
        assertTrue(balance.contains("autoRetryLimit = AUTO_SYNC_RETRIES"))
        assertTrue(balance.contains("syncRerunPending = false"))
        assertTrue(balance.contains("maybeTrailingRerun()"))
        assertFalse(balance.contains("if (failed == collections.size)"))

        assertTrue(rerun.contains("SyncRerunPolicy.shouldRerun(roundStartSignature, current)"))
        assertFalse(src.contains("roundStartSignature = null"))
    }

    private fun assertPartial(effect: Effect, list: ArrayList<PubKeyCollection>) {
        val start = SyncRerunPolicy.signatureOf(list)
        assertEquals(
            SyncState.Failed(SyncRoundTerminal.syncIncompleteReason(1, 2), retryable = true),
            effect.state
        )
        assertEquals(previousSync, effect.lastSynced)
        assertNull(effect.signature)
        assertFalse(effect.armAutoRetry)
        assertTrue(effect.decision.clearLastRoundSignature)
        // Signature cleared: an external arm is not skipped.
        val externalSkipped = effect.signature != null &&
            effect.signature == start
        assertFalse(externalSkipped)
        // This round's trailing re-run still sees the start signature.
        assertFalse(SyncRerunPolicy.shouldRerun(start, list))
        val changed = ArrayList(list)
        changed.add(collection("c", "xpub-c"))
        assertTrue(SyncRerunPolicy.shouldRerun(start, changed))
    }

    private fun assertNoTrailingLoop(list: ArrayList<PubKeyCollection>) {
        val start = SyncRerunPolicy.signatureOf(list)
        assertFalse(SyncRerunPolicy.shouldRerun(start, list))
    }

    private sealed interface Input {
        data class Result(val result: CollectionTxSyncResult) : Input
        data object Thrown : Input
    }

    private data class Effect(
        val decision: SyncRoundTerminal.Decision,
        val state: SyncState?,
        val lastSynced: Long,
        val signature: String?,
        val armAutoRetry: Boolean,
        val budget: Int,
        val failed: Int,
    )

    private fun finish(
        kind: SyncRoundTerminal.Kind,
        results: List<Pair<String, Input>>,
        txCounts: Map<String, Int> = results.associate { (id, _) -> id to 1 },
        endpointEnabled: Boolean = true,
        userInitiated: Boolean = false,
        autoRetryBudget: Int = 0,
        standingFailed: Boolean = false,
    ): Effect {
        val ledger = SyncRoundLedger()
        results.forEach { (id, input) ->
            when (input) {
                is Input.Result -> ledger.record(id, input.result)
                Input.Thrown -> ledger.recordThrown(id)
            }
        }
        ledger.applyGate(results.map { it.first }, txCounts)
        val decision = SyncRoundTerminal.decide(
            kind = kind,
            failed = ledger.failed,
            total = results.size,
            endpointEnabled = endpointEnabled,
            userInitiated = userInitiated,
            autoRetryBudget = autoRetryBudget,
            autoRetryLimit = retryLimit,
            standingFailed = standingFailed,
            tally = ledger.tally(),
        )
        val signature = SyncRerunPolicy.signatureOf(wallets())
        var synced = previousSync
        var kept: String? = signature
        if (decision.clearLastRoundSignature) kept = null
        if (decision.writeLastSynced) synced = now
        return Effect(
            decision = decision,
            state = decision.toSyncState(now),
            lastSynced = synced,
            signature = kept,
            armAutoRetry = decision.armAutoRetry,
            budget = decision.autoRetryBudget,
            failed = ledger.failed,
        )
    }

    private fun fetched(id: String) = id to Input.Result(CollectionTxSyncResult.Fetched(1))
    private fun provenEmpty(id: String) = id to Input.Result(CollectionTxSyncResult.ProvenEmpty)
    private fun indeterminate(id: String) = id to Input.Result(CollectionTxSyncResult.Indeterminate())
    private fun thrown(id: String) = id to Input.Thrown

    private fun wallets(): ArrayList<PubKeyCollection> = arrayListOf(
        collection("a", "xpub-a"),
        collection("b", "xpub-b"),
    )

    private fun collection(id: String, vararg pubKeys: String): PubKeyCollection {
        val pubs = ArrayList<PubKeyModel>()
        pubKeys.forEach { pub ->
            pubs.add(PubKeyModel(pubKey = pub, label = "$id key", type = AddressTypes.BIP84))
        }
        return PubKeyCollection(collectionLabel = id, pubs = pubs).also { it.id = id }
    }

    private fun between(src: String, start: String, end: String): String {
        val from = src.indexOf(start)
        val to = src.indexOf(end)
        assertTrue("$start missing", from >= 0)
        assertTrue("$end missing after $start", to > from)
        return src.substring(from, to)
    }

    private fun source(relative: String): String {
        val file = sequenceOf(File(relative), File(relative.removePrefix("app/"))).firstOrNull { it.isFile }
            ?: error("missing $relative from ${File(".").absolutePath}")
        return file.readText()
    }
}
