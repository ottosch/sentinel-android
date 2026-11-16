package com.samourai.sentinel.ui.dojo

import com.samourai.sentinel.core.DerivedNetwork
import com.samourai.sentinel.ui.dojo.CommunityDojoRepository.Failure
import com.samourai.sentinel.ui.dojo.DojoBaySignatures.OperatorCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityDojoDisplayTest {

    private val alice = DojoBayTestVectors.ThrowawayPayNym(3)
    private val def = DojoBayConstants.BASE_URL
    private val defCode = DojoBayConstants.DEFAULT_OPERATOR_CODE

    private val signedTestnet = CommunityDojoNode(
        id = "t1", network = "testnet", name = "b-signed", status = "active",
        payload = DojoBayTestVectors.testPayload(), signed = alice.listing(DojoBayTestVectors.TEST_PAIRING_JSON),
    )
    private val unsignedTestnet = CommunityDojoNode(id = "t2", network = "testnet", name = "a-unsigned", status = "active",
        payload = DojoBayTestVectors.testPayload())
    private val mainnet = DojoBayTestVectors.realSeedNode()
    private val all = listOf(unsignedTestnet, signedTestnet, mainnet)

    private fun dir(nodes: List<CommunityDojoNode>) = CommunityDojoRepository.Directory(nodes, OperatorCheck.Verified(defCode))

    @Test
    fun `trusted fetch shows this network, verified first, unverified kept but marked`() {
        val d = CommunityDojoDisplay.afterSuccess(dir(all), DojoBayTrust.Decision.Trusted(defCode), "testnet")
            as CommunityDojoDisplay.Fresh
        assertEquals(listOf("t1", "t2"), d.dojos.map { it.node.id })
        assertTrue(d.dojos[0].verified != null)
        assertEquals(null, d.dojos[1].verified)
    }

    @Test
    fun `trusted fetch with nothing for this network is the empty state`() {
        assertEquals(CommunityDojoDisplay.Empty("testnet"),
            CommunityDojoDisplay.afterSuccess(dir(listOf(mainnet)), DojoBayTrust.Decision.Trusted(defCode), "testnet"))
    }

    @Test
    fun `refused or unpinned directory shows nothing from it`() {
        assertEquals(CommunityDojoDisplay.Refused(DojoBayTrust.RefusalReason.OPERATOR_UNVERIFIED, null),
            CommunityDojoDisplay.afterSuccess(dir(all), DojoBayTrust.Decision.Refused(DojoBayTrust.RefusalReason.OPERATOR_UNVERIFIED), "testnet"))
        assertEquals(CommunityDojoDisplay.Refused(DojoBayTrust.RefusalReason.CODE_MISMATCH, "PM8Tx"),
            CommunityDojoDisplay.afterSuccess(dir(all), DojoBayTrust.Decision.Refused(DojoBayTrust.RefusalReason.CODE_MISMATCH, "PM8Tx"), "testnet"))
    }

    @Test
    fun `failure with a trusted saved list shows it with its date`() {
        val entry = CommunityDojoCache.Entry(def, 1234L, all, defCode)
        val d = CommunityDojoDisplay.afterFailure(Failure.UNREACHABLE, entry, def, null, "testnet") as CommunityDojoDisplay.Cached
        assertEquals(1234L, d.fetchedAtMs)
        assertEquals(Failure.UNREACHABLE, d.failure)
        assertEquals(2, d.dojos.size)
    }

    @Test
    fun `failure with no saved list, an untrusted one, or none for this network is the failure state`() {
        assertEquals(CommunityDojoDisplay.Failed(Failure.TIMEOUT),
            CommunityDojoDisplay.afterFailure(Failure.TIMEOUT, null, def, null, "testnet"))
        val wrongOperator = CommunityDojoCache.Entry(def, 1L, all, "PM8Tother")
        assertEquals(CommunityDojoDisplay.Failed(Failure.UNREACHABLE),
            CommunityDojoDisplay.afterFailure(Failure.UNREACHABLE, wrongOperator, def, null, "testnet"))
        val mainOnly = CommunityDojoCache.Entry(def, 1L, listOf(mainnet), defCode)
        assertEquals(CommunityDojoDisplay.Failed(Failure.UNREACHABLE),
            CommunityDojoDisplay.afterFailure(Failure.UNREACHABLE, mainOnly, def, null, "testnet"))
    }

    @Test
    fun `network switch filters the saved list`() {
        val entry = CommunityDojoCache.Entry(def, 1L, all, defCode)
        val d = CommunityDojoDisplay.afterFailure(Failure.UNREACHABLE, entry, def, null, "mainnet") as CommunityDojoDisplay.Cached
        assertEquals(listOf("mainnet-maxtannahill"), d.dojos.map { it.node.id })
    }

    @Test
    fun `relabel attack - an unsigned mainnet label cannot move a signed testnet listing`() {
        // A custom directory relabels a genuinely signed .../test/v2 listing.
        val relabelled = signedTestnet.copy(id = "relabelled", network = "mainnet")
        val checked = CheckedDojo(relabelled, DojoBaySignatures.checkNode(relabelled))
        assertTrue(checked.verified != null)
        assertEquals(DerivedNetwork.TESTNET, checked.network)
        val entry = CommunityDojoCache.Entry(def, 1L, listOf(relabelled, mainnet), defCode)
        val main = CommunityDojoDisplay.afterFailure(Failure.UNREACHABLE, entry, def, null, "mainnet") as CommunityDojoDisplay.Cached
        assertEquals(listOf("mainnet-maxtannahill"), main.dojos.map { it.node.id })
        val test = CommunityDojoDisplay.afterSuccess(dir(listOf(relabelled, mainnet)), DojoBayTrust.Decision.Trusted(defCode), "testnet")
            as CommunityDojoDisplay.Fresh
        assertEquals(listOf("relabelled"), test.dojos.map { it.node.id })
        // What onSelect hands the pairing guard: an established mainnet app is refused.
        assertEquals(PairingNetworkDecision.Refuse,
            pairingNetworkDecision(appEstablished = true, appIsTestnet = false, whisper = DerivedNetwork.TESTNET, declared = checked.network))
        assertEquals(PairingNetworkDecision.ProceedAndSetNetwork(targetIsTestnet = true),
            pairingNetworkDecision(appEstablished = false, appIsTestnet = false, whisper = DerivedNetwork.TESTNET, declared = checked.network))
    }

    @Test
    fun `relabel attack - a testnet label cannot move a signed mainnet v2 listing`() {
        // A custom directory relabels Max's genuinely signed .../v2 listing.
        val relabelled = mainnet.copy(id = "relabelled-main", network = "testnet")
        val checked = CheckedDojo(relabelled, DojoBaySignatures.checkNode(relabelled))
        assertTrue(checked.verified != null)
        assertEquals(DerivedNetwork.MAINNET, checked.network)
        val d = CommunityDojoDisplay.afterSuccess(dir(listOf(relabelled, signedTestnet)), DojoBayTrust.Decision.Trusted(defCode), "testnet")
            as CommunityDojoDisplay.Fresh
        assertEquals(listOf("t1"), d.dojos.map { it.node.id })
        val m = CommunityDojoDisplay.afterSuccess(dir(listOf(relabelled, signedTestnet)), DojoBayTrust.Decision.Trusted(defCode), "mainnet")
            as CommunityDojoDisplay.Fresh
        assertEquals(listOf("relabelled-main"), m.dojos.map { it.node.id })
        // What onSelect hands the pairing guard: an established testnet app is refused, not waved through.
        assertEquals(PairingNetworkDecision.Refuse, pairingNetworkDecision(appEstablished = true, appIsTestnet = true,
            whisper = null, declared = normalizeDeclaredNetwork(checked.network.directoryName())))
        assertEquals(PairingNetworkDecision.ProceedAndSetNetwork(targetIsTestnet = false), pairingNetworkDecision(appEstablished = false,
            appIsTestnet = true, whisper = null, declared = normalizeDeclaredNetwork(checked.network.directoryName())))
    }

    @Test
    fun `listing network - from the URL only, the label is ignored`() {
        val testUrl = "http://" + "a".repeat(56) + ".onion/test/v2"
        val mainUrl = "http://" + "a".repeat(56) + ".onion/v2"
        assertEquals(DerivedNetwork.TESTNET, communityDojoNetwork(testUrl))
        assertEquals(DerivedNetwork.MAINNET, communityDojoNetwork(mainUrl))
        assertEquals(DerivedNetwork.MAINNET, communityDojoNetwork("$mainUrl/"))
        assertEquals(DerivedNetwork.MAINNET, communityDojoNetwork(null))
        for (label in listOf("mainnet", "testnet", "signet", null)) {
            val main = mainnet.copy(network = label)
            assertEquals("label $label", DerivedNetwork.MAINNET, CheckedDojo(main, DojoBaySignatures.checkNode(main)).network)
            val test = signedTestnet.copy(network = label)
            assertEquals("label $label", DerivedNetwork.TESTNET, CheckedDojo(test, DojoBaySignatures.checkNode(test)).network)
        }
    }

    @Test
    fun `the signed URL, not the listed one, decides a verified listing's network`() {
        // Listed payload says .../v2 (mainnet-looking) but must equal the
        // signed one to verify, so a mismatch is simply unverified; for an
        // unverified listing the listed URL is the only evidence.
        val listedMain = unsignedTestnet.copy(network = "testnet", payload = CommunityDojoPayload(
            pairing = DojoBayTestVectors.testPayload().pairing!!.copy(url = "http://" + "a".repeat(56) + ".onion/v2")))
        val checked = CheckedDojo(listedMain, DojoBaySignatures.checkNode(listedMain))
        assertEquals(null, checked.verified)
        assertEquals(DerivedNetwork.MAINNET, checked.network)
    }

    @Test
    fun `operator 404 with a saved list shows the saved list, flagged as no operator statement`() {
        val entry = CommunityDojoCache.Entry(def, 99L, all, defCode)
        val d = CommunityDojoDisplay.afterFailure(Failure.NO_OPERATOR_STATEMENT, entry, def, null, "testnet") as CommunityDojoDisplay.Cached
        assertEquals(Failure.NO_OPERATOR_STATEMENT, d.failure)
        assertEquals(listOf("t1", "t2"), d.dojos.map { it.node.id })
    }

    @Test
    fun `operator 503 with a saved list shows the saved list`() {
        val entry = CommunityDojoCache.Entry(def, 99L, all, defCode)
        val d = CommunityDojoDisplay.afterFailure(Failure.HTTP_ERROR, entry, def, null, "testnet") as CommunityDojoDisplay.Cached
        assertEquals(99L, d.fetchedAtMs)
        assertEquals(Failure.HTTP_ERROR, d.failure)
        assertEquals(listOf("t1", "t2"), d.dojos.map { it.node.id })
    }

    @Test
    fun `a verified empty directory keeps the saved list and says Dojo Bay lists none`() {
        assertTrue(CommunityDojoDisplay.keepsSavedList(emptyList()))
        val entry = CommunityDojoCache.Entry(def, 4321L, all, defCode)
        val d = CommunityDojoDisplay.afterSuccess(dir(emptyList()), DojoBayTrust.Decision.Trusted(defCode), "testnet", entry)
            as CommunityDojoDisplay.ListedNone
        assertEquals(4321L, d.fetchedAtMs)
        assertEquals(listOf("t1", "t2"), d.dojos.map { it.node.id })
        assertTrue(d.dojos[0].verified != null)
    }

    @Test
    fun `a verified empty directory with no usable saved list is the lists-none state`() {
        val trusted = DojoBayTrust.Decision.Trusted(defCode)
        assertEquals(CommunityDojoDisplay.DirectoryEmpty,
            CommunityDojoDisplay.afterSuccess(dir(emptyList()), trusted, "testnet"))
        // Saved under another operator, or nothing for this network.
        assertEquals(CommunityDojoDisplay.DirectoryEmpty, CommunityDojoDisplay.afterSuccess(dir(emptyList()), trusted, "testnet",
            CommunityDojoCache.Entry(def, 1L, all, "PM8Tother")))
        assertEquals(CommunityDojoDisplay.DirectoryEmpty, CommunityDojoDisplay.afterSuccess(dir(emptyList()), trusted, "testnet",
            CommunityDojoCache.Entry(def, 1L, listOf(mainnet), defCode)))
        // A refused empty directory is still a refusal.
        val refused = DojoBayTrust.Decision.Refused(DojoBayTrust.RefusalReason.OPERATOR_MISSING, null)
        assertTrue(CommunityDojoDisplay.afterSuccess(dir(emptyList()), refused, "testnet",
            CommunityDojoCache.Entry(def, 1L, all, defCode)) is CommunityDojoDisplay.Refused)
    }
}
