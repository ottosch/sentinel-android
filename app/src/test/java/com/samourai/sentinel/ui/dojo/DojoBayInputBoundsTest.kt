package com.samourai.sentinel.ui.dojo

import com.samourai.sentinel.ui.dojo.CommunityDojoRepository.Failure
import com.samourai.sentinel.ui.dojo.CommunityDojoRepository.FetchException
import com.samourai.sentinel.ui.dojo.DojoBaySignatures.NodeCheck
import com.samourai.sentinel.ui.dojo.DojoBaySignatures.OperatorCheck
import com.samourai.sentinel.ui.dojo.DojoBaySignatures.OperatorFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Bounds on what reaches the backtracking block regexes (kept as crypto.ts
 * has them) and the quadratic Base58 decode: a signed block of at most 4 KiB,
 * at most 256 listings, and a payment code's shape checked before decoding.
 */
class DojoBayInputBoundsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val alice = DojoBayTestVectors.ThrowawayPayNym(21)

    private fun millis(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    // ---- signed block size -----------------------------------------------------

    @Test
    fun `8000 BEGIN markers are an unreadable block, refused before the regex`() {
        val signed = "BEGIN BITCOIN SIGNED MESSAGE-----\n".repeat(8_000)
        val node = CommunityDojoNode(payload = DojoBayTestVectors.testPayload(), signed = signed)
        var check: NodeCheck? = null
        val ms = millis { check = DojoBaySignatures.checkNode(node) }
        assertEquals(NodeCheck.Unverified(DojoBaySignatures.Failure.UNREADABLE_BLOCK), check)
        assertTrue("took $ms ms", ms < 1_000)
        assertNull(DojoBaySignatures.parseClaimBlock(signed))
    }

    @Test
    fun `a message of 40000 newlines is refused in bounded time`() {
        val signed = alice.block("x" + "\n".repeat(40_000) + "y")
        val node = CommunityDojoNode(payload = DojoBayTestVectors.testPayload(), signed = signed)
        var check: NodeCheck? = null
        val ms = millis { check = DojoBaySignatures.checkNode(node) }
        assertEquals(NodeCheck.Unverified(DojoBaySignatures.Failure.UNREADABLE_BLOCK), check)
        assertTrue("took $ms ms", ms < 1_000)
    }

    @Test
    fun `a listing block of exactly 4 KiB still verifies, one character more is refused`() {
        val block = alice.listing(DojoBayTestVectors.TEST_PAIRING_JSON)
        // Trailing text after END changes nothing the regexes capture.
        val padded = block + " ".repeat(DojoBaySignatures.MAX_SIGNED_CHARS - block.length)
        assertEquals(4096, padded.length)
        val ok = CommunityDojoNode(payload = DojoBayTestVectors.testPayload(), signed = padded)
        assertTrue(DojoBaySignatures.checkNode(ok) is NodeCheck.Verified)
        val over = CommunityDojoNode(payload = DojoBayTestVectors.testPayload(), signed = "$padded ")
        assertEquals(NodeCheck.Unverified(DojoBaySignatures.Failure.UNREADABLE_BLOCK), DojoBaySignatures.checkNode(over))
    }

    @Test
    fun `an operator block over 4 KiB is unreadable, at 4 KiB it verifies`() {
        val base = "http://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.onion/"
        val doc = alice.operatorDoc(base)
        val padded = doc.verifySigned!! + " ".repeat(DojoBaySignatures.MAX_SIGNED_CHARS - doc.verifySigned!!.length)
        assertEquals(OperatorCheck.Verified(alice.code), DojoBaySignatures.checkOperator(doc.copy(verifySigned = padded), base))
        assertEquals(
            OperatorCheck.Failed(OperatorFailure.UNREADABLE_BLOCK),
            DojoBaySignatures.checkOperator(doc.copy(verifySigned = "$padded "), base),
        )
    }

    // ---- listing count -----------------------------------------------------------

    private fun directory(count: Int) =
        (1..count).joinToString(",", """{"nodes":[""", "]}") { """{"id":"n$it","network":"testnet","status":"active"}""" }

    @Test
    fun `a directory of 257 listings is a bad response, 256 still parse`() {
        assertEquals(256, CommunityDojoRepository.parseDirectory(directory(256)).size)
        try {
            CommunityDojoRepository.parseDirectory(directory(257))
            fail("expected BAD_RESPONSE")
        } catch (e: FetchException) {
            assertEquals(Failure.BAD_RESPONSE, e.failure)
        }
    }

    @Test
    fun `a saved list of more than 256 listings reads as no saved list`() {
        val nodes = (1..257).map { CommunityDojoNode(id = "n$it", network = "testnet", status = "active") }
        val cache = CommunityDojoCache.forDirectory(tmp.root)
        cache.write(DojoBayConstants.BASE_URL, nodes, 1L, "PM8Tcode")
        assertTrue(File(tmp.root, CommunityDojoCache.FILE_NAME).isFile)
        assertNull(cache.read(DojoBayConstants.BASE_URL))
        cache.write(DojoBayConstants.BASE_URL, nodes.take(256), 1L, "PM8Tcode")
        assertEquals(256, cache.read(DojoBayConstants.BASE_URL)!!.nodes.size)
    }

    // ---- payment code shape before Base58 ---------------------------------------

    @Test
    fun `payment code shape is checked before decoding`() {
        val code = alice.code
        assertEquals(116, code.length)
        assertEquals(2, DojoBaySignatures.notificationAddresses(code).size)
        assertEquals(2, DojoBaySignatures.notificationAddresses(" $code\n").size)
        for (bad in listOf(
            code.dropLast(1), code + "1", "PM8U" + code.drop(4), code.dropLast(1) + "0",
            code.dropLast(1) + "O", code.dropLast(1) + "I", code.dropLast(1) + "l", "",
        )) {
            assertEquals(bad, emptyList<String>(), DojoBaySignatures.notificationAddresses(bad))
        }
    }

    @Test
    fun `a 64 KiB payment code is refused at once`() {
        val huge = "PM8T" + "z".repeat(64 * 1024)
        var out: List<String>? = null
        val ms = millis { out = DojoBaySignatures.notificationAddresses(huge) }
        assertEquals(emptyList<String>(), out)
        assertTrue("took $ms ms", ms < 1_000)
    }

    @Test
    fun `a self-signed listing naming an over-long code is not verified`() {
        val long = "PM8T" + "z".repeat(400)
        val node = CommunityDojoNode(
            payload = DojoBayTestVectors.testPayload(),
            signed = alice.block(DojoBayTestVectors.TEST_PAIRING_JSON + "\n\nBIP47: " + long),
        )
        assertEquals(NodeCheck.Unverified(DojoBaySignatures.Failure.SIGNER_NOT_CODE), DojoBaySignatures.checkNode(node))
        // The same through the listed code (no BIP47 line).
        val listed = CommunityDojoNode(
            payload = DojoBayTestVectors.testPayload(), paymentCode = long,
            signed = alice.listing(DojoBayTestVectors.TEST_PAIRING_JSON, bip47Line = false),
        )
        assertEquals(NodeCheck.Unverified(DojoBaySignatures.Failure.SIGNER_NOT_CODE), DojoBaySignatures.checkNode(listed))
    }

    @Test
    fun `an operator doc naming an over-long code fails before any decode`() {
        val base = "http://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.onion/"
        val long = "PM8T" + "z".repeat(400)
        val doc = DojoBaySignatures.OperatorDoc(base, long, alice.block("$base\n\nBIP47: $long"))
        assertEquals(OperatorCheck.Failed(OperatorFailure.SIGNER_NOT_CODE), DojoBaySignatures.checkOperator(doc, base))
    }

    // ---- regex parity -------------------------------------------------------------

    @Test
    fun `the block regexes are unchanged, for parity with crypto ts`() {
        val path = "java/com/samourai/sentinel/ui/dojo/DojoBaySignatures.kt"
        val src = listOf(File("src/main/$path"), File("app/src/main/$path")).first { it.isFile }.readText()
        assertTrue(src.contains("""Regex("BEGIN BITCOIN SIGNED MESSAGE-----\\n([\\s\\S]*?)\\n-----BEGIN BITCOIN SIGNATURE")"""))
        // The tail uses JavaScript's whitespace set (JS_WS), not Java's ASCII-only \s.
        val jsWs = "\$JS_WS"
        assertTrue(src.contains("""Regex("^([\\s\\S]*?)\\n[$jsWs]*BIP47:[$jsWs]*\\n?([^$jsWs]+)$")"""))
    }
}
