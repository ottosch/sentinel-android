package com.samourai.sentinel.ui.dojo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.samourai.sentinel.R

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
 * name and flag and the caption under them, the Dojo version row (the
 * directory's checked version, with where it was checked), and the
 * "Signed by" section. Kept apart from the sheet so the dialog states
 * can be bound and checked in tests.
 */
class PrePairDialogViews(val root: View) {

    private val header: View = root.findViewById(R.id.dojoPairingDirectoryHeader)
    private val name: TextView = root.findViewById(R.id.dojoPairingDirectoryName)
    private val flag: TextView = root.findViewById(R.id.dojoPairingDirectoryFlag)
    private val caption: View = root.findViewById(R.id.dojoPairingDirectoryCaption)
    private val versionRow: View = root.findViewById(R.id.dojoPairingVersionRow)
    private val versionValue: TextView = root.findViewById(R.id.dojoPairingVersionValue)
    private val signerSection: View = root.findViewById(R.id.dojoPairingSignerSection)
    private val signerCode: TextView = root.findViewById(R.id.dojoPairingSignerCode)
    private val signerLink: TextView = root.findViewById(R.id.dojoPairingSignerLink)
    private val collision: View = root.findViewById(R.id.dojoPairingSignerCollision)
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
     * The Dojo version row. [checked] is the directory's checked version,
     * already shape-filtered by [listingVersionOrNull], or null to hide
     * the row. The signed pairing.version is not a fallback and is not
     * shown. The source line is part of the row, so it hides with it.
     */
    fun bindVersion(checked: String?) {
        if (checked != null) {
            versionValue.text = checked
            versionRow.visibility = View.VISIBLE
        } else {
            versionValue.text = ""
            versionRow.visibility = View.GONE
        }
    }

    /** [onOpenPayNym] gets the signed code; the page is found from it ([PayNymPage.openSigner]). */
    fun bindSigner(signer: PrePairSigner?, onOpenPayNym: (String) -> Unit) {
        if (signer == null) {
            signerSection.visibility = View.GONE
            return
        }
        signerCode.text = signer.displayCode
        signerLink.setOnClickListener { onOpenPayNym(signer.code) }
        collision.visibility = if (signer.nameCollision) View.VISIBLE else View.GONE
        signerSection.visibility = View.VISIBLE
    }
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
