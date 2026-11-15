package com.samourai.sentinel.ui.home

import com.samourai.sentinel.core.SyncState
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.repository.CollectionTxSyncResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Device leg 2026-11-10, build 62c892b8, mainnet Dojo, 2 mainnet + 2
 * testnet. A syncCollections round skipped the two off-network rows,
 * both on-network fetches came back indeterminate, the round cleared
 * lastRoundSignature, and the round's own list writes (delivered on
 * the main thread after "sync re-run skipped") started the next round.
 *
 * The harness counts launches the way HomeViewModel does: a list
 * emission consults [SyncRerunPolicy.isOwnListEmission] and then
 * launchSyncRound's in-flight and last-signature gates. Tor re-ready
 * and fetchBalance call launchSyncRound / fetchBalance directly.
 */
class SyncRoundOwnEmissionTest {

    private val now = 1_700_000_000_000L
    private val retryLimit = 2

    @Test
    fun failedRoundThenIdenticalEmissionsLaunchNoNewRound() {
        listOf(false, true).forEach { activeIsTestnet ->
            val wallets = mixedWallets()
            val harness = Harness()
            harness.emission(wallets)
            assertEquals(activeIsTestnet.toString(), 1, harness.launched)

            // The round's own per-pub writes, while it is still in flight.
            repeat(5) {
                writeBalances(wallets)
                harness.emission(wallets)
            }
            assertEquals(activeIsTestnet.toString(), 1, harness.launched)

            // Total failure of the on-network pair. COLLECTIONS clears
            // the external-arm signature and blocks own emissions.
            // roundStartSignature stays.
            harness.finishFailure()
            repeat(8) {
                writeBalances(wallets)
                harness.emission(wallets)
            }
            assertEquals(activeIsTestnet.toString(), 1, harness.launched)
            assertFalse(SyncRerunPolicy.shouldRerun(harness.roundStart, wallets))

            // The bug, pinned so the flag is what stops it: a cleared
            // signature alone lets the same list straight back in.
            val unblocked = Harness()
            unblocked.emission(wallets)
            unblocked.finishClearedWithoutTheBlock()
            unblocked.emission(wallets)
            assertEquals(activeIsTestnet.toString(), 2, unblocked.launched)
        }
    }

    @Test
    fun onNetworkSuccessDoesNotRetryAndDoesNotRelaunch() {
        listOf(false, true).forEach { activeIsTestnet ->
            val wallets = mixedWallets()
            val inRound = collectionsForSyncRound(
                wallets,
                activeIsTestnet = activeIsTestnet,
                dojoEnabled = true,
            )
            assertEquals(
                if (activeIsTestnet) listOf("t1", "t2") else listOf("m1", "m2"),
                inRound.map { it.id },
            )
            SyncRoundTerminal.Kind.entries.forEach { kind ->
                val effect = finish(
                    kind = kind,
                    inRound = inRound,
                    fetch = { CollectionTxSyncResult.Fetched(1) },
                )
                assertEquals(kind.name, SyncState.Success(now), effect.state)
                assertEquals(kind.name, 0, effect.failed)
                assertEquals(kind.name, 2, effect.total)
                assertFalse(kind.name, effect.decision.armAutoRetry)
                assertFalse(kind.name, effect.decision.clearLastRoundSignature)
                assertEquals(0, effect.decision.autoRetryBudget)
            }

            val harness = Harness()
            harness.emission(wallets)
            harness.finishSuccess()
            repeat(8) {
                writeBalances(wallets)
                harness.emission(wallets)
            }
            assertEquals(activeIsTestnet.toString(), 1, harness.launched)
        }
    }

    /**
     * The two "tx history indeterminate" lines are fetchFromServer
     * results for the two collections the round actually called.
     * The skipped rows are not in the ledger and are not in M.
     */
    @Test
    fun indeterminateOnNetworkIsTheFetchResultNotASkippedRow() {
        listOf(false, true).forEach { activeIsTestnet ->
            val wallets = mixedWallets()
            val inRound = collectionsForSyncRound(
                wallets,
                activeIsTestnet = activeIsTestnet,
                dojoEnabled = true,
            )
            val effect = finish(
                kind = SyncRoundTerminal.Kind.COLLECTIONS,
                inRound = inRound,
                fetch = { CollectionTxSyncResult.Indeterminate() },
            )
            assertEquals(
                if (activeIsTestnet) listOf("t1", "t2") else listOf("m1", "m2"),
                effect.attempted,
            )
            assertEquals(2, effect.failed)
            assertEquals(2, effect.total)
            assertEquals(
                SyncState.Failed(SyncRoundTerminal.syncIncompleteReason(2, 2), retryable = true),
                effect.state,
            )
            assertTrue(effect.decision.clearLastRoundSignature)
            assertFalse(effect.decision.armAutoRetry)
            // Off-network ids were never recorded.
            assertFalse(effect.attempted.contains(if (activeIsTestnet) "m1" else "t1"))
            assertFalse(effect.attempted.contains(if (activeIsTestnet) "m2" else "t2"))

            // fetchBalance total failure retries and keeps the signature,
            // so it is not the loop in the log. The log is syncCollections.
            val balance = finish(
                kind = SyncRoundTerminal.Kind.BALANCE,
                inRound = inRound,
                fetch = { CollectionTxSyncResult.Indeterminate() },
            )
            assertTrue(balance.decision.armAutoRetry)
            assertFalse(balance.decision.clearLastRoundSignature)
            assertEquals(1, balance.decision.autoRetryBudget)
            assertEquals(null, balance.state)
        }
    }

    @Test
    fun partialFailureDoesNotRearmOnTheSameList() {
        val wallets = mixedWallets()
        val harness = Harness()
        harness.emission(wallets)
        harness.finishFailure()
        repeat(4) { harness.emission(wallets) }
        assertEquals(1, harness.launched)
    }

    @Test
    fun externalArmsStillLaunchAfterFailure() {
        val wallets = mixedWallets()
        val harness = Harness()
        harness.emission(wallets)
        harness.finishFailure()

        // #47: a pub added is a different signature.
        val changed = ArrayList(wallets)
        changed[0].pubs.add(
            PubKeyModel(pubKey = "xpub-added", label = "added", type = AddressTypes.BIP84),
        )
        harness.emission(changed)
        assertEquals(2, harness.launched)

        harness.finishFailure()
        // Tor re-ready calls launchSyncRound, not the list-emission gate.
        harness.external(changed)
        assertEquals(3, harness.launched)

        harness.finishFailure()
        // Pull-to-refresh / Retry is fetchBalance(userInitiated = true).
        harness.userRefresh(changed)
        assertEquals(4, harness.launched)

        // A Tor deferral nulls the external signature and does not block
        // the next list emission.
        harness.torDeferral()
        val same = mixedWallets()
        // Re-stamp by emitting once, then defer, then emit again.
        val deferred = Harness()
        deferred.emission(same)
        deferred.torDeferral()
        deferred.emission(same)
        assertEquals(2, deferred.launched)
    }

    @Test
    fun productionWiresTheGateOnlyOnTheListEmission() {
        val home = source("app/src/main/java/com/samourai/sentinel/ui/home/HomeViewModel.kt")
        val observe = between(home, "private fun observeTor()", "fun getCollections()")
        val collections = between(home, "fun getCollections()", "private suspend fun syncCollections")
        val balance = between(home, "fun fetchBalance", "fun getErrorMessage")

        assertTrue(observe.contains("launchSyncRound("))
        assertFalse(observe.contains("isOwnListEmission"))

        val gate = collections.indexOf("SyncRerunPolicy.isOwnListEmission(")
        val launch = collections.indexOf("launchSyncRound(collections)")
        assertTrue(gate >= 0 && launch > gate)
        assertTrue(
            collections.contains(
                "sync round skipped: own emission, list unchanged since round start",
            ),
        )
        assertFalse(balance.contains("isOwnListEmission"))
        assertTrue(balance.contains("blockOwnListEmission = false"))
        assertTrue(balance.contains("blockOwnListEmission = true"))
        assertFalse(home.contains("roundStartSignature = null"))

        val activity = source("app/src/main/java/com/samourai/sentinel/ui/home/HomeActivity.kt")
        val refresh = between(activity, "private fun requestRefresh()", "private fun showToolOptions")
        assertTrue(refresh.contains("model.fetchBalance(userInitiated = true)"))
        assertFalse(refresh.contains("isOwnListEmission"))
    }

    @Test
    fun fetchAsksTheCollectionPubsAndNamesWhyHistoryIsIndeterminate() {
        val src = source(
            "app/src/main/java/com/samourai/sentinel/data/repository/TransactionsRepository.kt",
        )
        val start = src.indexOf("suspend fun fetchFromServer(collectionId: String)")
        val end = src.indexOf("private suspend fun saveTx")
        assertTrue(start >= 0 && end > start)
        val fn = src.substring(start, end)
        assertTrue(fn.contains("collection.pubs.forEach"))
        assertTrue(fn.contains("apiService.getWallet(it.pubKey)"))
        assertFalse(fn.contains("collectionsForSyncRound"))
        assertFalse(fn.contains("inRound"))
        // One wallet call per pub of this collection, not a join of the list.
        assertEquals(1, Regex("apiService\\.getWallet").findAll(fn).count())
        listOf(
            "collection not in repository",
            "missing from snapshot during pub update",
            "saveTx found no collection",
            "empty tx round without a fee table; not wiping",
            "wallet response not usable",
        ).forEach { reason ->
            assertTrue(reason, fn.contains(reason))
        }
    }

    private fun finish(
        kind: SyncRoundTerminal.Kind,
        inRound: List<PubKeyCollection>,
        fetch: (PubKeyCollection) -> CollectionTxSyncResult,
    ): Effect {
        val ledger = SyncRoundLedger()
        val attempted = ArrayList<String>()
        inRound.forEach { collection ->
            attempted.add(collection.id)
            ledger.record(collection.id, fetch(collection))
        }
        val counts = inRound.associate { it.id to 1 }
        ledger.applyGate(inRound.map { it.id }, counts)
        val decision = SyncRoundTerminal.decide(
            kind = kind,
            failed = ledger.failed,
            total = inRound.size,
            endpointEnabled = true,
            userInitiated = false,
            autoRetryBudget = 0,
            autoRetryLimit = retryLimit,
            standingFailed = false,
            tally = ledger.tally(),
        )
        return Effect(
            decision = decision,
            state = decision.toSyncState(now),
            attempted = attempted,
            failed = ledger.failed,
            total = inRound.size,
        )
    }

    /**
     * launchSyncRound's gates, plus the list-emission check that
     * sits in front of it. [launched] counts rounds that pass both.
     */
    private class Harness {
        var roundStart: String? = null
        var last: String? = null
        var blockOwn = false
        var inFlight = false
        var launched = 0

        fun emission(list: List<PubKeyCollection>) {
            if (SyncRerunPolicy.isOwnListEmission(blockOwn, roundStart, list)) return
            admit(list)
        }

        /** observeTor -> launchSyncRound. */
        fun external(list: List<PubKeyCollection>) {
            admit(list)
        }

        /** fetchBalance(userInitiated = true). Replaces an in-flight round. */
        fun userRefresh(list: List<PubKeyCollection>) {
            inFlight = false
            admit(list)
        }

        fun finishFailure() {
            inFlight = false
            last = null
            blockOwn = true
        }

        /** What the device did before the block: clear, and treat the next emission as external. */
        fun finishClearedWithoutTheBlock() {
            inFlight = false
            last = null
            blockOwn = false
        }

        fun finishSuccess() {
            inFlight = false
            blockOwn = false
        }

        fun torDeferral() {
            inFlight = false
            last = null
            blockOwn = false
        }

        private fun admit(list: List<PubKeyCollection>) {
            if (inFlight) return
            val signature = SyncRerunPolicy.signatureOf(list)
            if (last != null && signature == last) return
            last = signature
            roundStart = signature
            blockOwn = false
            inFlight = true
            launched++
        }
    }

    private data class Effect(
        val decision: SyncRoundTerminal.Decision,
        val state: SyncState?,
        val attempted: List<String>,
        val failed: Int,
        val total: Int,
    )

    /** The round's own writes. None of these are in the signature. */
    private fun writeBalances(list: List<PubKeyCollection>) {
        list.forEach { collection ->
            collection.lastRefreshed = collection.lastRefreshed + 1L
            collection.pubs.forEach { it.balance = it.balance + 1L }
            collection.updateBalance()
        }
    }

    private fun mixedWallets(): ArrayList<PubKeyCollection> = arrayListOf(
        wallet("m1", "xpub"),
        wallet("t1", "tpub"),
        wallet("m2", "zpub"),
        wallet("t2", "vpub"),
    )

    private fun wallet(id: String, prefix: String): PubKeyCollection {
        val pubs = arrayListOf(
            PubKeyModel(
                pubKey = "${prefix}fake-$id",
                label = "$id key",
                type = AddressTypes.BIP84,
            ),
        )
        return PubKeyCollection(collectionLabel = id, pubs = pubs).also { it.id = id }
    }

    private fun between(src: String, start: String, end: String): String {
        val from = src.indexOf(start)
        val to = src.indexOf(end, from + start.length)
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
