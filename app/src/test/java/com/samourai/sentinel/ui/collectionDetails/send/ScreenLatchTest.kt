package com.samourai.sentinel.ui.collectionDetails.send

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #385: a double tap on COMPOSE stacked two Unsigned Transaction
 * screens, because every tap started another container transform.
 * The latch admits one open until the screen is closed, so a second
 * tap is ignored and a compose after Back is a real one. The wiring
 * gate pins that same latch on the other navigation controls in the
 * send flow, and the scan sheet's existing tag guard.
 */
class ScreenLatchTest {

    @Test
    fun `the first open is admitted and a second open is ignored`() {
        val latch = ScreenLatch()
        assertTrue(latch.tryOpen())
        assertTrue(latch.isOpen)
        assertFalse(latch.tryOpen())
        assertTrue(latch.isOpen)
    }

    @Test
    fun `closing re-arms the next open`() {
        val latch = ScreenLatch()
        assertTrue(latch.tryOpen())
        assertTrue(latch.tryClose())
        assertFalse(latch.isOpen)
        // Back to the Send form, then COMPOSE again.
        assertTrue(latch.tryOpen())
        assertFalse(latch.tryOpen())
    }

    @Test
    fun `a second close is ignored and a close before any open is a no-op`() {
        val latch = ScreenLatch()
        assertFalse(latch.tryClose())
        assertFalse(latch.isOpen)
        assertTrue(latch.tryOpen())
        assertTrue(latch.tryClose())
        assertFalse(latch.tryClose())
        assertFalse(latch.isOpen)
    }

    @Test
    fun `bailing out of an open lets the next tap through`() {
        // makeTx() produced no PSBT: the caller closes, the form stays usable.
        val latch = ScreenLatch()
        assertTrue(latch.tryOpen())
        assertTrue(latch.tryClose())
        assertTrue(latch.tryOpen())
    }

    @Test
    fun `the send screen guards each navigation control once`() {
        val send = source("app/src/main/java/com/samourai/sentinel/ui/collectionDetails/send/SendFragment.kt")

        // One COMPOSE listener. The old earlier listener was dead
        // (setOnClickListener replaces) and is gone, so it cannot grow
        // back into a second unguarded transform.
        assertEquals(1, occurrences(send, "composeBtn.setOnClickListener"))
        assertTrue(send.contains("if (!unsignedScreen.tryOpen()) return@setOnClickListener"))
        assertTrue(send.contains("if (!viewModel.makeTx())"))
        assertEquals(2, occurrences(send, "unsignedScreen.tryClose()"))
        assertTrue(send.contains("private fun closeUnsignedScreen()"))
        // Toolbar close, system Back, and a reviewed input that was
        // spent or frozen. Not the definition, which is `closeUnsignedScreen() {`.
        assertEquals(3, occurrences(send, "closeUnsignedScreen()\n"))
        assertTrue(send.contains("TransitionManager.endTransitions(sceneRoot)"))

        assertTrue(send.contains("if (!broadcastScreen.tryOpen()) return@setOnClickListener"))
        assertEquals(2, occurrences(send, "broadcastScreen.tryClose()"))

        assertTrue(send.contains("if (!exportMenu.tryOpen()) return@setOnClickListener"))
        assertTrue(send.contains("popup.setOnDismissListener { _ -> exportMenu.tryClose() }"))

        // Scan sheet: the coin-picker guard, one sheet per tag. Both
        // entry points (the toolbar icon and the permission result)
        // go through it; neither shows a sheet on its own.
        assertTrue(send.contains("if (parentFragmentManager.findFragmentByTag(TAG_SCAN) != null) return"))
        assertTrue(send.contains("showScanner(REQUEST_SCAN_PAYMENT)"))
        assertTrue(send.contains("showScanner(REQUEST_SCAN_ADDRESS)"))
        assertFalse(send.contains("CameraFragmentBottomSheet.newInstance(REQUEST_SCAN_PAYMENT)"))
        assertFalse(send.contains("CameraFragmentBottomSheet.newInstance(REQUEST_SCAN_ADDRESS)"))
    }

    private fun occurrences(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1

    private fun source(relative: String): String {
        val file = sequenceOf(
            File(relative),
            File(relative.removePrefix("app/")),
            File("../$relative"),
        ).firstOrNull { it.isFile }
            ?: error("missing $relative from ${File(".").absolutePath}")
        return file.readText()
    }
}
