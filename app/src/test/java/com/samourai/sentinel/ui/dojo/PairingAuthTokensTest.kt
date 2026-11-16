package com.samourai.sentinel.ui.dojo

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * #567. These three bodies reach commitPairing only after
 * [readDojoAuthTokens] has already returned. A throw here is the
 * pairing sheet's failure toast, and no setting has been written yet.
 */
class PairingAuthTokensTest {

    @Test
    fun aNoteThatMentionsAuthorizationsIsNotATokenBody() {
        assertRejected("""{"note":"authorizations"}""")
    }

    @Test
    fun authorizationsMustBeAnObject() {
        assertRejected("""{"authorizations":"x"}""")
    }

    @Test
    fun aNonFiniteNumberIsRejectedBeforeAnySettingIsSaved() {
        // The unit-test jar stores 1e400 as a BigDecimal and can
        // re-serialize it. AOSP stores Infinity and toString() returns
        // null. Both are refused here, so the main thread never sees them.
        assertRejected("""{"authorizations":{"access_token":"a"},"x":[1e400]}""")
    }

    @Test
    fun aNormalLoginBodyYieldsBothTokens() {
        val tokens = readDojoAuthTokens(
            """{"authorizations":{"access_token":"access-1","refresh_token":"refresh-1"}}"""
        )
        assertEquals("access-1", tokens.accessToken)
        assertEquals("refresh-1", tokens.refreshToken)
    }

    private fun assertRejected(body: String) {
        try {
            readDojoAuthTokens(body)
            fail("expected a rejection for $body")
        } catch (e: JSONException) {
            assertNull(e.cause)
        }
    }
}
