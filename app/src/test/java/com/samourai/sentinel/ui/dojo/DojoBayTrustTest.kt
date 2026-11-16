package com.samourai.sentinel.ui.dojo

import com.samourai.sentinel.ui.dojo.DojoBaySignatures.OperatorCheck
import com.samourai.sentinel.ui.dojo.DojoBaySignatures.OperatorFailure
import com.samourai.sentinel.ui.dojo.DojoBayTrust.Decision
import com.samourai.sentinel.ui.dojo.DojoBayTrust.RefusalReason
import com.samourai.sentinel.ui.dojo.DojoBayTestVectors.ThrowawayPayNym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DojoBayTrustTest {

    private val custom = "http://" + "d".repeat(56) + ".onion/"
    private val def = DojoBayConstants.BASE_URL
    private val defCode = DojoBayConstants.DEFAULT_OPERATOR_CODE

    @Test
    fun `default address is pinned to the built-in code`() {
        assertEquals(Decision.Trusted(defCode), DojoBayTrust.decide(def, OperatorCheck.Verified(defCode), null))
        // A stored code never overrides the built-in one for the default address.
        assertEquals(Decision.Trusted(defCode), DojoBayTrust.decide(def, OperatorCheck.Verified(defCode), "PM8Tother"))
        assertEquals(Decision.Refused(RefusalReason.CODE_MISMATCH, "PM8Tother"),
            DojoBayTrust.decide(def, OperatorCheck.Verified("PM8Tother"), "PM8Tother"))
    }

    @Test
    fun `missing or unverified binding is refused for any address`() {
        val missing = OperatorCheck.Failed(OperatorFailure.MISSING)
        assertEquals(Decision.Refused(RefusalReason.OPERATOR_MISSING, operatorFailure = OperatorFailure.MISSING), DojoBayTrust.decide(def, missing, null))
        assertEquals(Decision.Refused(RefusalReason.OPERATOR_MISSING, operatorFailure = OperatorFailure.MISSING), DojoBayTrust.decide(custom, missing, "PM8Tx"))
        for (f in OperatorFailure.values().filter { it != OperatorFailure.MISSING }) {
            assertEquals(Decision.Refused(RefusalReason.OPERATOR_UNVERIFIED, operatorFailure = f), DojoBayTrust.decide(def, OperatorCheck.Failed(f), null))
            assertEquals(Decision.Refused(RefusalReason.OPERATOR_UNVERIFIED, operatorFailure = f), DojoBayTrust.decide(custom, OperatorCheck.Failed(f), "PM8Tx"))
        }
    }

    @Test
    fun `only a served document proving another or failed binding drops the saved list`() {
        assertFalse(DojoBayTrust.dropsSavedList(Decision.Trusted("PM8Ta")))
        assertFalse(DojoBayTrust.dropsSavedList(DojoBayTrust.decide(custom, OperatorCheck.Verified("PM8Ta"), null)))
        assertFalse(DojoBayTrust.dropsSavedList(DojoBayTrust.decide(def, OperatorCheck.Failed(OperatorFailure.MISSING), null)))
        assertFalse(DojoBayTrust.dropsSavedList(DojoBayTrust.decide(def, OperatorCheck.Failed(OperatorFailure.UNREADABLE_BLOCK), null)))
        for (f in listOf(OperatorFailure.WRONG_ADDRESS, OperatorFailure.CODE_MISMATCH, OperatorFailure.SIGNER_NOT_CODE, OperatorFailure.BAD_SIGNATURE)) {
            assertTrue(f.name, DojoBayTrust.dropsSavedList(DojoBayTrust.decide(def, OperatorCheck.Failed(f), null)))
        }
        assertTrue(DojoBayTrust.dropsSavedList(DojoBayTrust.decide(custom, OperatorCheck.Verified("PM8Tb"), "PM8Ta")))
    }

    @Test
    fun `custom address needs the entered code - no trust on first use`() {
        assertEquals(Decision.Refused(RefusalReason.NO_CODE), DojoBayTrust.decide(custom, OperatorCheck.Verified("PM8Ta"), null))
        assertEquals(Decision.Refused(RefusalReason.NO_CODE), DojoBayTrust.decide(custom, OperatorCheck.Verified("PM8Ta"), " "))
        assertEquals(Decision.Trusted("PM8Ta"), DojoBayTrust.decide(custom, OperatorCheck.Verified("PM8Ta"), "PM8Ta"))
        assertEquals(Decision.Refused(RefusalReason.CODE_MISMATCH, "PM8Tb"),
            DojoBayTrust.decide(custom, OperatorCheck.Verified("PM8Tb"), "PM8Ta"))
        // The default instance's own code is not accepted for a custom address unless entered.
        assertEquals(Decision.Refused(RefusalReason.CODE_MISMATCH, defCode),
            DojoBayTrust.decide(custom, OperatorCheck.Verified(defCode), "PM8Ta"))
    }

    @Test
    fun `entered code checked against real signed operator documents`() {
        val alice = ThrowawayPayNym(11)
        val mallory = ThrowawayPayNym(22)
        val doc = alice.operatorDoc(custom)
        assertEquals(Decision.Trusted(alice.code),
            DojoBayTrust.decide(custom, DojoBaySignatures.checkOperator(doc, custom), alice.code))
        assertEquals(Decision.Refused(RefusalReason.CODE_MISMATCH, alice.code),
            DojoBayTrust.decide(custom, DojoBaySignatures.checkOperator(doc, custom), mallory.code))
        // Mallory's instance claiming Alice's code with Mallory's key.
        val forged = DojoBaySignatures.OperatorDoc(custom, alice.code, mallory.block("$custom\n\nBIP47: ${alice.code}"))
        assertEquals(RefusalReason.OPERATOR_UNVERIFIED,
            (DojoBayTrust.decide(custom, DojoBaySignatures.checkOperator(forged, custom), alice.code) as Decision.Refused).reason)
        // The default instance's real document replayed from a custom address.
        assertEquals(RefusalReason.OPERATOR_UNVERIFIED,
            (DojoBayTrust.decide(custom, DojoBaySignatures.checkOperator(DojoBayTestVectors.REAL_OPERATOR_DOC, custom), defCode) as Decision.Refused).reason)
        assertEquals(Decision.Trusted(defCode),
            DojoBayTrust.decide(def, DojoBaySignatures.checkOperator(DojoBayTestVectors.REAL_OPERATOR_DOC, def), null))
    }

    @Test
    fun `saved list shown only from the operator still trusted`() {
        assertTrue(DojoBayTrust.cachedListTrusted(def, defCode, null))
        assertFalse(DojoBayTrust.cachedListTrusted(def, "PM8Tother", null))
        assertFalse(DojoBayTrust.cachedListTrusted(def, null, null))
        assertTrue(DojoBayTrust.cachedListTrusted(custom, "PM8Ta", "PM8Ta"))
        assertFalse(DojoBayTrust.cachedListTrusted(custom, "PM8Ta", null))
        assertFalse(DojoBayTrust.cachedListTrusted(custom, "PM8Ta", "PM8Tb"))
    }
}
