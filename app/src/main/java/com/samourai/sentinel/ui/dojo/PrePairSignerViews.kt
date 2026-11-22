package com.samourai.sentinel.ui.dojo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.samourai.sentinel.R
import kotlinx.coroutines.Job

/**
 * The pre-pair dialog's "Signed by" section, from the picked listing: the
 * code the listing's signature proved, its grouped form, and
 * whether another listing used the same name under a different signer. Null
 * (no section) for a scan or paste, or a code that is not shaped like one.
 */
data class PrePairSigner(
    val code: String,
    val displayCode: String,
    val nameCollision: Boolean,
)

fun prePairSigner(
    signerCode: String?,
    snapshot: DojoDisplaySnapshot?,
    pairingUrl: String?,
): PrePairSigner? {
    val code = signerCode?.takeIf { DojoBaySignatures.isPaymentCodeShape(it) } ?: return null
    val collision = pairingUrl != null && snapshot?.url == pairingUrl && snapshot.nameCollision
    return PrePairSigner(code, SignerCodeFormat.displayCode(code), collision)
}

/**
 * Binds dialog_dojo_pairing_details: the directory header with its
 * name and flag and the caption under them, and the "Signed by"
 * section. There is no Dojo version row: Sentinel shows a version only
 * after pairing, the one the Dojo itself reports. Kept apart from the
 * sheet so the dialog states can be bound and checked in tests.
 */
class PrePairDialogViews(val root: View) {

    private val header: View = root.findViewById(R.id.dojoPairingDirectoryHeader)
    private val name: TextView = root.findViewById(R.id.dojoPairingDirectoryName)
    private val flag: TextView = root.findViewById(R.id.dojoPairingDirectoryFlag)
    private val caption: TextView = root.findViewById(R.id.dojoPairingDirectoryCaption)
    private val signerSection: View = root.findViewById(R.id.dojoPairingSignerSection)
    private val signerCode: TextView = root.findViewById(R.id.dojoPairingSignerCode)
    private val signerLink: TextView = root.findViewById(R.id.dojoPairingSignerLink)
    private val signerLinkProgress: View = root.findViewById(R.id.dojoPairingSignerLinkProgress)
    private val signerLinkError: TextView = root.findViewById(R.id.dojoPairingSignerLinkError)
    private val collision: View = root.findViewById(R.id.dojoPairingSignerCollision)
    private var payNymJob: Job? = null
    private val identity: View = root.findViewById(R.id.dojoPairingSignerIdentity)
    private val avatar: ImageView = root.findViewById(R.id.dojoPairingSignerAvatar)
    private val meta: View = root.findViewById(R.id.dojoPairingSignerMeta)
    private val nym: TextView = root.findViewById(R.id.dojoPairingSignerNym)

    /** The avatar slot while PayNym is asked: an empty square, no name. */
    fun showIdentityLoading() {
        avatar.setImageDrawable(null)
        avatar.clearColorFilter()
        avatar.contentDescription = root.context.getString(R.string.paynym_avatar_description)
        meta.visibility = View.GONE
        identity.visibility = View.VISIBLE
    }

    /**
     * What PayNym answered. No avatar: a greyed-out, dimmed stand-in and no
     * +name or caption, whatever the name lookup gave. An avatar without a
     * name: the avatar alone.
     */
    fun showIdentity(avatarImage: Bitmap?, name: String?) {
        identity.visibility = View.VISIBLE
        if (avatarImage == null) {
            avatar.setImageResource(R.drawable.paynym_avatar_unavailable)
            avatar.colorFilter = UNAVAILABLE_FILTER
            avatar.setBackgroundColor(UNAVAILABLE_BACKGROUND)
            avatar.contentDescription = root.context.getString(R.string.paynym_avatar_failed_description)
            meta.visibility = View.GONE
            return
        }
        avatar.clearColorFilter()
        avatar.setImageBitmap(avatarImage)
        avatar.contentDescription = root.context.getString(R.string.paynym_avatar_description)
        if (name != null) {
            nym.text = name
            meta.visibility = View.VISIBLE
        } else {
            meta.visibility = View.GONE
        }
    }

    /** True while the stand-in for a failed avatar is shown. */
    val showsUnavailableAvatar: Boolean
        get() = identity.visibility == View.VISIBLE && avatar.colorFilter === UNAVAILABLE_FILTER

    /** [directory] null: no header (a scan, a paste, or a re-pair elsewhere). */
    fun bindHeader(directory: DojoDirectoryHeader?, unnamed: String) {
        if (directory == null) {
            header.visibility = View.GONE
            caption.visibility = View.GONE
            return
        }
        name.text = directory.name ?: unnamed
        if (directory.flag != null) {
            flag.text = directory.flag
            flag.visibility = View.VISIBLE
        } else {
            flag.visibility = View.GONE
        }
        header.visibility = View.VISIBLE
        // The name and flag come from the directory, outside the signature.
        caption.visibility = View.VISIBLE
    }

    /**
     * What the signature covers, and which directory supplied the name
     * and location. [coversExplorer] is true only when the signed
     * payload carries a block explorer. [directoryName] is DojoBay.org for
     * the default directory and that directory's label otherwise.
     */
    fun bindSignatureScope(directoryName: String, coversExplorer: Boolean) {
        val res = if (coversExplorer) {
            R.string.dojo_pairing_signature_scope_with_explorer
        } else {
            R.string.dojo_pairing_signature_scope
        }
        caption.text = root.context.getString(res, directoryName)
    }

    /** [onOpenPayNym] gets the signed code; the page is found from it ([PayNymPage.openSigner]). */
    fun bindSigner(signer: PrePairSigner?, onOpenPayNym: (String) -> Unit) {
        if (signer == null) {
            cancelPayNymLookup()
            signerSection.visibility = View.GONE
            return
        }
        signerCode.text = signer.displayCode
        showPayNymLookupIdle()
        signerLink.setOnClickListener {
            // A second tap while the lookup is running is ignored.
            if (payNymJob?.isActive == true) return@setOnClickListener
            onOpenPayNym(signer.code)
        }
        collision.visibility = if (signer.nameCollision) View.VISIBLE else View.GONE
        signerSection.visibility = View.VISIBLE
    }

    /** The lookup for "View on PayNym" is in flight: spinner on, link disabled. */
    fun beginPayNymLookup(job: Job) {
        payNymJob = job
        signerLink.isEnabled = false
        signerLinkProgress.visibility = View.VISIBLE
        signerLinkError.visibility = View.GONE
    }

    /** Restore the link after a lookup that opened the page, or after cancel. */
    fun showPayNymLookupIdle() {
        signerLink.isEnabled = true
        signerLinkProgress.visibility = View.GONE
        signerLinkError.visibility = View.GONE
    }

    /** The lookup finished with no page: restore the link and show why inline. */
    fun showPayNymLookupFailed() {
        payNymJob = null
        showPayNymLookupIdle()
        signerLinkError.text = root.context.getString(R.string.paynym_page_not_found)
        signerLinkError.visibility = View.VISIBLE
    }

    fun endPayNymLookup(opened: Boolean) {
        payNymJob = null
        if (opened) showPayNymLookupIdle() else showPayNymLookupFailed()
    }

    /** The dialog went away: stop the lookup and clear the in-progress state. */
    fun cancelPayNymLookup() {
        payNymJob?.cancel()
        payNymJob = null
        if (signerLinkProgress.visibility == View.VISIBLE || !signerLink.isEnabled) {
            showPayNymLookupIdle()
        }
    }

    val isPayNymLookupInProgress: Boolean
        get() = payNymJob?.isActive == true
}

/**
 * PayNym avatar bytes to a bitmap for a 40dp slot, or null. The bytes are
 * already a capped PNG ([PayNymFetcher]); an image with a side over
 * [MAX_SIDE] pixels is refused before it is decoded, and the decode is
 * sampled down to about [TARGET_SIDE].
 */
object PayNymAvatarImage {
    const val MAX_SIDE = 4096
    const val TARGET_SIDE = 256

    fun decode(png: ByteArray?): Bitmap? {
        if (png == null || !PayNymFetcher.isPng(png)) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
            val side = maxOf(bounds.outWidth, bounds.outHeight)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || side > MAX_SIDE) return null
            var sample = 1
            while (side / (sample * 2) >= TARGET_SIDE) sample *= 2
            BitmapFactory.decodeByteArray(png, 0, png.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }
}

/** Grayscale, then dimmed to 85%, as in the failed-avatar mockup. Built on first use (Android classes). */
private val UNAVAILABLE_FILTER by lazy {
    ColorMatrixColorFilter(
    ColorMatrix().apply {
        setSaturation(0f)
        postConcat(ColorMatrix().apply { setScale(0.85f, 0.85f, 0.85f, 1f) })
    },
    )
}

private const val UNAVAILABLE_BACKGROUND = 0xFF303234.toInt()
