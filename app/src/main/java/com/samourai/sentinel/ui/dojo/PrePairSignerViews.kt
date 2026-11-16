package com.samourai.sentinel.ui.dojo

import android.view.View
import android.widget.TextView
import com.samourai.sentinel.R

/**
 * The pre-pair dialog's "Signed by" section, from the picked listing: the
 * code the listing's signature proved, its grouped form and PayNym link, and
 * whether another listing used the same name under a different signer. Null
 * (no section) for a scan or paste, or a code that is not shaped like one.
 */
data class PrePairSigner(
    val code: String,
    val displayCode: String,
    val profileUrl: String,
    val nameCollision: Boolean,
)

fun prePairSigner(
    signerCode: String?,
    snapshot: DojoDisplaySnapshot?,
    pairingUrl: String?,
): PrePairSigner? {
    val code = signerCode?.takeIf { DojoBaySignatures.isPaymentCodeShape(it) } ?: return null
    val url = PayNymLinks.profileUrl(code) ?: return null
    val collision = pairingUrl != null && snapshot?.url == pairingUrl && snapshot.nameCollision
    return PrePairSigner(code, SignerCodeFormat.displayCode(code), url, collision)
}

/**
 * Binds dialog_dojo_pairing_details: the directory header with its
 * "Unverified" chip and caption, and the "Signed by" section. Kept apart from
 * the sheet so the dialog states can be bound and checked in tests.
 */
class PrePairDialogViews(val root: View) {

    private val header: View = root.findViewById(R.id.dojoPairingDirectoryHeader)
    private val name: TextView = root.findViewById(R.id.dojoPairingDirectoryName)
    private val flag: TextView = root.findViewById(R.id.dojoPairingDirectoryFlag)
    private val caption: View = root.findViewById(R.id.dojoPairingDirectoryCaption)
    private val signerSection: View = root.findViewById(R.id.dojoPairingSignerSection)
    private val signerCode: TextView = root.findViewById(R.id.dojoPairingSignerCode)
    private val signerLink: TextView = root.findViewById(R.id.dojoPairingSignerLink)
    private val collision: View = root.findViewById(R.id.dojoPairingSignerCollision)

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
        caption.visibility = View.VISIBLE
    }

    fun bindSigner(signer: PrePairSigner?, onOpenPayNym: (String) -> Unit) {
        if (signer == null) {
            signerSection.visibility = View.GONE
            return
        }
        signerCode.text = signer.displayCode
        signerLink.setOnClickListener { onOpenPayNym(signer.profileUrl) }
        collision.visibility = if (signer.nameCollision) View.VISIBLE else View.GONE
        signerSection.visibility = View.VISIBLE
    }
}
