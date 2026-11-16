package com.samourai.sentinel.ui.dojo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CommunityDojoCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val nodes = listOf(
        CommunityDojoNode(id = "t1", network = "testnet", name = "Test", status = "active", signed = "block"),
        CommunityDojoNode(id = "m1", network = "mainnet", name = "Main", status = "inactive"),
    )

    @Test
    fun `round trip keeps nodes, time and operator`() {
        val cache = CommunityDojoCache.forDirectory(tmp.root)
        cache.write(DojoBayConstants.BASE_URL, nodes, 1_700_000_000_000L, "PM8Tcode")
        val e = cache.read(DojoBayConstants.BASE_URL)!!
        assertEquals(nodes, e.nodes)
        assertEquals(1_700_000_000_000L, e.fetchedAtMs)
        assertEquals("PM8Tcode", e.operatorCode)
    }

    @Test
    fun `a list saved for another address is not returned`() {
        val cache = CommunityDojoCache.forDirectory(tmp.root)
        cache.write("http://other.onion/", nodes, 1L, "PM8Tcode")
        assertNull(cache.read(DojoBayConstants.BASE_URL))
    }

    @Test
    fun `corrupt or missing file reads as no cache`() {
        val cache = CommunityDojoCache.forDirectory(tmp.root)
        assertNull(cache.read(DojoBayConstants.BASE_URL))
        File(tmp.root, CommunityDojoCache.FILE_NAME).writeText("{not json")
        assertNull(cache.read(DojoBayConstants.BASE_URL))
        File(tmp.root, CommunityDojoCache.FILE_NAME).writeText("{}")
        assertNull(cache.read(DojoBayConstants.BASE_URL))
    }

    @Test
    fun `clear removes the file`() {
        val cache = CommunityDojoCache.forDirectory(tmp.root)
        cache.write(DojoBayConstants.BASE_URL, nodes, 1L, "PM8Tcode")
        cache.clear()
        assertFalse(File(tmp.root, CommunityDojoCache.FILE_NAME).exists())
        assertNull(cache.read(DojoBayConstants.BASE_URL))
    }

    @Test
    fun `saved list keeps each payload exactly as served, so checks give the same verdict`() {
        val alice = DojoBayTestVectors.ThrowawayPayNym(5)
        val signedText = DojoBayTestVectors.TEST_PAIRING_JSON
        val signed = alice.listing(signedText)
        val payload = signedText.removePrefix("{").removeSuffix("}")
        fun node(id: String, payloadJson: String) =
            """{"id":"$id","network":"testnet","status":"active","paymentCode":"${alice.code}",""" +
                """"payload":$payloadJson,"signed":${com.google.gson.JsonPrimitive(signed)}}"""
        val withExtra = signedText.dropLast(2) + ""","extra":1}}"""
        val served = CommunityDojoRepository.parseDirectory(
            """{"nodes":[${node("ok", "{$payload}")},${node("extra", withExtra)}]}"""
        )
        val before = served.map { DojoBaySignatures.checkNode(it) }
        assertTrue(before[0] is DojoBaySignatures.NodeCheck.Verified)
        assertEquals(DojoBaySignatures.NodeCheck.Unverified(DojoBaySignatures.Failure.PAYLOAD_MISMATCH), before[1])
        val cache = CommunityDojoCache.forDirectory(tmp.root)
        cache.write(DojoBayConstants.BASE_URL, served, 1L, "PM8Tcode")
        val after = cache.read(DojoBayConstants.BASE_URL)!!.nodes.map { DojoBaySignatures.checkNode(it) }
        assertEquals(before, after)
    }

    @Test
    fun `an empty list never replaces the saved one`() {
        val cache = CommunityDojoCache.forDirectory(tmp.root)
        cache.write(DojoBayConstants.BASE_URL, emptyList(), 5L, "PM8Tcode")
        assertNull(cache.read(DojoBayConstants.BASE_URL))
        cache.write(DojoBayConstants.BASE_URL, nodes, 7L, "PM8Tcode")
        cache.write(DojoBayConstants.BASE_URL, emptyList(), 9L, "PM8Tcode")
        val e = cache.read(DojoBayConstants.BASE_URL)!!
        assertEquals(nodes, e.nodes)
        assertEquals(7L, e.fetchedAtMs)
    }
}
