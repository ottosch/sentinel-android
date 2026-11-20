package com.samourai.sentinel.ui.collectionDetails.transactions

import com.samourai.sentinel.data.whirlpool.ExistingLabel
import com.samourai.sentinel.data.whirlpool.WhirlpoolConfig
import com.samourai.sentinel.data.whirlpool.WhirlpoolLabelPolicy
import com.samourai.wallet.util.XPUB
import java.util.Locale

/**
 * Display-only account perspective for an auto-written Whirlpool Tx0
 * label (#343, mix labels deferred).
 *
 * Sync stores one tx row per (txid, associatedPubKey), but the
 * auto-writer stores a single type-"tx" label keyed by bare txid
 * ("Whirlpool Tx0"). Every account row would otherwise show that same
 * string. This helper picks the text for the row being bound:
 *
 *  - Deposit (funds leaving) -> "Whirlpool Tx0"
 *  - Premix                   -> "Whirlpool Premix"
 *  - Bad Bank                 -> "Bad Bank"
 *  - any other role           -> the stored label, unchanged
 *
 * It never writes. [WhirlpoolLabelPolicy] decides whether the stored
 * row is ours to reinterpret: only an `auto:` origin may be remapped,
 * and only when the stored text is the configured Tx0 label. A label
 * the user typed (null origin), an empty origin, or a BIP-329 import
 * is returned as stored. Any other auto text, including "Whirlpool
 * Mix", is returned as stored when automatic labels are being shown.
 * Postmix is [Role.OTHER], so a first mix shows "Whirlpool Mix" on
 * that tab. Remix amount rendering is a separate path and is not
 * touched here.
 *
 * [showAuto] is the Whirlpool auto-labels setting, read at render
 * time. When it is false every `auto:` transaction label is hidden,
 * including the Premix and Bad Bank remaps. The stored row is not
 * touched. Manual and imported origins stay visible.
 *
 * Pure JVM. No Android, Room, or network. Callers resolve the role
 * with [roleForPub]: the xpub's account index wins over the
 * user-editable pub label.
 */
object TxPerspectiveLabel {

    enum class Role {
        DEPOSIT,
        PREMIX,
        BAD_BANK,
        OTHER,
    }

    /**
     * Premix-side display of an auto Tx0. Not a stored label and not
     * part of [WhirlpoolConfig.Labels] — the writer must keep writing
     * the single global Tx0 string.
     */
    const val PREMIX_DISPLAY = "Whirlpool Premix"

    /**
     * Account role of a collection pub, from its label. Wallet import
     * uses "Deposit", "Deposit BIP84/49/44", "Premix", and "Bad Bank"
     * (the tab title "Badbank" is accepted too). Anything else,
     * including Postmix, is [Role.OTHER] and is not remapped.
     *
     * The list prefers [roleForPub]. This remains the fallback when
     * the key is not an account xpub.
     */
    fun roleForPubLabel(label: String?): Role {
        val normalized = label?.trim()?.lowercase(Locale.US).orEmpty()
        return when {
            normalized == "bad bank" || normalized == "badbank" -> Role.BAD_BANK
            normalized == "premix" -> Role.PREMIX
            normalized == "deposit" || normalized.startsWith("deposit ") -> Role.DEPOSIT
            else -> Role.OTHER
        }
    }

    /**
     * Account role from a BIP32 account index. Deposit is 0, Bad Bank
     * is 2^31−4, Premix is 2^31−3. Postmix and every other index are
     * [Role.OTHER]: a Tx0 there keeps the stored text.
     */
    fun roleForAccount(
        account: Long,
        config: WhirlpoolConfig = WhirlpoolConfig.DEFAULT,
    ): Role = when (account) {
        config.badbankAccount -> Role.BAD_BANK
        config.premixAccount -> Role.PREMIX
        config.depositAccount -> Role.DEPOSIT
        else -> Role.OTHER
    }

    /**
     * Role for one collection pub. A decoded account-level xpub wins,
     * so renaming "Bad Bank" or "Premix" does not drop the Tx0
     * display, and labeling Postmix "Bad Bank" does not invent one.
     * A single address, a non-account key, or a key that does not
     * decode falls back to [roleForPubLabel].
     */
    fun roleForPub(
        pubKey: String?,
        label: String?,
        config: WhirlpoolConfig = WhirlpoolConfig.DEFAULT,
    ): Role {
        val account = accountIndexOf(pubKey) ?: return roleForPubLabel(label)
        return roleForAccount(account, config)
    }

    /**
     * BIP32 account index of an account-level xpub (depth 3,
     * `m/purpose'/coin'/account'`), or null. The child field is the
     * hardened index as a signed int; adding 2^31 recovers the same
     * account the collection tabs use (`xpub.child + 2^31`).
     */
    fun accountIndexOf(pubKey: String?): Long? {
        if (pubKey.isNullOrBlank()) return null
        return try {
            val xpub = XPUB(pubKey)
            xpub.decode()
            if (xpub.depth.toInt() != ACCOUNT_XPUB_DEPTH) return null
            xpub.child.toLong() + HARDENED
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Text to show for one row.
     *
     * @param storedLabel text from label_entries, or null when the tx has none
     * @param origin provenance of that row; null means the user typed it
     * @param role account of the row being shown
     * @param showAuto false hides every `auto:` transaction label.
     *        Manual and imported rows are unchanged.
     */
    fun display(
        storedLabel: String?,
        origin: String?,
        role: Role,
        config: WhirlpoolConfig = WhirlpoolConfig.DEFAULT,
        showAuto: Boolean = true,
    ): String? {
        if (storedLabel == null) return null
        if (!showAuto && isAutoOrigin(origin)) return null
        if (!isAutoTx0(storedLabel, origin, config)) return storedLabel
        return when (role) {
            Role.DEPOSIT -> config.labels.tx0
            Role.PREMIX -> PREMIX_DISPLAY
            Role.BAD_BANK -> config.labels.badbank
            Role.OTHER -> storedLabel
        }
    }

    /**
     * True only for the auto-written Tx0 label. Manual and imported
     * rows fail the policy check and must be shown as typed.
     */
    private fun isAutoTx0(
        storedLabel: String,
        origin: String?,
        config: WhirlpoolConfig,
    ): Boolean {
        if (storedLabel != config.labels.tx0) return false
        return WhirlpoolLabelPolicy.decide(ExistingLabel(storedLabel, origin)) ==
            WhirlpoolLabelPolicy.Decision.Write
    }

    private fun isAutoOrigin(origin: String?): Boolean =
        origin?.startsWith(WhirlpoolLabelPolicy.AUTO_NAMESPACE) == true

    private const val HARDENED = 2_147_483_648L

    /** Account xpubs sit at depth 3. A purpose or coin key is not an account. */
    private const val ACCOUNT_XPUB_DEPTH = 3
}
