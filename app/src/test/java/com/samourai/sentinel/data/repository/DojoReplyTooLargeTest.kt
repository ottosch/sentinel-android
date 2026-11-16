package com.samourai.sentinel.data.repository

import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.ui.home.SyncRoundLedger
import com.samourai.sentinel.ui.home.SyncRoundTerminal
import com.samourai.sentinel.util.ImportTooLargeException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #567. A /wallet body over the cap is not a dead onion. ImportTooLargeException
 * is an IOException, and that used to be recorded as transport, which
 * shows "Could not reach Dojo" and then skips the rest of the round.
 */
class DojoReplyTooLargeTest {

    @Test
    fun anOversizedDojoReplyIsNotTransport() {
        val tooLarge = ApiService.DojoReplyTooLarge()
        assertEquals("Dojo reply too large", tooLarge.message)
        assertEquals(SyncFailureKind.OVERSIZE, syncFailureKind(tooLarge))
        assertEquals(SyncFailureKind.TRANSPORT, syncFailureKind(ImportTooLargeException(1, "bytes")))

        val ledger = SyncRoundLedger()
        ledger.recordThrown("collection", tooLarge)
        assertEquals(
            SyncRoundTerminal.DOJO_REPLY_TOO_LARGE,
            SyncRoundTerminal.failureReason(1, 1, ledger.tally()),
        )
        assertEquals(
            SyncRoundTerminal.DOJO_UNREACHABLE,
            SyncRoundTerminal.failureReason(
                1,
                1,
                ledger.tally().copy(transport = 1, oversize = 0),
            ),
        )
    }
}
