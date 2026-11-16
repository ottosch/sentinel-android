package com.samourai.sentinel.ui.webview

import com.samourai.sentinel.ui.webview.PayNymLoadEvent.BLOCKED
import com.samourai.sentinel.ui.webview.PayNymLoadEvent.LOAD_STARTED
import com.samourai.sentinel.ui.webview.PayNymLoadEvent.MAIN_FRAME_ERROR
import com.samourai.sentinel.ui.webview.PayNymLoadEvent.PAGE_SHOWN
import com.samourai.sentinel.ui.webview.PayNymLoadEvent.TIMED_OUT
import com.samourai.sentinel.ui.webview.PayNymLoadEvent.WAIT_FOR_TOR
import com.samourai.sentinel.ui.webview.PayNymLoadPhase.FAILED
import com.samourai.sentinel.ui.webview.PayNymLoadPhase.IDLE
import com.samourai.sentinel.ui.webview.PayNymLoadPhase.LOADED
import com.samourai.sentinel.ui.webview.PayNymLoadPhase.LOADING
import com.samourai.sentinel.ui.webview.PayNymLoadPhase.WAITING_FOR_TOR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The PayNym page's load phases, time limits and which errors fail it. */
class PayNymLoadStateTest {

    private fun run(vararg events: PayNymLoadEvent): PayNymLoadPhase =
        events.fold(IDLE) { phase, e -> PayNymLoad.next(phase, e) }

    @Test
    fun `waiting for Tor, then loading, then shown`() {
        assertEquals(WAITING_FOR_TOR, run(WAIT_FOR_TOR))
        assertEquals(LOADING, run(WAIT_FOR_TOR, LOAD_STARTED))
        assertEquals(LOADED, run(WAIT_FOR_TOR, LOAD_STARTED, PAGE_SHOWN))
        // Tor already ready: straight to loading.
        assertEquals(LOADED, run(LOAD_STARTED, PAGE_SHOWN))
    }

    @Test
    fun `waiting or loading too long fails, nothing else times out`() {
        assertEquals(FAILED, run(WAIT_FOR_TOR, TIMED_OUT))
        assertEquals(FAILED, run(LOAD_STARTED, TIMED_OUT))
        assertEquals(LOADED, run(LOAD_STARTED, PAGE_SHOWN, TIMED_OUT))
        assertEquals(IDLE, run(TIMED_OUT))
        assertEquals(FAILED, run(LOAD_STARTED, TIMED_OUT, TIMED_OUT))
    }

    @Test
    fun `the time limits are 90 s for Tor and 60 s for the page, and only while waiting or loading`() {
        assertEquals(90_000L, PayNymLoad.TOR_WAIT_LIMIT_MS)
        assertEquals(60_000L, PayNymLoad.PAGE_LOAD_LIMIT_MS)
        assertEquals(PayNymLoad.TOR_WAIT_LIMIT_MS, PayNymLoad.limitFor(WAITING_FOR_TOR))
        assertEquals(PayNymLoad.PAGE_LOAD_LIMIT_MS, PayNymLoad.limitFor(LOADING))
        for (p in listOf(IDLE, LOADED, FAILED)) assertNull(p.name, PayNymLoad.limitFor(p))
    }

    @Test
    fun `a main-frame error fails a loading or shown page, and Chromium's error page doesn't undo it`() {
        assertEquals(FAILED, run(LOAD_STARTED, MAIN_FRAME_ERROR))
        assertEquals(FAILED, run(LOAD_STARTED, PAGE_SHOWN, MAIN_FRAME_ERROR))
        // Chromium commits its own error page after the error: still failed.
        assertEquals(FAILED, run(LOAD_STARTED, MAIN_FRAME_ERROR, PAGE_SHOWN))
        // Nothing was loading: nothing to fail.
        assertEquals(WAITING_FOR_TOR, run(WAIT_FOR_TOR, MAIN_FRAME_ERROR))
        assertEquals(IDLE, run(MAIN_FRAME_ERROR))
    }

    @Test
    fun `retry goes back through routing, and a block dialog clears the state`() {
        assertEquals(WAITING_FOR_TOR, run(LOAD_STARTED, TIMED_OUT, WAIT_FOR_TOR))
        assertEquals(LOADING, run(WAIT_FOR_TOR, TIMED_OUT, LOAD_STARTED))
        for (start in listOf(WAIT_FOR_TOR, LOAD_STARTED)) assertEquals(IDLE, run(start, BLOCKED))
        assertEquals(IDLE, run(LOAD_STARTED, TIMED_OUT, BLOCKED))
    }

    @Test
    fun `only main-frame errors fail the page, and not a request the app refused`() {
        // Network errors (no status).
        assertTrue(PayNymLoad.failsPage(isForMainFrame = true, httpStatus = null, blockedByApp = false))
        assertFalse(PayNymLoad.failsPage(isForMainFrame = false, httpStatus = null, blockedByApp = false))
        assertFalse(PayNymLoad.failsPage(isForMainFrame = true, httpStatus = null, blockedByApp = true))
        // HTTP: 4xx and 5xx on the main frame only.
        for (s in listOf(400, 403, 404, 500, 502, 503, 504)) {
            assertTrue("$s", PayNymLoad.failsPage(true, s, false))
            assertFalse("$s", PayNymLoad.failsPage(false, s, false))
        }
        for (s in listOf(200, 204, 301, 304, 399)) assertFalse("$s", PayNymLoad.failsPage(true, s, false))
        // The app's own 403 for an onion off Tor gets the block dialog instead.
        assertFalse(PayNymLoad.failsPage(true, 403, blockedByApp = true))
    }
}
