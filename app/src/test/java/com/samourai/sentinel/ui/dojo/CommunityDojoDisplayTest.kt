package com.samourai.sentinel.ui.dojo

import com.samourai.sentinel.core.DerivedNetwork
import com.samourai.sentinel.ui.dojo.CommunityDojoRepository.Failure
import org.junit.Assert.assertEquals
import org.junit.Test

class CommunityDojoDisplayTest {

    private val def = DojoBayConstants.BASE_URL
    private val onion = "http://" + "a".repeat(56) + ".onion"
    private fun node(id: String, network: String, name: String, status: String = "active") =
        CommunityDojoNode(
            id = id, network = network, name = name, status = status,
            payload = CommunityDojoPayload(Pairing(type = "dojo.api", version = "1.27.0", apikey = "testonly",
                url = if (network.equals("testnet", ignoreCase = true)) "$onion/test/v2" else "$onion/v2")),
        )

    private val testA = node("t1", "testnet", "b-online")
    private val testB = node("t2", "TESTNET", "a-offline", status = "inactive")
    private val testC = node("t3", "testnet", "a-online")
    private val main = node("m1", "mainnet", "main")
    private val all = listOf(testB, testA, main, testC)

    @Test
    fun `fresh list shows this network, online first, then by name`() {
        val d = CommunityDojoDisplay.afterSuccess(all, "testnet") as CommunityDojoDisplay.Fresh
        assertEquals(listOf("t3", "t1", "t2"), d.dojos.map { it.id })
    }

    @Test
    fun `nothing for this network is the empty state`() {
        assertEquals(CommunityDojoDisplay.Empty("testnet"), CommunityDojoDisplay.afterSuccess(listOf(main), "testnet"))
        // A list with Dojos, none for this network, is not "empty": it replaces the saved one.
        assertEquals(false, CommunityDojoDisplay.keepsSavedList(listOf(main)))
    }

    @Test
    fun `an empty directory keeps the saved list and says Dojo Bay lists none`() {
        assertEquals(true, CommunityDojoDisplay.keepsSavedList(emptyList()))
        val entry = CommunityDojoCache.Entry(def, 4321L, all)
        val d = CommunityDojoDisplay.afterSuccess(emptyList(), "testnet", entry) as CommunityDojoDisplay.ListedNone
        assertEquals(4321L, d.fetchedAtMs)
        assertEquals(listOf("t3", "t1", "t2"), d.dojos.map { it.id })
    }

    @Test
    fun `an empty directory with no saved list for this network is the lists-none state`() {
        assertEquals(CommunityDojoDisplay.DirectoryEmpty, CommunityDojoDisplay.afterSuccess(emptyList(), "testnet"))
        assertEquals(CommunityDojoDisplay.DirectoryEmpty,
            CommunityDojoDisplay.afterSuccess(emptyList(), "testnet", CommunityDojoCache.Entry(def, 1L, listOf(main))))
    }

    @Test
    fun `failure with a saved list shows it with its date`() {
        val entry = CommunityDojoCache.Entry(def, 1234L, all)
        val d = CommunityDojoDisplay.afterFailure(Failure.UNREACHABLE, entry, "testnet") as CommunityDojoDisplay.Cached
        assertEquals(1234L, d.fetchedAtMs)
        assertEquals(Failure.UNREACHABLE, d.failure)
        assertEquals(listOf("t3", "t1", "t2"), d.dojos.map { it.id })
    }

    @Test
    fun `failure with no saved list, or none for this network, is the failure state`() {
        assertEquals(CommunityDojoDisplay.Failed(Failure.TIMEOUT),
            CommunityDojoDisplay.afterFailure(Failure.TIMEOUT, null, "testnet"))
        val mainOnly = CommunityDojoCache.Entry(def, 1L, listOf(main))
        assertEquals(CommunityDojoDisplay.Failed(Failure.HTTP_ERROR),
            CommunityDojoDisplay.afterFailure(Failure.HTTP_ERROR, mainOnly, "testnet"))
    }

    @Test
    fun `network switch filters the saved list`() {
        val entry = CommunityDojoCache.Entry(def, 1L, all)
        val d = CommunityDojoDisplay.afterFailure(Failure.UNREACHABLE, entry, "mainnet") as CommunityDojoDisplay.Cached
        assertEquals(listOf("m1"), d.dojos.map { it.id })
    }

    @Test
    fun `network comes from the pairing URL only, never the directory label`() {
        assertEquals(DerivedNetwork.TESTNET, communityDojoNetwork("$onion/test/v2"))
        assertEquals(DerivedNetwork.MAINNET, communityDojoNetwork("$onion/v2"))
        assertEquals(DerivedNetwork.MAINNET, communityDojoNetwork("$onion/v2/"))
        assertEquals(DerivedNetwork.MAINNET, communityDojoNetwork("$onion/attest/v2"))
        assertEquals(DerivedNetwork.MAINNET, communityDojoNetwork(null))
    }

    @Test
    fun `relabelled listings stay on their URL's network, both directions`() {
        // A mainnet /v2 Dojo labelled testnet, and a /test/v2 Dojo labelled mainnet.
        val mainLabelledTest = node("x-main", "mainnet", "main labelled test").copy(network = "testnet")
        val testLabelledMain = node("x-test", "testnet", "test labelled main").copy(network = "mainnet")
        val nodes = listOf(mainLabelledTest, testLabelledMain)
        assertEquals(listOf("x-test"), (CommunityDojoDisplay.afterSuccess(nodes, "testnet") as CommunityDojoDisplay.Fresh).dojos.map { it.id })
        assertEquals(listOf("x-main"), (CommunityDojoDisplay.afterSuccess(nodes, "mainnet") as CommunityDojoDisplay.Fresh).dojos.map { it.id })
        // What the sheet hands the #250 pairing guard for each.
        assertEquals("mainnet", communityDojoNetwork(mainLabelledTest.payload?.pairing?.url).directoryName())
        assertEquals("testnet", communityDojoNetwork(testLabelledMain.payload?.pairing?.url).directoryName())
        assertEquals(PairingNetworkDecision.Refuse, pairingNetworkDecision(
            appEstablished = true, appIsTestnet = true, whisper = null,
            declared = normalizeDeclaredNetwork(communityDojoNetwork(mainLabelledTest.payload?.pairing?.url).directoryName())))
        assertEquals(PairingNetworkDecision.Refuse, pairingNetworkDecision(
            appEstablished = true, appIsTestnet = false, whisper = DerivedNetwork.TESTNET,
            declared = normalizeDeclaredNetwork(communityDojoNetwork(testLabelledMain.payload?.pairing?.url).directoryName())))
    }
}
