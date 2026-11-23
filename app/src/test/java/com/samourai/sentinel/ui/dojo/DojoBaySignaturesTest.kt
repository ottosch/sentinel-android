package com.samourai.sentinel.ui.dojo

import com.samourai.sentinel.helpers.fromJSON
import com.samourai.sentinel.ui.dojo.DojoBaySignatures.Failure
import com.samourai.sentinel.ui.dojo.DojoBaySignatures.NodeCheck
import com.samourai.sentinel.ui.dojo.DojoBaySignatures.OperatorCheck
import com.samourai.sentinel.ui.dojo.DojoBaySignatures.OperatorFailure
import com.samourai.sentinel.ui.dojo.DojoBayTestVectors.ThrowawayPayNym
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Utils
import org.bouncycastle.util.encoders.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * Valid, tampered, wrong-code, missing-signature, and replay vectors for the
 * client-side check of Dojo Bay's BIP47 signatures (format: Dojo Bay
 * server/crypto.ts at a3125f5).
 */
class DojoBaySignaturesTest {

    private val alice = ThrowawayPayNym(1)
    private val mallory = ThrowawayPayNym(101)

    private fun failure(node: CommunityDojoNode) = (DojoBaySignatures.checkNode(node) as NodeCheck.Unverified).failure

    // ---- primitives -------------------------------------------------------

    @Test
    fun `real operator code derives the address its blocks are signed from`() {
        val addrs = DojoBaySignatures.notificationAddresses(DojoBayTestVectors.REAL_OPERATOR_CODE)
        assertEquals(DojoBayTestVectors.REAL_OPERATOR_ADDRESS, addrs.first())
        assertEquals(2, addrs.size) // mainnet and testnet derivations, like notificationAddresses()
        assertTrue(addrs[1].first() in "mn")
    }

    @Test
    fun `throwaway paynym signs from its notification address`() {
        assertEquals(alice.address, DojoBaySignatures.notificationAddresses(alice.code).first())
        assertTrue(alice.code.startsWith("PM8T"))
    }

    @Test
    fun `invalid payment codes derive nothing`() {
        assertTrue(DojoBaySignatures.notificationAddresses(null).isEmpty())
        assertTrue(DojoBaySignatures.notificationAddresses("PM8Tnotacode").isEmpty())
    }

    @Test
    fun `HIGH-S signature is accepted, like Dojo Bay`() {
        val message = DojoBayTestVectors.TEST_PAIRING_JSON + "\n\nBIP47: " + alice.code
        val low = alice.notificationKey.signMessage(message)
        val high = toHighS(low)
        assertTrue(high != low)
        assertTrue(DojoBaySignatures.verifyMessage(message, alice.address, high))

        // End to end: a listing carrying the high-S form still verifies.
        val block = "-----BEGIN BITCOIN SIGNED MESSAGE-----\n$message\n" +
            "-----BEGIN BITCOIN SIGNATURE-----\nVersion: Bitcoin-qt (1.0)\n" +
            "Address: ${alice.address}\n\n$high\n" +
            "-----END BITCOIN SIGNATURE-----"
        val node = CommunityDojoNode(payload = DojoBayTestVectors.testPayload(), signed = block)
        assertTrue(DojoBaySignatures.checkNode(node) is NodeCheck.Verified)
    }

    @Test
    fun `MALFORMED r and s are refused without throwing`() {
        val n = ECKey.CURVE.n
        val one = BigInteger.ONE
        // r = 0, r >= n, and an in-range value that is not a curve x-coordinate.
        for (r in listOf(BigInteger.ZERO, n, n.add(BigInteger.valueOf(2)), BigInteger.valueOf(5))) {
            assertFalse("r=$r", DojoBaySignatures.verifyMessage("m", alice.address, encodeSignature(31, r, one)))
        }
        // s = 0 and s >= n.
        for (s in listOf(BigInteger.ZERO, n)) {
            assertFalse("s=$s", DojoBaySignatures.verifyMessage("m", alice.address, encodeSignature(31, one, s)))
        }
    }

    // ---- listings: valid --------------------------------------------------

    @Test
    fun `VALID real seed block verifies and pairs with the signed payload`() {
        val check = DojoBaySignatures.checkNode(DojoBayTestVectors.realSeedNode(version = "1.27.0")) as NodeCheck.Verified
        assertEquals(DojoBayTestVectors.REAL_OPERATOR_CODE, check.paymentCode)
        assertFalse(check.versionDrift)
        val paired = fromJSON<DojoPairing>(check.signedPayloadJson)!!
        assertEquals("jaf8fQuGD3QBWLjso6BqU4GEFZ8rW77hXGJfpXNq", paired.pairing!!.apikey)
        assertEquals("1.27.0", paired.pairing!!.version)
    }

    @Test
    fun `VALID real seed with a later version in the listing verifies with drift and pairs the signed version`() {
        val check = DojoBaySignatures.checkNode(DojoBayTestVectors.realSeedNode(version = "1.28.0")) as NodeCheck.Verified
        assertTrue(check.versionDrift)
        assertEquals("1.27.0", fromJSON<DojoPairing>(check.signedPayloadJson)!!.pairing!!.version)
    }

    @Test
    fun `VALID synthetic listing with BIP47 line, either line layout`() {
        val a = CommunityDojoNode(payload = DojoBayTestVectors.testPayload(), paymentCode = "ignored-label",
            signed = alice.listing(DojoBayTestVectors.TEST_PAIRING_JSON))
        assertEquals(alice.code, (DojoBaySignatures.checkNode(a) as NodeCheck.Verified).paymentCode)
        val b = a.copy(signed = alice.block(DojoBayTestVectors.TEST_PAIRING_JSON + "\n\nBIP47:\n" + alice.code))
        assertEquals(alice.code, (DojoBaySignatures.checkNode(b) as NodeCheck.Verified).paymentCode)
    }

    @Test
    fun `VALID listing without BIP47 line verifies against the listed code`() {
        val node = CommunityDojoNode(payload = DojoBayTestVectors.testPayload(), paymentCode = alice.code,
            signed = alice.listing(DojoBayTestVectors.TEST_PAIRING_JSON, bip47Line = false))
        assertEquals(alice.code, (DojoBaySignatures.checkNode(node) as NodeCheck.Verified).paymentCode)
    }

    @Test
    fun `VALID key order and whitespace in the signed JSON do not matter`() {
        val pretty = "{\n  \"pairing\": {\"url\": \"http://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.onion/test/v2\"," +
            " \"apikey\": \"testonly-apikey\", \"version\": \"1.27.0\", \"type\": \"dojo.api\"}\n}"
        val node = CommunityDojoNode(payload = DojoBayTestVectors.testPayload(), signed = alice.listing(pretty))
        assertTrue(DojoBaySignatures.checkNode(node) is NodeCheck.Verified)
    }

    // ---- listings: tampered -----------------------------------------------

    @Test
    fun `TAMPERED listing payload (directory swaps the api key) is refused`() {
        val node = DojoBayTestVectors.realSeedNode(version = "1.27.0").copy(
            payload = DojoBayTestVectors.realSeedPayload().let { it.copy(pairing = it.pairing!!.copy(apikey = "attacker")) },
        )
        assertEquals(Failure.PAYLOAD_MISMATCH, failure(node))
    }

    @Test
    fun `TAMPERED listing endpoint (directory swaps the onion) is refused`() {
        val node = CommunityDojoNode(
            payload = DojoBayTestVectors.testPayload().let {
                it.copy(pairing = it.pairing!!.copy(url = "http://" + "b".repeat(56) + ".onion/v2"))
            },
            signed = alice.listing(DojoBayTestVectors.TEST_PAIRING_JSON),
        )
        assertEquals(Failure.PAYLOAD_MISMATCH, failure(node))
    }

    @Test
    fun `TAMPERED signed text (one byte changed inside the block) fails the signature`() {
        val block = DojoBayTestVectors.REAL_SEED_SIGNED.replace("jaf8fQuGD3", "jaf8fQuGD4")
        val node = DojoBayTestVectors.realSeedNode(version = "1.27.0").copy(
            payload = DojoBayTestVectors.realSeedPayload().let { it.copy(pairing = it.pairing!!.copy(apikey = "jaf8fQuGD4QBWLjso6BqU4GEFZ8rW77hXGJfpXNq")) },
            signed = block,
        )
        assertEquals(Failure.BAD_SIGNATURE, failure(node))
    }

    @Test
    fun `TAMPERED signature bytes fail`() {
        val block = DojoBayTestVectors.REAL_SEED_SIGNED.replace("H6BZzINZjJQz", "H6BZzINZjJQy")
        assertEquals(Failure.BAD_SIGNATURE, failure(DojoBayTestVectors.realSeedNode().copy(signed = block)))
    }

    @Test
    fun `TAMPERED signed text that is not a pairing is refused`() {
        val node = CommunityDojoNode(payload = DojoBayTestVectors.testPayload(), signed = alice.listing("{\"hello\":1}"))
        assertEquals(Failure.NOT_A_PAIRING, failure(node))
    }

    // ---- listings: wrong code ---------------------------------------------

    @Test
    fun `WRONG CODE BIP47 line names someone else than the signer`() {
        // Mallory signs, but writes Alice's code into the signed text.
        val node = CommunityDojoNode(payload = DojoBayTestVectors.testPayload(),
            signed = mallory.block(DojoBayTestVectors.TEST_PAIRING_JSON + "\n\nBIP47: " + alice.code))
        assertEquals(Failure.SIGNER_NOT_CODE, failure(node))
    }

    @Test
    fun `WRONG CODE no BIP47 line and the listed code is not the signer`() {
        val node = CommunityDojoNode(payload = DojoBayTestVectors.testPayload(), paymentCode = alice.code,
            signed = mallory.listing(DojoBayTestVectors.TEST_PAIRING_JSON, bip47Line = false))
        assertEquals(Failure.SIGNER_NOT_CODE, failure(node))
    }

    @Test
    fun `WRONG CODE the address line is swapped to the claimed owner`() {
        val block = mallory.listing(DojoBayTestVectors.TEST_PAIRING_JSON).replace(mallory.address, alice.address)
        val node = CommunityDojoNode(payload = DojoBayTestVectors.testPayload(), signed = block)
        assertEquals(Failure.BAD_SIGNATURE, failure(node))
    }

    @Test
    fun `the directory's own paymentCode label is not what is shown`() {
        val node = CommunityDojoNode(payload = DojoBayTestVectors.testPayload(), paymentCode = alice.code,
            signed = mallory.listing(DojoBayTestVectors.TEST_PAIRING_JSON))
        assertEquals(mallory.code, (DojoBaySignatures.checkNode(node) as NodeCheck.Verified).paymentCode)
    }

    // ---- listings: missing --------------------------------------------------

    @Test
    fun `MISSING signature, blank, or unreadable block`() {
        assertEquals(Failure.MISSING_SIGNATURE, failure(DojoBayTestVectors.realSeedNode(signed = null)))
        assertEquals(Failure.MISSING_SIGNATURE, failure(DojoBayTestVectors.realSeedNode(signed = "  ")))
        assertEquals(Failure.UNREADABLE_BLOCK, failure(DojoBayTestVectors.realSeedNode(signed = "signed by me, honest")))
        val truncated = DojoBayTestVectors.REAL_SEED_SIGNED.substringBefore("-----END")
        assertEquals(Failure.UNREADABLE_BLOCK, failure(DojoBayTestVectors.realSeedNode(signed = truncated)))
    }

    // ---- listings: replay ----------------------------------------------------

    @Test
    fun `REPLAY one owner's signed block attached to another listing's details is refused`() {
        val node = CommunityDojoNode(payload = DojoBayTestVectors.testPayload(), signed = DojoBayTestVectors.REAL_SEED_SIGNED)
        assertEquals(Failure.PAYLOAD_MISMATCH, failure(node))
    }

    @Test
    fun `REPLAY limit - a genuine block relisted under another name still shows the real signer`() {
        // Dojo Bay signs no name, timestamp or nonce, so a relisting cannot be
        // refused; what the app guarantees is that it names the true signer
        // and pairs with exactly what that signer signed.
        val node = DojoBayTestVectors.realSeedNode(version = "1.27.0").copy(name = "Totally different Dojo", paymentCode = alice.code)
        val check = DojoBaySignatures.checkNode(node) as NodeCheck.Verified
        assertEquals(DojoBayTestVectors.REAL_OPERATOR_CODE, check.paymentCode)
    }

    // ---- operator binding ---------------------------------------------------

    @Test
    fun `VALID real operator json verifies for the default address`() {
        val check = DojoBaySignatures.checkOperator(DojoBayTestVectors.REAL_OPERATOR_DOC, DojoBayConstants.BASE_URL)
        assertEquals(OperatorCheck.Verified(DojoBayConstants.DEFAULT_OPERATOR_CODE), check)
    }

    @Test
    fun `VALID trailing slash is ignored but case is not, like Dojo Bay's norm`() {
        val noSlash = DojoBaySignatures.checkOperator(DojoBayTestVectors.REAL_OPERATOR_DOC, DojoBayConstants.BASE_URL.trimEnd('/'))
        assertTrue(noSlash is OperatorCheck.Verified)
        assertEquals(OperatorCheck.Failed(OperatorFailure.WRONG_ADDRESS),
            DojoBaySignatures.checkOperator(DojoBayTestVectors.REAL_OPERATOR_DOC, DojoBayConstants.BASE_URL.uppercase()))
    }

    @Test
    fun `REPLAY real operator json served from another address is refused`() {
        val other = "http://" + "c".repeat(56) + ".onion/"
        assertEquals(OperatorCheck.Failed(OperatorFailure.WRONG_ADDRESS),
            DojoBaySignatures.checkOperator(DojoBayTestVectors.REAL_OPERATOR_DOC, other))
        // ...even if the copy's "onion" field is rewritten to match.
        assertEquals(OperatorCheck.Failed(OperatorFailure.WRONG_ADDRESS),
            DojoBaySignatures.checkOperator(DojoBayTestVectors.REAL_OPERATOR_DOC.copy(onion = other), other))
    }

    @Test
    fun `WRONG CODE operator doc claims a code that did not sign it`() {
        val onion = "http://" + "c".repeat(56) + ".onion/"
        val doc = mallory.operatorDoc(onion)
        assertEquals(OperatorCheck.Failed(OperatorFailure.CODE_MISMATCH),
            DojoBaySignatures.checkOperator(doc.copy(paymentCode = alice.code), onion))
        val forged = mallory.block("$onion\n\nBIP47: ${alice.code}")
        assertEquals(OperatorCheck.Failed(OperatorFailure.SIGNER_NOT_CODE),
            DojoBaySignatures.checkOperator(DojoBaySignatures.OperatorDoc(onion, alice.code, forged), onion))
    }

    @Test
    fun `TAMPERED operator signature fails`() {
        val doc = DojoBayTestVectors.REAL_OPERATOR_DOC.let {
            it.copy(verifySigned = it.verifySigned!!.replace("IDgHOPHUYpef", "IDgHOPHUYpeg"))
        }
        assertEquals(OperatorCheck.Failed(OperatorFailure.BAD_SIGNATURE), DojoBaySignatures.checkOperator(doc, DojoBayConstants.BASE_URL))
    }

    @Test
    fun `MISSING operator doc or block`() {
        assertEquals(OperatorCheck.Failed(OperatorFailure.MISSING), DojoBaySignatures.checkOperator(null, DojoBayConstants.BASE_URL))
        assertEquals(OperatorCheck.Failed(OperatorFailure.MISSING),
            DojoBaySignatures.checkOperator(DojoBayTestVectors.REAL_OPERATOR_DOC.copy(verifySigned = null), DojoBayConstants.BASE_URL))
        assertEquals(OperatorCheck.Failed(OperatorFailure.UNREADABLE_BLOCK),
            DojoBaySignatures.checkOperator(DojoBayTestVectors.REAL_OPERATOR_DOC.copy(verifySigned = "x"), DojoBayConstants.BASE_URL))
    }

    @Test
    fun `VALID synthetic operator doc for a custom address`() {
        val onion = "http://" + "d".repeat(56) + ".onion/"
        assertEquals(OperatorCheck.Verified(alice.code), DojoBaySignatures.checkOperator(alice.operatorDoc(onion), onion))
    }

    private fun toHighS(signatureBase64: String): String {
        val bytes = Base64.decode(signatureBase64)
        val header = bytes[0].toInt() and 0xFF
        val compressed = header >= 31
        val recId = (if (compressed) header - 4 else header) - 27
        val r = BigInteger(1, bytes.copyOfRange(1, 33))
        val s = BigInteger(1, bytes.copyOfRange(33, 65))
        // Negating s also flips the y parity of R, hence the recovery id.
        return encodeSignature(27 + (recId xor 1) + (if (compressed) 4 else 0), r, ECKey.CURVE.n.subtract(s))
    }

    private fun encodeSignature(header: Int, r: BigInteger, s: BigInteger): String {
        val encoded = ByteArray(65)
        encoded[0] = header.toByte()
        Utils.bigIntegerToBytes(r, 32).copyInto(encoded, 1)
        Utils.bigIntegerToBytes(s, 32).copyInto(encoded, 33)
        return String(Base64.encode(encoded), Charsets.US_ASCII)
    }
}
