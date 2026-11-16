package com.samourai.sentinel.ui.dojo

import com.samourai.sentinel.helpers.fromJSON
import com.samourai.sentinel.ui.utils.PrefsUtil
import java.net.URI

/**
 * The paired Dojo's trusted signer: the full BIP47 payment code that signed
 * the Dojo Bay listing the user paired with, saved with the pairing.
 *
 * A listing's name, flag and onion are not what makes it "the paired Dojo".
 * Only a listing signed by [code] for the paired URL is; a listing that
 * copies the name or the onion under another code is a separate, unpaired
 * listing. The pin is keyed by the paired URL, like the display snapshot, so
 * it applies only while that URL is the one paired (a re-pair elsewhere, a
 * scan or paste, or a restore without it leaves no pin in force).
 *
 * Pairings made by scan or paste, and pairings made before this existed,
 * have no pin: nothing changes for them.
 */
data class DojoBaySignerPin(
    /** The paired Dojo's pairing URL, as signed. */
    val url: String,
    /** The full payment code the listing's signature proved. */
    val code: String,
    /** The directory name shown when it was paired (display only, unsigned). */
    val name: String? = null,
) {
    companion object {
        /**
         * The pin in force for the current pairing, or null: no pin saved
         * (manual pairing, or paired before pins existed), or one saved for
         * another URL than the one now paired.
         */
        fun read(prefs: PrefsUtil): DojoBaySignerPin? {
            val code = prefs.dojoTrustedSignerCode?.takeIf { it.isNotBlank() } ?: return null
            val url = prefs.dojoTrustedSignerUrl?.takeIf { it.isNotBlank() } ?: return null
            val paired = prefs.apiEndPointTor?.takeIf { it.isNotBlank() } ?: return null
            if (url != paired) return null
            val name = prefs.dojoDisplayName.takeIf { prefs.dojoDisplayUrl == url }
            return DojoBaySignerPin(url, code, name)
        }

        /** Saves [pin] with a committed pairing; null (scan, paste) clears any old pin. */
        fun save(prefs: PrefsUtil, pin: DojoBaySignerPin?) {
            prefs.dojoTrustedSignerCode = pin?.code
            prefs.dojoTrustedSignerUrl = pin?.url
        }

        fun clear(prefs: PrefsUtil) = save(prefs, null)
    }
}

/**
 * The signer code of the Community Dojos listing the user picked, held by
 * the pairing sheet until the pairing commits, the same way as
 * [PendingDisplaySnapshot]. DON'T PAIR, a failed pairing, or closing the
 * sheet saves nothing; a scan or paste carries no code.
 */
class PendingSignerPin {
    private var pending: String? = null

    /** A new payload arrived: [signerCode] for a verified listing, null for a scan or paste. */
    fun select(signerCode: String?) {
        pending = signerCode?.takeIf { it.isNotBlank() }
    }

    /** The picked listing's signer code, for the pre-pair dialog; null for a scan or paste. */
    fun forDialog(): String? = pending

    /**
     * The pairing to [pairedUrl] committed: the pin to save, or null for a
     * pairing that did not come from a listing (which clears any old pin).
     * Handed out once.
     */
    fun takeForCommit(pairedUrl: String?, name: String?): DojoBaySignerPin? {
        val code = pending
        pending = null
        if (code == null || pairedUrl.isNullOrBlank()) return null
        return DojoBaySignerPin(pairedUrl, code, name)
    }

    /** Declined or abandoned: nothing is saved. */
    fun discard() {
        pending = null
    }
}

/**
 * How a fetched list relates to the paired Dojo's [DojoBaySignerPin].
 * Nothing on screen reads it yet: showing a changed signer needs a way to
 * open Community Dojos while paired, so that is left for later.
 */
object DojoBayPairedCheck {

    enum class Row {
        /** Signed by the trusted code, for the paired URL: the paired Dojo. */
        PAIRED,
        /** Any other listing, including one that copies the paired Dojo's name or onion under another code. */
        NOT_PAIRED,
    }

    sealed class Status {
        /** No pin in force (no pairing, a manual pairing, or one made before pins). Nothing to check. */
        object NoPin : Status()
        /** The paired Dojo's listing is in the list, signed by the trusted code. */
        object Present : Status()
        /** Neither the paired Dojo nor anything that looks like it is listed. */
        object NotListed : Status()
        /**
         * The paired Dojo's listing (same onion or same name) is now signed
         * by a different code, and no listing signed by the trusted code is
         * there. The app keeps the current pairing as it is and does not take
         * details from that listing; the only way to pair it is the full
         * pre-pair flow again, which replaces the pin. Neutral wording for
         * the user: the signer changed, check with the owner.
         */
        data class SignerChanged(val newCodes: List<String>) : Status()
    }

    /** The URL the listing's signature covers (never the directory's unsigned copy). */
    internal fun signedUrl(item: CheckedDojo): String? =
        item.verified?.let { fromJSON<DojoPairing>(it.signedPayloadJson)?.pairing?.url }

    private fun host(url: String?): String? = try {
        url?.let { URI(it.trim()).host?.lowercase()?.trimEnd('.') }
    } catch (e: Exception) {
        null
    }

    private fun sameName(a: String?, b: String?): Boolean {
        val x = a?.trim()?.lowercase().orEmpty()
        val y = b?.trim()?.lowercase().orEmpty()
        return x.isNotEmpty() && x == y
    }

    fun row(item: CheckedDojo, pin: DojoBaySignerPin?): Row {
        val verified = item.verified ?: return Row.NOT_PAIRED
        if (pin == null) return Row.NOT_PAIRED
        return if (verified.paymentCode == pin.code && signedUrl(item) == pin.url) Row.PAIRED else Row.NOT_PAIRED
    }

    /** True for a listing that looks like the paired Dojo (same onion or same name) under another code. */
    fun looksLikePairedUnderAnotherCode(item: CheckedDojo, pin: DojoBaySignerPin): Boolean {
        val verified = item.verified ?: return false
        if (verified.paymentCode == pin.code) return false
        val pinnedHost = host(pin.url)
        val sameOnion = pinnedHost != null && host(signedUrl(item)) == pinnedHost
        return sameOnion || sameName(item.node.name, pin.name)
    }

    /** [status] for what the sheet shows: a fresh list, or a saved one. Other states list nothing. */
    fun status(display: CommunityDojoDisplay, pin: DojoBaySignerPin?): Status = when (display) {
        is CommunityDojoDisplay.Fresh -> status(display.dojos, pin)
        is CommunityDojoDisplay.Cached -> status(display.dojos, pin)
        is CommunityDojoDisplay.ListedNone -> status(display.dojos, pin)
        else -> if (pin == null) Status.NoPin else Status.NotListed
    }

    fun status(items: List<CheckedDojo>, pin: DojoBaySignerPin?): Status {
        if (pin == null) return Status.NoPin
        if (items.any { row(it, pin) == Row.PAIRED }) return Status.Present
        val changed = items.filter { looksLikePairedUnderAnotherCode(it, pin) }
        if (changed.isEmpty()) return Status.NotListed
        return Status.SignerChanged(changed.mapNotNull { it.verified?.paymentCode }.distinct())
    }
}
