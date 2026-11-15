package com.samourai.sentinel.ui.dojo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Block Explorer row names the bound onion itself. The operator
 * is a second line, and only when this pairing has a directory
 * snapshot. The Dojo row does not carry either.
 */
class PairedExplorerOperatorTest {

    private val pairingUrl = "http://community.example"
    private val unnamed = "Unnamed Dojo"
    private val snapshot = DojoDisplaySnapshot(
        url = pairingUrl,
        name = "Directory Dojo",
        flag = "SG",
        jurisdiction = "Singapore",
    )

    @Test
    fun `a community snapshot is the operator line and never an onion`() {
        val operator = pairedExplorerOperator(pairingUrl, snapshot, unnamed)
        assertEquals(pairingIdentityLine(pairingUrl, snapshot, unnamed), operator)
        assertEquals("SG Directory Dojo", operator)
        assertFalse(operator!!.contains(".onion"))
    }

    @Test
    fun `a nameless directory node uses the unnamed Dojo label`() {
        assertEquals(
            "SG Unnamed Dojo",
            pairedExplorerOperator(pairingUrl, snapshot.copy(name = null), unnamed),
        )
        assertEquals(
            "Directory Dojo",
            pairedExplorerOperator(pairingUrl, snapshot.copy(flag = null), unnamed),
        )
    }

    @Test
    fun `a manual pairing a stale snapshot and missing metadata have no operator line`() {
        assertNull(pairedExplorerOperator(pairingUrl, null, unnamed))
        assertNull(
            pairedExplorerOperator(
                pairingUrl,
                snapshot.copy(url = "http://elsewhere.example"),
                unnamed,
            ),
        )
        val blank = DojoDisplaySnapshot(url = pairingUrl, name = " ", flag = "", jurisdiction = "  ")
        assertNull(pairedExplorerOperator(pairingUrl, blank, unnamed))
        assertNull(pairedExplorerOperator(null, snapshot, unnamed))
    }

    @Test
    fun `the dojo row follows reachability and the explorer names the onion`() {
        val network = source(
            "src/main/java/com/samourai/sentinel/ui/settings/NetworkActivity.kt",
            "app/src/main/java/com/samourai/sentinel/ui/settings/NetworkActivity.kt",
        )
        val status = network.substringAfter("fun setDojoStatus()")
            .substringBefore("private fun setNetworkStatus")
        assertTrue(status.contains("DojoStatusRow.present"))
        assertTrue(status.contains("R.string.disabled"))
        assertFalse(status.contains("R.string.Enabled"))
        assertFalse(status.contains("pairedExplorerOperator"))
        assertFalse(status.contains("truncateExplorerLabel"))
        assertFalse(network.contains("network_dojo_detail"))
        val render = network.substringAfter("fun renderExplorerRow()")
            .substringBefore("private fun setTorConnectionState")
        assertTrue(render.contains("explorer_status_bound, state.hostLabel.orEmpty()"))
        assertTrue(render.contains("pairedExplorerOperator"))
        assertTrue(render.contains("explorerOperatorVisible"))
        val layout = source(
            "src/main/res/layout/activity_network.xml",
            "app/src/main/res/layout/activity_network.xml",
        )
        assertFalse(layout.contains("network_dojo_detail"))
        assertTrue(layout.contains("network_explorer_detail"))
    }

    private fun source(vararg relative: String): String {
        val file = relative.map { File(it) }.firstOrNull { it.isFile }
            ?: error("source not found from ${File(".").absolutePath}: ${relative.toList()}")
        return file.readText()
    }
}
