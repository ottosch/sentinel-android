package com.samourai.sentinel.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncRoundDiffTest {

    // Collection ids used across the diff tests.
    private val idA = "idA"
    private val idB = "idB"
    private val idC = "idC"

    // Production form: signatureOf always writes "id:" even with no keys.
    private fun signature(vararg entries: Pair<String, List<String>>) =
        entries.joinToString("|") { (id, keys) ->
            id + ":" + keys.joinToString(",")
        }

    @Test
    fun parseRoundTripsTheHomeViewModelShape() {
        val sig = signature(idA to listOf("k1", "k2"), idB to listOf("k3"))
        val parsed = SyncRoundDiff.parse(sig)!!
        assertEquals(setOf("k1", "k2"), parsed[idA])
        assertEquals(setOf("k3"), parsed[idB])
        assertEquals(2, parsed.size)
    }

    @Test
    fun parseIsEmptyForNullBlankAndGarbage() {
        assertNull(SyncRoundDiff.parse(null))
        assertNull(SyncRoundDiff.parse(""))
        assertNull(SyncRoundDiff.parse("   "))
        // A blank id is not a collection. ":" is an empty id, and a
        // leading separator makes the first segment blank too.
        assertNull(SyncRoundDiff.parse(":"))
        assertNull(SyncRoundDiff.parse("|idA:k1"))
    }

    @Test
    fun parseHandlesIdOnlySegmentNoKeys() {
        // A segment without ':' is an id-only entry (collection with no keys).
        // "idX" -> id-idX, no keys. ("idX:idY" would instead be id 'idX' + key 'idY'.)
        val parsed = SyncRoundDiff.parse("idX")!!
        assertEquals(emptySet<String>(), parsed["idX"])
        assertEquals(1, parsed.size)
    }

    @Test
    fun nullLastMeansFullRound() {
        assertNull(
            SyncRoundDiff.changedCollectionIds(
                null,
                signature(idA to listOf("k1"))
            )
        )
    }

    @Test
    fun addedCollectionIsReported() {
        val before = signature(idA to listOf("k1"), idB to listOf("k2"))
        val after = signature(idA to listOf("k1"), idB to listOf("k2"), idC to listOf("k9"))
        assertEquals(setOf(idC), SyncRoundDiff.changedCollectionIds(before, after))
    }

    @Test
    fun keyAddedToExistingCollectionIsReported() {
        val before = signature(idA to listOf("k1"), idB to listOf("k2"))
        val after = signature(idA to listOf("k1", "k5"), idB to listOf("k2"))
        assertEquals(setOf(idA), SyncRoundDiff.changedCollectionIds(before, after))
    }

    @Test
    fun keyRemovedFromExistingCollectionIsReported() {
        val before = signature(idA to listOf("k1", "k5"), idB to listOf("k2"))
        val after = signature(idA to listOf("k1"), idB to listOf("k2"))
        assertEquals(setOf(idA), SyncRoundDiff.changedCollectionIds(before, after))
    }

    @Test
    fun removedCollectionIsNotReported() {
        val before = signature(idA to listOf("k1"), idB to listOf("k2"))
        val after = signature(idA to listOf("k1"))
        assertEquals(emptySet<String>(), SyncRoundDiff.changedCollectionIds(before, after))
    }

    @Test
    fun reorderYieldsEmptySet() {
        // signatureOf is order-sensitive, so a reorder produces a different
        // signature string; the per-id key sets are unchanged, so the delta
        // is empty and launchSyncRound skips the round instead of fetching.
        val before = signature(idA to listOf("k1"), idB to listOf("k2"))
        val after = signature(idB to listOf("k2"), idA to listOf("k1"))
        assertEquals(emptySet<String>(), SyncRoundDiff.changedCollectionIds(before, after))
    }

    @Test
    fun noChangeYieldsEmptySet() {
        val sig = signature(idA to listOf("k1"), idB to listOf("k2", "k3"))
        assertEquals(emptySet<String>(), SyncRoundDiff.changedCollectionIds(sig, sig))
    }

    @Test
    fun unparseableCurrentFailsSafeToFullRound() {
        assertNull(SyncRoundDiff.changedCollectionIds("x", ""))
    }

    @Test
    fun separatorBearingIdsPinTheAssumption() {
        // Ids are UUIDs and keys are pubkey strings; neither contains
        // '|', ':' or ','. A separator-bearing id silently misparses -
        // documented, not guarded.
        val parsed = SyncRoundDiff.parse("a|b:k")!!
        assertEquals(setOf("a", "b"), parsed.keys)
        assertEquals(emptySet<String>(), parsed["a"])
        assertEquals(setOf("k"), parsed["b"])
    }

    @Test
    fun duplicateIdKeepsTheLastEntry() {
        val last = signature(idA to listOf("k1"))
        val after = signature(idA to listOf("k0"), idA to listOf("k9"))
        assertEquals(setOf(idA), SyncRoundDiff.changedCollectionIds(last, after))
    }
}
