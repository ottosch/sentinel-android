package com.samourai.sentinel.ui.dojo

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The dialog and row states for the signer: the directory name and flag
 * with the caption of what the signature covers (no chip, here or on list rows), the grouped full code and PayNym link, the same-name warning, and
 * a row's collapsed signer line. Inflated from the real layouts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignerIdentityUiTest {

    private val context = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext<Application>(), R.style.AppThemeV3,
    )
    private val qa = "PM8TJUJHkfdjkb5g5XaJuvnC63rUz7LXHnVqsnCd17dhDRupn9gW3QrgJJdNNGxsR4LSe4kFBMwarLbJVRQur9ZQfY4rtTHWWgdjLeE1svskeNoCEoum"
    private val other = "PM8TJUJH" + "b".repeat(100) + "eNoCEoum"

    private fun dialog(): View = LayoutInflater.from(context).inflate(R.layout.dialog_dojo_pairing_details, null)

    private fun View.shown(id: Int) = findViewById<View>(id).visibility == View.VISIBLE
    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()

    @Test
    fun `a picked listing shows the name and flag, the signature caption, the full grouped code and the link`() {
        val view = dialog()
        val views = PrePairDialogViews(view)
        views.bindHeader(DojoDirectoryHeader("🇸🇬", "QA valid (signed)"), "Unnamed")
        views.bindSignatureScope("DojoBay.org", coversExplorer = true)
        var opened: String? = null
        views.bindSigner(prePairSigner(qa, DojoDisplaySnapshot("u", "QA valid (signed)", "🇸🇬", null), "u")) { opened = it }

        assertTrue(view.shown(R.id.dojoPairingDirectoryHeader))
        assertEquals("QA valid (signed)", view.text(R.id.dojoPairingDirectoryName))
        assertEquals("🇸🇬", view.text(R.id.dojoPairingDirectoryFlag))
        // The caption says what the signature covers and where name and location come from.
        assertTrue(view.shown(R.id.dojoPairingDirectoryCaption))
        assertEquals(
            "The signature covers this Dojo's URL, connection details and block explorer. " +
                "Name and location come from the DojoBay.org listing.",
            view.text(R.id.dojoPairingDirectoryCaption),
        )
        assertTrue(view.shown(R.id.dojoPairingSignerSection))
        assertEquals(SignerCodeFormat.displayCode(qa), view.text(R.id.dojoPairingSignerCode))
        assertEquals(qa, SignerCodeFormat.compact(view.text(R.id.dojoPairingSignerCode)))
        assertEquals(12f, view.findViewById<TextView>(R.id.dojoPairingSignerCode).textSize / context.resources.displayMetrics.scaledDensity, 0.01f)
        assertFalse(view.shown(R.id.dojoPairingSignerCollision))
        // The PayNym name stays hidden until it is looked up.
        assertFalse(view.shown(R.id.dojoPairingSignerMeta))

        view.findViewById<View>(R.id.dojoPairingSignerLink).performClick()
        assertEquals(qa, opened)
    }

    @Test
    fun `the same name under another signer shows the warning`() {
        val view = dialog()
        PrePairDialogViews(view).bindSigner(
            prePairSigner(qa, DojoDisplaySnapshot("u", "QA", null, null, nameCollision = true), "u"),
        ) {}
        assertTrue(view.shown(R.id.dojoPairingSignerCollision))
    }

    @Test
    fun `a scan or paste has no header and no signer section`() {
        val view = dialog()
        val views = PrePairDialogViews(view)
        views.bindHeader(null, "Unnamed")
        views.bindSigner(null) {}
        assertFalse(view.shown(R.id.dojoPairingDirectoryHeader))
        assertFalse(view.shown(R.id.dojoPairingDirectoryCaption))
        assertFalse(view.shown(R.id.dojoPairingSignerSection))
    }

    private fun checked(name: String, code: String?) = CheckedDojo(
        CommunityDojoNode(id = name + (code?.takeLast(3) ?: ""), name = name, status = "active", version = "1.27.0"),
        if (code != null) DojoBaySignatures.NodeCheck.Verified(code, "{}", versionDrift = false)
        else DojoBaySignatures.NodeCheck.Unverified(DojoBaySignatures.Failure.MISSING_SIGNATURE),
    )

    private fun bound(adapter: CommunityDojoAdapter, position: Int): CommunityDojoAdapter.ViewHolder {
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(holder, position)
        return holder
    }

    @Test
    fun `a row shows first 8 and last 8, and a tap anywhere on the row selects it`() {
        var selected: CheckedDojo? = null
        val adapter = CommunityDojoAdapter(onSelect = { selected = it }, onCopy = {})
        val signed = checked("QA valid (signed)", qa)
        adapter.submitList(listOf(signed, checked("QA missing signature", null)))

        val row = bound(adapter, 0)
        assertEquals("Signed by PM8TJUJH…eNoCEoum", row.signer.text.toString())
        assertEquals(View.VISIBLE, row.signer.visibility)

        row.itemView.performClick()
        assertEquals(signed, selected)
        // Still the collapsed line: a tap does not expand the row.
        assertEquals("Signed by PM8TJUJH…eNoCEoum", row.signer.text.toString())

        // The signer line is plain text, so a real tap reaches the row.
        assertTrue(!row.signer.hasOnClickListeners())
        assertTrue(!row.signer.isClickable)

        // An unverified row is greyed out, with no signer.
        val dim = bound(adapter, 1)
        assertEquals(0.5f, dim.itemView.alpha)
        assertFalse(dim.copyButton.isEnabled)
        assertEquals(View.GONE, dim.signer.visibility)
        assertTrue(dim.subtitle.text.toString().contains("can't pair"))
    }

    @Test
    fun `rows that share a name under different signers say so`() {
        val adapter = CommunityDojoAdapter(onSelect = {}, onCopy = {})
        adapter.submitList(listOf(checked("QA", qa), checked("QA", other), checked("Elsewhere", qa)))
        assertEquals(View.VISIBLE, bound(adapter, 0).warning.visibility)
        assertEquals(View.VISIBLE, bound(adapter, 1).warning.visibility)
        assertEquals(View.GONE, bound(adapter, 2).warning.visibility)
        assertTrue(adapter.nameCollides(adapter.currentList[0]))
        assertFalse(adapter.nameCollides(adapter.currentList[2]))
    }

    @Test
    fun `the toolbar title is one line at 19sp and is never cut off`() {
        val sheet = LayoutInflater.from(context).inflate(R.layout.bottomsheet_community_dojo_list, null)
        val title = sheet.findViewById<TextView>(R.id.communityDojoTitle)
        assertEquals("Community Dojos (DojoBay.org)", title.text.toString())
        assertEquals(1, title.maxLines)
        assertNull(title.ellipsize)
        assertEquals(
            androidx.core.widget.TextViewCompat.AUTO_SIZE_TEXT_TYPE_UNIFORM,
            androidx.core.widget.TextViewCompat.getAutoSizeTextType(title),
        )
        val sp = context.resources.displayMetrics.scaledDensity
        assertEquals(19f, androidx.core.widget.TextViewCompat.getAutoSizeMaxTextSize(title) / sp, 0.5f)
    }

    // ---- the PayNym avatar and name ------------------------------------------------

    private fun signerDialog(): Pair<View, PrePairDialogViews> {
        val view = dialog()
        val views = PrePairDialogViews(view)
        views.bindSigner(prePairSigner(qa, null, null)) {}
        views.showIdentityLoading()
        return view to views
    }

    @Test
    fun `while PayNym is asked the avatar slot is empty and no name shows`() {
        val (view, views) = signerDialog()
        assertTrue(view.shown(R.id.dojoPairingSignerIdentity))
        assertFalse(view.shown(R.id.dojoPairingSignerMeta))
        assertFalse(views.showsUnavailableAvatar)
    }

    @Test
    fun `an avatar and a name show the avatar, the +name and the paynym rs caption`() {
        val (view, views) = signerDialog()
        val image = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
        views.showIdentity(image, "+examplenym")
        assertTrue(view.shown(R.id.dojoPairingSignerMeta))
        assertEquals("+examplenym", view.text(R.id.dojoPairingSignerNym))
        assertFalse(views.showsUnavailableAvatar)
        assertNull(view.findViewById<android.widget.ImageView>(R.id.dojoPairingSignerAvatar).colorFilter)
        // An avatar without a name: the avatar alone.
        views.showIdentity(image, null)
        assertFalse(view.shown(R.id.dojoPairingSignerMeta))
    }

    @Test
    fun `a failed avatar shows the greyed-out stand-in and hides the name and caption`() {
        val (view, views) = signerDialog()
        views.showIdentity(null, "+examplenym")
        assertTrue(views.showsUnavailableAvatar)
        assertFalse(view.shown(R.id.dojoPairingSignerMeta))
        val avatar = view.findViewById<android.widget.ImageView>(R.id.dojoPairingSignerAvatar)
        assertTrue(avatar.colorFilter is android.graphics.ColorMatrixColorFilter)
        assertEquals("PayNym avatar unavailable", avatar.contentDescription)
        // The code and link stay.
        assertTrue(view.shown(R.id.dojoPairingSignerCode))
        assertTrue(view.shown(R.id.dojoPairingSignerLink))
    }

    @Test
    fun `bytes that are not a PNG decode to nothing`() {
        assertNull(PayNymAvatarImage.decode(null))
        assertNull(PayNymAvatarImage.decode("GIF89a".toByteArray()))
    }
}
