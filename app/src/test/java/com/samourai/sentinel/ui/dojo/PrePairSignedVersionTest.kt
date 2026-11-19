package com.samourai.sentinel.ui.dojo

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import com.samourai.sentinel.R
import com.samourai.sentinel.helpers.fromJSON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The pre-pair dialog's version row comes from inside the signature. A
 * directory (Dojo Bay if compromised, or a custom directory) can edit a
 * listing's top-level version without breaking its signature; the dialog
 * must still show the signed version as the Dojo version, the same one the
 * stored pairing carries, and the directory's only as a line labelled not
 * signed. Uses the real Dojo Bay verifier vector, the real checkNode, the
 * snapshot built as CommunityDojoListBottomSheet.selectListing builds it,
 * the real pairingDetailRows and the real dialog layout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrePairSignedVersionTest {

    private val context = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext<Application>(), R.style.AppThemeV3,
    )

    private fun tamperedListing(version: String): CommunityDojoNode {
        val text = javaClass.classLoader!!.getResource("dojobay/dojobay-verifier-vectors.json")!!.readText()
        val vec = JsonParser.parseString(text).asJsonObject.getAsJsonArray("listings")
            .map { it.asJsonObject }.first { it.get("name").asString == "valid-with-bip47-line" }
        val node = vec.getAsJsonObject("node").deepCopy()
        // Only unsigned, top-level fields are edited.
        node.addProperty("version", version)
        node.addProperty("name", "Samourai Official Dojo")
        node.addProperty("jurisdiction", "Switzerland")
        return CommunityDojoRepository.parseDirectory("""{"nodes":[$node]}""").single()
    }

    private fun snapshotFor(node: CommunityDojoNode, signedJson: String) = DojoDisplaySnapshot(
        url = fromJSON<DojoPairing>(signedJson)?.pairing?.url,
        name = node.name?.takeIf { it.isNotBlank() },
        flag = node.flagEmoji,
        jurisdiction = node.jurisdiction?.takeIf { it.isNotBlank() },
        network = null,
        nameCollision = false,
        version = node.version?.takeIf { it.isNotBlank() },
    )

    @Test
    fun `a tampered unsigned listing version never shows as the Dojo version`() {
        val node = tamperedListing("1.99.0")
        val check = DojoBaySignatures.checkNode(node)
        // The edit doesn't break the signature: this is the attack.
        assertTrue(check is DojoBaySignatures.NodeCheck.Verified)
        val signedJson = (check as DojoBaySignatures.NodeCheck.Verified).signedPayloadJson
        // What Pair stores is this JSON (onScanPayload -> payload -> setDojo).
        val stored = fromJSON<DojoPairing>(signedJson)!!
        assertEquals("1.27.0", stored.pairing?.version)

        val rows = pairingDetailRows(stored, snapshotFor(node, signedJson))
        assertEquals("1.27.0", rows.version)
        assertEquals(stored.pairing?.version, rows.version)
        assertEquals("1.99.0", rows.directoryVersion)

        val view = LayoutInflater.from(context).inflate(R.layout.dialog_dojo_pairing_details, null)
        PrePairDialogViews(view).bindVersion(rows.version, rows.directoryVersion)
        val versionValue = view.findViewById<TextView>(R.id.dojoPairingVersionValue)
        val directoryLine = view.findViewById<TextView>(R.id.dojoPairingDirectoryVersion)
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.dojoPairingVersionRow).visibility)
        assertEquals("1.27.0", versionValue.text.toString())
        assertFalse(versionValue.text.contains("1.99.0"))
        // The directory's claim is visible only as a labelled, unsigned line.
        assertEquals(View.VISIBLE, directoryLine.visibility)
        assertEquals("Directory says 1.99.0 (not signed)", directoryLine.text.toString())
    }

    @Test
    fun `an untampered listing shows one version and no unsigned line`() {
        val node = tamperedListing("1.27.0")
        val check = DojoBaySignatures.checkNode(node) as DojoBaySignatures.NodeCheck.Verified
        val stored = fromJSON<DojoPairing>(check.signedPayloadJson)!!
        val rows = pairingDetailRows(stored, snapshotFor(node, check.signedPayloadJson))
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_dojo_pairing_details, null)
        PrePairDialogViews(view).bindVersion(rows.version, rows.directoryVersion)
        assertEquals("1.27.0", view.findViewById<TextView>(R.id.dojoPairingVersionValue).text.toString())
        assertEquals(View.GONE, view.findViewById<View>(R.id.dojoPairingDirectoryVersion).visibility)
    }
}
