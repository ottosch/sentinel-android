package com.samourai.sentinel.ui.home

import com.samourai.sentinel.core.ConnectionIndicator
import com.samourai.sentinel.core.DojoContact
import com.samourai.sentinel.core.SyncState
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.repository.CollectionTxSyncResult
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.ui.adapters.CollectionRowNetworkState
import com.samourai.sentinel.ui.adapters.collectionRowNetworkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A sync round attempts only collections the #429 row state leaves
 * ungreyed. The match is the active network against the collection,
 * in both directions. Off-network rows are not attempted, not gated,
 * and not counted in either side of "N of M".
 *
 * Fixtures are fake prefix strings. NetworkClassifier reads only the
 * prefix, so no real key material appears here.
 */
class SyncRoundNetworkScopeTest {

    private val previousSync = 50L
    private val now = 1_700_000_000_000L
    private val retryLimit = 2
    private val storedBalance = 50_000L
    private val syncedBalance = 7L

    @Test
    fun mainnetPairing_attemptsOnlyMainnetCollections_andSucceeds() {
        SyncRoundTerminal.Kind.entries.forEach { kind ->
            val wallets = mixedWallets()
            val effect = finish(
                kind = kind,
                collections = wallets,
                activeIsTestnet = false,
                fetch = { collection ->
                    collection.balance = syncedBalance
                    fetched()
                },
            )
            assertEquals(kind.name, listOf("m1", "m2"), effect.attempted)
            assertEquals(kind.name, SyncState.Success(now), effect.state)
            assertEquals(kind.name, 0, effect.failed)
            assertEquals(kind.name, 2, effect.total)
            assertFalse(effect.decision.clearLastRoundSignature)
            assertEquals(kind.name, ConnectionIndicator.GREEN, homeIndicator(effect.state!!))
            assertEquals(storedBalance, balanceOf(wallets, "t1"))
            assertEquals(storedBalance, balanceOf(wallets, "t2"))
            assertEquals(syncedBalance, balanceOf(wallets, "m1"))
            assertEquals(syncedBalance, balanceOf(wallets, "m2"))
            if (kind == SyncRoundTerminal.Kind.BALANCE) {
                assertEquals(now, effect.lastSynced)
            } else {
                assertEquals(previousSync, effect.lastSynced)
            }
        }
    }

    /**
     * Paired to a testnet Dojo. Mainnet collections are the grey rows.
     * They are not attempted, they contribute to neither N nor M, and
     * a clean on-network round leaves the Home dot green.
     */
    @Test
    fun testnetPairing_skipsMainnetCollections_andTheIndicatorStaysGreen() {
        SyncRoundTerminal.Kind.entries.forEach { kind ->
            val wallets = mixedWallets()
            val effect = finish(
                kind = kind,
                collections = wallets,
                activeIsTestnet = true,
                fetch = { collection ->
                    collection.balance = syncedBalance
                    fetched()
                },
                // A gate over the unfiltered list would count these
                // empty mainnet histories as misses.
                txCounts = mapOf("m1" to 0, "m2" to 0, "t1" to 3, "t2" to 4),
            )
            assertEquals(kind.name, listOf("t1", "t2"), effect.attempted)
            assertEquals(kind.name, SyncState.Success(now), effect.state)
            assertEquals(kind.name, 0, effect.failed)
            assertEquals(kind.name, 2, effect.total)
            assertFalse(kind.name, effect.state is SyncState.Failed)
            assertFalse(effect.decision.clearLastRoundSignature)
            assertEquals(kind.name, ConnectionIndicator.GREEN, homeIndicator(effect.state!!))
            assertEquals(storedBalance, balanceOf(wallets, "m1"))
            assertEquals(storedBalance, balanceOf(wallets, "m2"))
            assertEquals(syncedBalance, balanceOf(wallets, "t1"))
            assertEquals(syncedBalance, balanceOf(wallets, "t2"))
        }
    }

    @Test
    fun oneOnNetworkFailure_isOneOfTwo_inEitherDirection() {
        val mainnet = finish(
            kind = SyncRoundTerminal.Kind.BALANCE,
            collections = mixedWallets(),
            activeIsTestnet = false,
            fetch = { collection ->
                if (collection.id == "m2") {
                    indeterminate()
                } else {
                    collection.balance = syncedBalance
                    fetched()
                }
            },
            txCounts = mapOf("m1" to 4, "m2" to 0, "t1" to 0, "t2" to 0),
        )
        assertPartial(mainnet, attempted = listOf("m1", "m2"))
        assertEquals(syncedBalance, balanceOf(mainnet.collections, "m1"))
        assertEquals(storedBalance, balanceOf(mainnet.collections, "m2"))
        assertEquals(storedBalance, balanceOf(mainnet.collections, "t1"))
        assertEquals(storedBalance, balanceOf(mainnet.collections, "t2"))
        assertEquals(ConnectionIndicator.YELLOW, homeIndicator(mainnet.state!!))

        val testnet = finish(
            kind = SyncRoundTerminal.Kind.COLLECTIONS,
            collections = mixedWallets(),
            activeIsTestnet = true,
            fetch = { collection ->
                if (collection.id == "t2") {
                    indeterminate()
                } else {
                    collection.balance = syncedBalance
                    fetched()
                }
            },
            txCounts = mapOf("t1" to 4, "t2" to 0, "m1" to 0, "m2" to 0),
        )
        assertPartial(testnet, attempted = listOf("t1", "t2"))
        assertEquals(syncedBalance, balanceOf(testnet.collections, "t1"))
        assertEquals(storedBalance, balanceOf(testnet.collections, "m1"))
        assertEquals(storedBalance, balanceOf(testnet.collections, "m2"))
        assertEquals(ConnectionIndicator.YELLOW, homeIndicator(testnet.state!!))
    }

    @Test
    fun allCollectionsOffNetwork_isSuccess_inEitherDirection() {
        val onMainnet = listOf(wallet("t1", "tpub"), wallet("t2", "vpub"))
        val mainnetRound = finish(
            kind = SyncRoundTerminal.Kind.BALANCE,
            collections = onMainnet,
            activeIsTestnet = false,
            fetch = { error("off-network collection ${it.id} was attempted") },
            txCounts = mapOf("t1" to 0, "t2" to 0),
        )
        assertEquals(emptyList<String>(), mainnetRound.attempted)
        assertEquals(SyncState.Success(now), mainnetRound.state)
        assertEquals(0, mainnetRound.failed)
        assertEquals(0, mainnetRound.total)
        assertEquals(now, mainnetRound.lastSynced)
        assertFalse(mainnetRound.decision.clearLastRoundSignature)
        assertFalse(mainnetRound.decision.armAutoRetry)
        assertEquals(ConnectionIndicator.GREEN, homeIndicator(mainnetRound.state!!))
        assertEquals(storedBalance, balanceOf(onMainnet, "t1"))
        assertEquals(storedBalance, balanceOf(onMainnet, "t2"))

        val onTestnet = listOf(wallet("m1", "xpub"), wallet("m2", "ypub"))
        val testnetRound = finish(
            kind = SyncRoundTerminal.Kind.COLLECTIONS,
            collections = onTestnet,
            activeIsTestnet = true,
            fetch = { error("off-network collection ${it.id} was attempted") },
            txCounts = mapOf("m1" to 0, "m2" to 0),
        )
        assertEquals(emptyList<String>(), testnetRound.attempted)
        assertEquals(SyncState.Success(now), testnetRound.state)
        assertEquals(0, testnetRound.failed)
        assertEquals(0, testnetRound.total)
        assertEquals(previousSync, testnetRound.lastSynced)
        assertFalse(testnetRound.decision.clearLastRoundSignature)
        assertEquals(ConnectionIndicator.GREEN, homeIndicator(testnetRound.state!!))
        assertEquals(storedBalance, balanceOf(onTestnet, "m1"))
        assertEquals(storedBalance, balanceOf(onTestnet, "m2"))
    }

    /**
     * Home's dot and the Network Dojo row share [DojoContact].
     * Off-network rows do not turn the dot red. A local sync miss
     * stays yellow until a contact is recorded. Unreachable is red.
     * A real contact stays green; the banner keeps the sync result.
     */
    @Test
    fun homeIndicator_followsDojoContactNotEverySyncFailure() {
        val skippedOnMainnet = finish(
            kind = SyncRoundTerminal.Kind.BALANCE,
            collections = mixedWallets(),
            activeIsTestnet = false,
            fetch = { fetched() },
        )
        val skippedOnTestnet = finish(
            kind = SyncRoundTerminal.Kind.BALANCE,
            collections = mixedWallets(),
            activeIsTestnet = true,
            fetch = { fetched() },
        )
        val onNetworkFailure = finish(
            kind = SyncRoundTerminal.Kind.BALANCE,
            collections = mixedWallets(),
            activeIsTestnet = true,
            fetch = { collection ->
                if (collection.id == "t1") indeterminate() else fetched()
            },
        )
        assertEquals(ConnectionIndicator.GREEN, homeIndicator(skippedOnMainnet.state!!))
        assertEquals(ConnectionIndicator.GREEN, homeIndicator(skippedOnTestnet.state!!))
        assertEquals(
            SyncState.Failed(SyncRoundTerminal.syncIncompleteReason(1, 2), retryable = true),
            onNetworkFailure.state,
        )
        assertEquals(ConnectionIndicator.YELLOW, homeIndicator(onNetworkFailure.state!!))
        assertEquals(
            ConnectionIndicator.GREEN,
            homeConnectionIndicator(
                sync = SyncState.Success(now),
                torRequired = true,
                torState = EnumTorState.ON,
                torProgress = 100,
                endpointEnabled = true,
            ),
        )
        assertEquals(
            ConnectionIndicator.RED,
            homeConnectionIndicator(
                sync = SyncState.Failed(SyncRoundTerminal.DOJO_UNREACHABLE, retryable = true),
                torRequired = true,
                torState = EnumTorState.ON,
                torProgress = 100,
                endpointEnabled = true,
                reachability = DojoContact.Unreachable(now, "socks"),
            ),
        )
        assertEquals(
            ConnectionIndicator.GREEN,
            homeConnectionIndicator(
                sync = onNetworkFailure.state!!,
                torRequired = true,
                torState = EnumTorState.ON,
                torProgress = 100,
                endpointEnabled = true,
                reachability = DojoContact.Reachable(now),
            ),
        )
        assertEquals(
            ConnectionIndicator.YELLOW,
            homeConnectionIndicator(
                sync = onNetworkFailure.state!!,
                torRequired = true,
                torState = EnumTorState.ON,
                torProgress = 100,
                endpointEnabled = true,
                reachability = DojoContact.DojoError(now, 500, "http"),
            ),
        )
        assertEquals(
            ConnectionIndicator.RED,
            homeConnectionIndicator(
                sync = SyncState.Failed(SyncRoundTerminal.DOJO_UNREACHABLE, retryable = true),
                torRequired = true,
                torState = EnumTorState.ON,
                torProgress = 100,
                endpointEnabled = true,
            ),
        )
    }

    @Test
    fun scopeAgreesWithTheRowStateInBothDirections() {
        val collections = listOf(
            wallet("x", "xpub"),
            wallet("y", "ypub"),
            wallet("z", "zpub"),
            wallet("t", "tpub"),
            wallet("u", "upub"),
            wallet("v", "vpub"),
            wallet("unknown", "fake"),
            wallet("mixed", "xpub", "tpub"),
            wallet("abstain", "tpub", "fake"),
        )
        listOf(false, true).forEach { activeIsTestnet ->
            listOf(false, true).forEach { dojoEnabled ->
                val expected = collections.filter { collection ->
                    collectionRowNetworkState(
                        collection,
                        activeIsTestnet = activeIsTestnet,
                        dojoEnabled = dojoEnabled,
                    ) is CollectionRowNetworkState.Normal
                }.map { it.id }
                assertEquals(
                    "activeIsTestnet=$activeIsTestnet dojoEnabled=$dojoEnabled",
                    expected,
                    collectionsForSyncRound(
                        collections,
                        activeIsTestnet = activeIsTestnet,
                        dojoEnabled = dojoEnabled,
                    ).map { it.id },
                )
            }
        }
        // The repro's two directions, spelled out.
        assertEquals(
            listOf("x", "y", "z", "unknown", "mixed"),
            collectionsForSyncRound(collections, activeIsTestnet = false, dojoEnabled = true)
                .map { it.id },
        )
        assertEquals(
            listOf("t", "u", "v", "unknown", "mixed", "abstain"),
            collectionsForSyncRound(collections, activeIsTestnet = true, dojoEnabled = true)
                .map { it.id },
        )
    }

    @Test
    fun bothRoundsFilterBeforeTheFetchTheGateAndTheTotal() {
        val vm = source("app/src/main/java/com/samourai/sentinel/ui/home/HomeViewModel.kt")
        val collections = between(vm, "private suspend fun syncCollections", "private suspend fun gateTxHistory")
        val balance = between(vm, "fun fetchBalance", "fun getErrorMessage")
        listOf(collections, balance).forEach { fn ->
            assertTrue(fn.contains("collectionsForSyncRound("))
            assertTrue(fn.contains("activeIsTestnet = prefsUtil.testnet == true"))
            assertTrue(fn.contains("dojoEnabled = SentinelState.isDojoEnabled()"))
            assertFalse(fn.contains("activeIsTestnet = true"))
            assertFalse(fn.contains("activeIsTestnet = false"))
            assertTrue(fn.contains("inRound.forEach"))
            assertFalse(fn.contains("collections.forEach"))
            assertTrue(fn.contains("gateTxHistory(inRound, ledger)"))
            assertTrue(fn.contains("total = inRound.size"))
            assertFalse(fn.contains("total = collections.size"))
            assertEquals(1, Regex("transactionsRepository\\.fetchFromServer").findAll(fn).count())
        }
        val beforeAutoLoop = collections.substringBefore("inRound.forEach")
        assertTrue(beforeAutoLoop.contains("SyncState.Success"))
        assertFalse(beforeAutoLoop.contains("SyncState.Failed("))
        assertFalse(beforeAutoLoop.contains("fetchFromServer"))

        val indicator = between(vm, "private fun computeIndicator", "fun fetchBalance")
        assertTrue(indicator.contains("homeConnectionIndicator("))
        assertFalse(indicator.contains("ConnectionIndicator.RED"))
        assertFalse(indicator.contains("ConnectionIndicator.GREEN"))

        val scope = between(
            source("app/src/main/java/com/samourai/sentinel/ui/home/SyncRoundTerminal.kt"),
            "internal fun collectionsForSyncRound",
            "internal object SyncRoundTerminal",
        )
        assertTrue(scope.contains("collectionRowNetworkState("))
        assertTrue(scope.contains("activeIsTestnet = activeIsTestnet"))
        assertFalse(scope.contains("NetworkClassifier"))
        assertFalse(scope.contains("fromCollection"))
        assertFalse(scope.contains("fromXpub"))
        assertFalse(scope.contains("DerivedNetwork"))
        listOf("tpub", "upub", "vpub", "xpub", "ypub", "zpub").forEach { prefix ->
            assertFalse(scope.contains(prefix))
        }
    }

    private fun assertPartial(effect: Effect, attempted: List<String>) {
        assertEquals(attempted, effect.attempted)
        assertEquals(
            SyncState.Failed(SyncRoundTerminal.syncIncompleteReason(1, 2), retryable = true),
            effect.state,
        )
        assertEquals(1, effect.failed)
        assertEquals(2, effect.total)
        assertEquals(previousSync, effect.lastSynced)
        assertNull(effect.signature)
        assertTrue(effect.decision.clearLastRoundSignature)
        assertFalse(effect.decision.writeLastSynced)
        assertFalse(effect.decision.armAutoRetry)
        val start = SyncRerunPolicy.signatureOf(effect.collections)
        assertFalse(SyncRerunPolicy.shouldRerun(start, effect.collections))
    }

    /**
     * Tor connected, bootstrap finished, endpoint configured: the
     * Network screen is green. Home uses the same inputs plus the
     * round's terminal state.
     */
    private fun homeIndicator(sync: SyncState): ConnectionIndicator =
        homeConnectionIndicator(
            sync = sync,
            torRequired = true,
            torState = EnumTorState.ON,
            torProgress = 100,
            endpointEnabled = true,
        )

    private sealed interface Input {
        data class Result(val result: CollectionTxSyncResult) : Input
        data object Thrown : Input
    }

    private data class Effect(
        val collections: List<PubKeyCollection>,
        val decision: SyncRoundTerminal.Decision,
        val state: SyncState?,
        val lastSynced: Long,
        val signature: String?,
        val attempted: List<String>,
        val failed: Int,
        val total: Int,
    )

    private fun finish(
        kind: SyncRoundTerminal.Kind,
        collections: List<PubKeyCollection>,
        activeIsTestnet: Boolean,
        dojoEnabled: Boolean = true,
        fetch: (PubKeyCollection) -> Input,
        txCounts: Map<String, Int> = emptyMap(),
        endpointEnabled: Boolean = true,
        userInitiated: Boolean = false,
        autoRetryBudget: Int = 0,
    ): Effect {
        val inRound = collectionsForSyncRound(
            collections,
            activeIsTestnet = activeIsTestnet,
            dojoEnabled = dojoEnabled,
        )
        val attempted = ArrayList<String>()
        val ledger = SyncRoundLedger()
        inRound.forEach { collection ->
            attempted.add(collection.id)
            when (val input = fetch(collection)) {
                is Input.Result -> ledger.record(collection.id, input.result)
                Input.Thrown -> ledger.recordThrown(collection.id)
            }
        }
        val counts = HashMap(txCounts)
        inRound.forEach { collection ->
            if (collection.id !in counts) counts[collection.id] = 1
        }
        ledger.applyGate(inRound.map { it.id }, counts)
        val decision = SyncRoundTerminal.decide(
            kind = kind,
            failed = ledger.failed,
            total = inRound.size,
            endpointEnabled = endpointEnabled,
            userInitiated = userInitiated,
            autoRetryBudget = autoRetryBudget,
            autoRetryLimit = retryLimit,
            standingFailed = false,
            tally = ledger.tally(),
        )
        val signature = SyncRerunPolicy.signatureOf(collections)
        var synced = previousSync
        var kept: String? = signature
        if (decision.clearLastRoundSignature) kept = null
        if (decision.writeLastSynced) synced = now
        return Effect(
            collections = collections,
            decision = decision,
            state = decision.toSyncState(now),
            lastSynced = synced,
            signature = kept,
            attempted = attempted,
            failed = ledger.failed,
            total = inRound.size,
        )
    }

    private fun fetched() = Input.Result(CollectionTxSyncResult.Fetched(1))
    private fun indeterminate() = Input.Result(CollectionTxSyncResult.Indeterminate())

    /** Two mainnet, two testnet. The device repro's shape. */
    private fun mixedWallets(): List<PubKeyCollection> = listOf(
        wallet("m1", "xpub"),
        wallet("t1", "tpub"),
        wallet("m2", "zpub"),
        wallet("t2", "vpub"),
    )

    private fun wallet(id: String, vararg prefixes: String): PubKeyCollection {
        val pubs = ArrayList<PubKeyModel>()
        prefixes.forEachIndexed { index, prefix ->
            pubs.add(
                PubKeyModel(
                    pubKey = "${prefix}fake-$id-$index",
                    label = "$id key",
                    type = AddressTypes.BIP84,
                )
            )
        }
        return PubKeyCollection(
            collectionLabel = id,
            balance = storedBalance,
            pubs = pubs,
        ).also { it.id = id }
    }

    private fun balanceOf(collections: List<PubKeyCollection>, id: String): Long =
        collections.first { it.id == id }.balance

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
