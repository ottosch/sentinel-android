package com.samourai.sentinel.ui.adapters

import com.samourai.sentinel.core.DerivedNetwork
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #429: every row of the row-state table, pinned without Android
 * machinery - the PairingNetworkDecisionTest precedent.
 *
 * Fixtures are deliberately-fake prefix strings ("xpubfake-key-1"):
 * NetworkClassifier.fromXpub reads only the prefix, so no real or
 * plausible key material appears in this repository.
 */
class CollectionRowNetworkStateTest {

    private fun pub(prefix: String, n: Int) = PubKeyModel(
        pubKey = "${prefix}fake-key-$n",
        label = "fixture $n",
        type = AddressTypes.BIP84,
        balance = 0L
    )

    private fun collection(vararg prefixes: String) = PubKeyCollection(
        collectionLabel = "fixture",
        pubs = prefixes.mapIndexed { i, p -> pub(p, i) }.toCollection(ArrayList())
    )

    // no verdict -> Normal, never guess

    @Test
    fun `empty collection renders normal`() {
        assertEquals(
            CollectionRowNetworkState.Normal,
            collectionRowNetworkState(collection(), activeIsTestnet = false, dojoEnabled = true)
        )
    }

    @Test
    fun `all-unknown keys render normal`() {
        assertEquals(
            CollectionRowNetworkState.Normal,
            collectionRowNetworkState(
                collection("fake-a", "fake-b"),
                activeIsTestnet = false,
                dojoEnabled = true
            )
        )
    }

    @Test
    fun `mixed keys render normal`() {
        assertEquals(
            CollectionRowNetworkState.Normal,
            collectionRowNetworkState(
                collection("xpub", "tpub"),
                activeIsTestnet = false,
                dojoEnabled = true
            )
        )
    }

    @Test
    fun `mixed keys render normal unpaired too`() {
        assertEquals(
            CollectionRowNetworkState.Normal,
            collectionRowNetworkState(
                collection("xpub", "tpub"),
                activeIsTestnet = false,
                dojoEnabled = false
            )
        )
    }

    // match -> Normal

    @Test
    fun `testnet collection on testnet app renders normal`() {
        assertEquals(
            CollectionRowNetworkState.Normal,
            collectionRowNetworkState(
                collection("tpub", "vpub"),
                activeIsTestnet = true,
                dojoEnabled = true
            )
        )
    }

    @Test
    fun `mainnet collection on mainnet app renders normal unpaired`() {
        assertEquals(
            CollectionRowNetworkState.Normal,
            collectionRowNetworkState(
                collection("xpub", "zpub"),
                activeIsTestnet = false,
                dojoEnabled = false
            )
        )
    }

    // mismatch -> greyed, both badge variants, both directions

    @Test
    fun `testnet collection on mainnet app greys paired`() {
        assertEquals(
            CollectionRowNetworkState.GreyedPaired,
            collectionRowNetworkState(
                collection("tpub"),
                activeIsTestnet = false,
                dojoEnabled = true
            )
        )
    }

    @Test
    fun `mainnet collection on testnet app greys paired`() {
        assertEquals(
            CollectionRowNetworkState.GreyedPaired,
            collectionRowNetworkState(
                collection("xpub"),
                activeIsTestnet = true,
                dojoEnabled = true
            )
        )
    }

    @Test
    fun `testnet collection on mainnet app greys unpaired`() {
        assertEquals(
            CollectionRowNetworkState.GreyedUnpaired,
            collectionRowNetworkState(
                collection("vpub"),
                activeIsTestnet = false,
                dojoEnabled = false
            )
        )
    }

    @Test
    fun `mainnet collection on testnet app greys unpaired`() {
        assertEquals(
            CollectionRowNetworkState.GreyedUnpaired,
            collectionRowNetworkState(
                collection("ypub"),
                activeIsTestnet = true,
                dojoEnabled = false
            )
        )
    }

    // prefix coverage and case, via the derivation helper

    @Test
    fun `ypub and zpub vote mainnet`() {
        assertEquals(DerivedNetwork.MAINNET, collectionNetwork(collection("ypub", "zpub")))
    }

    @Test
    fun `upub and vpub vote testnet`() {
        assertEquals(DerivedNetwork.TESTNET, collectionNetwork(collection("upub", "vpub")))
    }

    @Test
    fun `prefixes match case-insensitively`() {
        assertEquals(DerivedNetwork.TESTNET, collectionNetwork(collection("TPUB")))
    }

    @Test
    fun `unknown prefixes abstain and yield no verdict`() {
        assertEquals(null, collectionNetwork(collection("fake-a", "fake-b")))
    }

    @Test
    fun `empty collection yields no verdict`() {
        assertEquals(null, collectionNetwork(collection()))
    }

    @Test
    fun `mixed collection yields no verdict`() {
        assertEquals(null, collectionNetwork(collection("xpub", "tpub")))
    }
}
