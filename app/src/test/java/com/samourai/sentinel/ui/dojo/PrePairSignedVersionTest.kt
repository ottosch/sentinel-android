package com.samourai.sentinel.ui.dojo

import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.samourai.sentinel.R
import com.samourai.sentinel.core.DerivedNetwork
import com.samourai.sentinel.helpers.fromJSON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The pre-pair dialog shows one Dojo version: the directory's checked
 * version, after [listingVersionOrNull]. The signed pairing.version stays
 * in the payload Pair stores and is not shown, even when the directory
 * version is missing or ill-shaped. A directory can edit the top-level
 * version without breaking the signature; the dialog shows that checked
 * version, and nothing in the dialog says it is not signed while the
 * signer is on screen.
 *
 * Uses the real Dojo Bay verifier vector, the real checkNode, the snapshot
 * built as CommunityDojoListBottomSheet.selectListing builds it, the real
 * pairingDetailRows and the real dialog layout, shown the way the sheet
 * shows it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrePairSignedVersionTest {

    private fun tamperedListing(version: String?): CommunityDojoNode {
        val text = javaClass.classLoader!!.getResource("dojobay/dojobay-verifier-vectors.json")!!.readText()
        val vec = com.google.gson.JsonParser.parseString(text).asJsonObject.getAsJsonArray("listings")
            .map { it.asJsonObject }.first { it.get("name").asString == "valid-with-bip47-line" }
        val node = vec.getAsJsonObject("node").deepCopy()
        // Only fields outside the signature are edited.
        if (version == null) node.remove("version") else node.addProperty("version", version)
        node.addProperty("name", "btcwrestle")
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

    /** The dialog body, bound the way DojoConfigureBottomSheet.showPairingConfirmation binds it. */
    private fun bindBody(
        activity: PrePairHostActivity,
        pairing: DojoPairing,
        snapshot: DojoDisplaySnapshot?,
        signerCode: String?,
    ): View {
        val rows = pairingDetailRows(pairing, snapshot)
        val builder = MaterialAlertDialogBuilder(activity)
        val view = LayoutInflater.from(builder.context).inflate(R.layout.dialog_dojo_pairing_details, null)
        val views = PrePairDialogViews(view)
        views.bindHeader(directoryHeader(snapshot, pairing.pairing?.url), activity.getString(R.string.dojo_unnamed))
        views.bindSigner(prePairSigner(signerCode, snapshot, pairing.pairing?.url)) {}
        bindRow(view, R.id.dojoPairingJurisdictionRow, R.id.dojoPairingJurisdictionValue, rows.jurisdiction)
        rows.network?.let { evidence ->
            val label = activity.getString(
                if (evidence == DerivedNetwork.TESTNET) R.string.network_testnet else R.string.network_mainnet,
            )
            bindRow(view, R.id.dojoPairingNetworkRow, R.id.dojoPairingNetworkValue, label)
        }
        bindRow(view, R.id.dojoPairingUrlRow, R.id.dojoPairingUrlValue, rows.url)
        views.bindVersion(rows.version)
        bindRow(view, R.id.dojoPairingExplorerRow, R.id.dojoPairingExplorerValue, rows.explorer)
        view.findViewById<TextView>(R.id.dojoPairingMessage).setText(R.string.dojo_pairing_confirm_message)
        return view
    }

    private fun bindRow(view: View, rowId: Int, valueId: Int, value: String?) {
        if (value == null) {
            view.findViewById<View>(rowId).visibility = View.GONE
        } else {
            view.findViewById<TextView>(valueId).text = value
            view.findViewById<View>(rowId).visibility = View.VISIBLE
        }
    }

    private fun show(body: View, activity: PrePairHostActivity): AlertDialog {
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.dojo_pairing_confirm_title)
            .setView(body)
            .setPositiveButton(R.string.dojo_pairing_confirm_accept, null)
            .setNegativeButton(R.string.dojo_pairing_confirm_decline, null)
            .create()
        dialog.show()
        shadowOf(Looper.getMainLooper()).idle()
        return dialog
    }

    private fun activity() = Robolectric.buildActivity(PrePairHostActivity::class.java).setup().get()

    private fun visibleText(root: View): String {
        val parts = ArrayList<String>()
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE) return
            v.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            if (v is TextView) v.text?.toString()?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
        return parts.joinToString("\n")
    }

    private fun assertNoContradictoryWording(text: String) {
        val lower = text.lowercase()
        assertFalse(text, lower.contains("not signed"))
        assertFalse(text, lower.contains("unsigned"))
        assertFalse(text, lower.contains("unverified"))
    }

    private fun occurrences(text: String, needle: String): Int =
        if (needle.isEmpty()) 0 else text.split(needle).size - 1

    @Test
    fun `a checked directory version is the only Dojo version and the signed one stays stored`() {
        val node = tamperedListing("1.29.2")
        val check = DojoBaySignatures.checkNode(node)
        assertTrue(check is DojoBaySignatures.NodeCheck.Verified)
        val verified = check as DojoBaySignatures.NodeCheck.Verified
        val signedJson = verified.signedPayloadJson
        val stored = fromJSON<DojoPairing>(signedJson)!!
        // What Pair stores is this JSON. Editing the directory version does not change it.
        assertEquals("1.27.0", stored.pairing?.version)
        assertTrue(signedJson.contains("\"version\":\"1.27.0\""))

        val snapshot = snapshotFor(node, signedJson)
        val activity = activity()
        val body = bindBody(activity, stored, snapshot, verified.paymentCode)
        val dialog = show(body, activity)
        val text = visibleText(dialog.window!!.decorView)

        assertEquals(View.VISIBLE, body.findViewById<View>(R.id.dojoPairingVersionRow).visibility)
        assertEquals("1.29.2", body.findViewById<TextView>(R.id.dojoPairingVersionValue).text.toString())
        assertEquals(
            "Last checked by DojoBay.org",
            body.findViewById<TextView>(R.id.dojoPairingVersionSource).text.toString(),
        )
        assertEquals(View.VISIBLE, body.findViewById<View>(R.id.dojoPairingVersionSource).visibility)
        assertEquals(1, occurrences(text, "1.29.2"))
        assertEquals(0, occurrences(text, "1.27.0"))
        assertTrue(body.findViewById<View>(R.id.dojoPairingSignerSection).visibility == View.VISIBLE)
        assertNoContradictoryWording(text)
        assertTrue(text.contains("Last checked by DojoBay.org"))
        assertTrue(text.contains("The signature covers this Dojo's URL, connection details and block explorer."))
        // Binding the dialog does not rewrite the consented payload.
        assertEquals("1.27.0", fromJSON<DojoPairing>(signedJson)!!.pairing?.version)
        assertFalse(text.contains("testkey123"))
        dialog.dismiss()
    }

    @Test
    fun `signed 1_4_5 with directory 1_29_2 shows only 1_29_2`() {
        val payload =
            """{"pairing":{"type":"dojo.api","version":"1.4.5","apikey":"secret-api-key","url":"http://community.example/v2"},"explorer":{"type":"explorer.btc_rpc_explorer","url":"http://explorer.example"}}"""
        val stored = fromJSON<DojoPairing>(payload)!!
        assertEquals("1.4.5", stored.pairing?.version)
        val signer = (DojoBaySignatures.checkNode(tamperedListing("1.29.2")) as DojoBaySignatures.NodeCheck.Verified).paymentCode
        val snapshot = DojoDisplaySnapshot(
            url = "http://community.example/v2",
            name = "btcwrestle",
            flag = null,
            jurisdiction = "Switzerland",
            version = "1.29.2",
        )
        val activity = activity()
        val body = bindBody(activity, stored, snapshot, signer)
        val dialog = show(body, activity)
        val text = visibleText(dialog.window!!.decorView)

        assertEquals("1.29.2", body.findViewById<TextView>(R.id.dojoPairingVersionValue).text.toString())
        assertEquals(View.VISIBLE, body.findViewById<View>(R.id.dojoPairingVersionRow).visibility)
        assertEquals(1, occurrences(text, "1.29.2"))
        assertEquals(0, occurrences(text, "1.4.5"))
        assertTrue(text.contains("Last checked by DojoBay.org"))
        assertEquals(View.VISIBLE, body.findViewById<View>(R.id.dojoPairingSignerSection).visibility)
        assertNoContradictoryWording(text)
        assertEquals("1.4.5", stored.pairing?.version)
        assertEquals("1.4.5", fromJSON<DojoPairing>(payload)!!.pairing?.version)
        assertFalse(text.contains("secret-api-key"))
        dialog.dismiss()
    }

    @Test
    fun `the same directory and signed version still shows once, from the directory`() {
        val payload =
            """{"pairing":{"type":"dojo.api","version":"1.4.5","apikey":"secret-api-key","url":"http://community.example/v2"}}"""
        val stored = fromJSON<DojoPairing>(payload)!!
        val signer = (DojoBaySignatures.checkNode(tamperedListing(null)) as DojoBaySignatures.NodeCheck.Verified).paymentCode
        val snapshot = DojoDisplaySnapshot(
            url = "http://community.example/v2",
            name = "btcwrestle",
            flag = null,
            jurisdiction = null,
            version = "1.4.5",
        )
        val activity = activity()
        val body = bindBody(activity, stored, snapshot, signer)
        val text = visibleText(show(body, activity).window!!.decorView)
        assertEquals("1.4.5", body.findViewById<TextView>(R.id.dojoPairingVersionValue).text.toString())
        assertEquals(1, occurrences(text, "1.4.5"))
        assertTrue(text.contains("Last checked by DojoBay.org"))
        assertNoContradictoryWording(text)
        assertEquals("1.4.5", stored.pairing?.version)
    }

    @Test
    fun `a missing blank or ill-shaped directory version hides the row and never shows the signed version`() {
        val payload =
            """{"pairing":{"type":"dojo.api","version":"1.4.5","apikey":"secret-api-key","url":"http://community.example/v2"}}"""
        val stored = fromJSON<DojoPairing>(payload)!!
        val signer = (DojoBaySignatures.checkNode(tamperedListing("1.29.2")) as DojoBaySignatures.NodeCheck.Verified).paymentCode
        val rejected = listOf(
            null,
            "",
            " ",
            " 1.29.2",
            "1.29.2 ",
            "1.29.2\n",
            "<b>1.29.2</b>",
            "<img src=x>",
            "a".repeat(33),
        )
        val activity = activity()
        for (directoryVersion in rejected) {
            assertNull(directoryVersion, listingVersionOrNull(directoryVersion))
            val snapshot = DojoDisplaySnapshot(
                url = "http://community.example/v2",
                name = "btcwrestle",
                flag = null,
                jurisdiction = "Switzerland",
                version = directoryVersion,
            )
            val body = bindBody(activity, stored, snapshot, signer)
            val dialog = show(body, activity)
            val text = visibleText(dialog.window!!.decorView)
            assertEquals(directoryVersion, View.GONE, body.findViewById<View>(R.id.dojoPairingVersionRow).visibility)
            assertEquals(directoryVersion, "", body.findViewById<TextView>(R.id.dojoPairingVersionValue).text.toString())
            assertEquals(directoryVersion, 0, occurrences(text, "1.4.5"))
            assertFalse(directoryVersion, text.contains("Last checked by DojoBay.org"))
            assertFalse(directoryVersion, text.contains("Dojo version"))
            if (!directoryVersion.isNullOrBlank()) {
                assertFalse(directoryVersion, text.contains(directoryVersion))
            }
            assertTrue(body.findViewById<View>(R.id.dojoPairingSignerSection).visibility == View.VISIBLE)
            assertNoContradictoryWording(text)
            dialog.dismiss()
        }
        // A 32-character version is still in shape, so the row shows it and not 1.4.5.
        val ok = "a".repeat(32)
        assertEquals(ok, listingVersionOrNull(ok))
        val shown = bindBody(
            activity,
            stored,
            DojoDisplaySnapshot(
                url = "http://community.example/v2",
                name = "btcwrestle",
                flag = null,
                jurisdiction = null,
                version = ok,
            ),
            signer,
        )
        val shownText = visibleText(show(shown, activity).window!!.decorView)
        assertEquals(View.VISIBLE, shown.findViewById<View>(R.id.dojoPairingVersionRow).visibility)
        assertEquals(ok, shown.findViewById<TextView>(R.id.dojoPairingVersionValue).text.toString())
        assertEquals(0, occurrences(shownText, "1.4.5"))
        assertTrue(shownText.contains("Last checked by DojoBay.org"))
        assertEquals("1.4.5", fromJSON<DojoPairing>(payload)!!.pairing?.version)
    }
}
