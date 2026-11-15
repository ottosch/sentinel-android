package com.samourai.sentinel.ui.collectionDetails.send

import com.samourai.sentinel.data.Utxo
import com.samourai.sentinel.data.repository.PubSyncHealth
import com.samourai.sentinel.data.repository.SyncFailureKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A failed XPUB's kept UTXOs stay out of send until that XPUB syncs.
 */
class UnsyncedXpubTest {

    @Before
    fun reset() {
        PubSyncHealth.clearAll()
    }

    @After
    fun clear() {
        PubSyncHealth.clearAll()
    }

    @Test
    fun aFailedPubIsHiddenUntilItSyncs() {
        val hidden = utxo("a", "xpub-a")
        val kept = utxo("b", "xpub-b")
        val rows = listOf(hidden, kept)
        PubSyncHealth.markFailed("xpub-a", SyncFailureKind.DOJO)

        assertEquals(listOf(kept), utxosSelectableForSend(rows))
        assertEquals(1, hiddenXpubCount(rows))
        assertEquals(
            "1 XPUB didn't sync; its coins are hidden until it does",
            unsyncedXpubNote(1),
        )
        assertTrue(PubSyncHealth.isHidden("xpub-a"))
        assertFalse(PubSyncHealth.isHidden("xpub-b"))

        PubSyncHealth.clear("xpub-a")
        assertEquals(rows, utxosSelectableForSend(rows))
        assertEquals(0, hiddenXpubCount(rows))
        assertNull(unsyncedXpubNote(0))
    }

    @Test
    fun severalFailedPubsUseThePluralNoteAndAnEmptyKeyIsNotHidden() {
        PubSyncHealth.markFailed("xpub-a", SyncFailureKind.TRANSPORT)
        PubSyncHealth.markFailed("xpub-b", SyncFailureKind.LOCAL)
        PubSyncHealth.markFailed("", SyncFailureKind.DOJO)
        val rows = listOf(utxo("a", "xpub-a"), utxo("b", "xpub-b"), utxo("blank", ""))
        assertEquals(listOf(utxo("blank", "")), utxosSelectableForSend(rows))
        assertEquals(2, hiddenXpubCount(rows))
        assertEquals(
            "2 XPUBs didn't sync; their coins are hidden until they do",
            unsyncedXpubNote(2),
        )
        assertFalse(PubSyncHealth.isHidden(""))
    }

    private fun utxo(idx: String, pubKey: String) = Utxo(idx = idx, pubKey = pubKey, value = 1L)
}
