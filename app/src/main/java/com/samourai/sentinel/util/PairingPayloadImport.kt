package com.samourai.sentinel.util

import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.wallet.crypto.AESUtil
import com.samourai.wallet.util.CharSequenceX
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * #399: reading a Samourai/Ashigaru pairing payload, and nothing else.
 *
 * The outer envelope is `{ external, payload, version? }`. `external` is
 * either the format id string Ashigaru writes (`samourai.wallet.watchonly`)
 * or an object carrying a version; `version` beside it is the pairing
 * version (a string). A numeric `version` is the encryption envelope
 * (BackupEncrypted), not an app version, and is ignored.
 *
 * Decrypt failure and every post-decrypt failure are different results.
 * The legacy `wallet` object (`fingerprint` / `accounts` /
 * `bip49_accounts` / `bip84_accounts`) is rejected, not parsed: the
 * sample that shape was rebuilt from was synthetic.
 *
 * Nothing here is logged. Payloads, passwords and xpubs stay out of
 * messages; a skipped slot is named by its role, never by its key.
 */

/** Pairing version this build fully reads: the 7-slot watch-only array. */
internal const val SUPPORTED_ASHIGARU_PAIRING_VERSION = "1.0.0"

internal enum class PairingImportFailure {
    /** Ciphertext did not open. The password (or the blob) is wrong. */
    DECRYPT_FAILED,

    /** Opened, and the plaintext is not JSON. */
    NOT_JSON,

    /** Opened JSON, and it is neither watch-only nor the legacy wallet shape. */
    UNRECOGNISED,

    /** The older `wallet` export. Named and refused. */
    LEGACY_FORMAT,

    /** Watch-only was readable, and every key slot was missing or invalid. */
    NO_VALID_KEYS
}

internal enum class PairingSlotProblem {
    MISSING,
    INVALID_XPUB,
    UNRECOGNISED
}

internal data class PairingSlotSkip(
    val name: String,
    val problem: PairingSlotProblem,
    /** 1-based index of an entry past the known slots. Named at display time. */
    val extraIndex: Int? = null
)

/**
 * One attempt to read a pairing envelope.
 *
 * [collection] is set only when at least one key validated. A newer
 * declared Ashigaru version is recorded only when the plaintext did
 * not fully parse; a complete 7-slot read stays quiet.
 */
internal data class PairingImportOutcome(
    val collection: PubKeyCollection? = null,
    val failure: PairingImportFailure? = null,
    val newerAshigaruVersion: String? = null,
    val skipped: List<PairingSlotSkip> = emptyList()
)

internal interface PairingImportCopy {
    val wrongPassword: String
    val notJson: String
    val unrecognised: String
    val legacy: String
    val noKeys: String
    fun newerAshigaru(version: String): String
    fun skippedImported(list: String): String
    fun skippedOnly(list: String): String
    fun slot(name: String, problem: String): String
    fun extraEntry(index: Int): String
    val missing: String
    val invalidXpub: String
    val unrecognisedEntry: String
}

internal object EnglishPairingImportCopy : PairingImportCopy {
    override val wrongPassword = "Incorrect password, please try again."
    override val notJson = "The decrypted pairing data is not valid JSON."
    override val unrecognised = "This pairing payload isn't in a format Sentinel recognises."
    override val legacy = "This is an older pairing format this version of Sentinel does not support."
    override val noKeys = "This pairing payload has no valid keys to import."
    override fun newerAshigaru(version: String) =
        "This pairing was created by a newer version of Ashigaru (v$version) than this version of Sentinel supports. Please update Sentinel."
    override fun skippedImported(list: String) =
        "Imported the keys that could be read. Skipped: $list."
    override fun skippedOnly(list: String) = "Skipped: $list."
    override fun slot(name: String, problem: String) = "$name ($problem)"
    override fun extraEntry(index: Int) = "Entry $index"
    override val missing = "missing"
    override val invalidXpub = "not a valid XPUB"
    override val unrecognisedEntry = "unrecognised entry"
}

/**
 * The sentence for this outcome, or null when the read was complete
 * and there is nothing to tell the user. Decrypt failure and the
 * legacy shape are each a single sentence. A newer Ashigaru version
 * leads, and replaces the generic unrecognised sentence; other facts
 * (not JSON, no keys, skipped slots) stay attached so they are not
 * folded into that one line.
 */
internal fun pairingImportMessage(
    outcome: PairingImportOutcome,
    copy: PairingImportCopy = EnglishPairingImportCopy
): String? {
    when (outcome.failure) {
        PairingImportFailure.DECRYPT_FAILED -> return copy.wrongPassword
        PairingImportFailure.LEGACY_FORMAT -> return copy.legacy
        else -> Unit
    }
    val parts = ArrayList<String>()
    outcome.newerAshigaruVersion?.let { parts += copy.newerAshigaru(it) }
    when (outcome.failure) {
        PairingImportFailure.NOT_JSON -> parts += copy.notJson
        PairingImportFailure.UNRECOGNISED ->
            if (outcome.newerAshigaruVersion == null) parts += copy.unrecognised
        PairingImportFailure.NO_VALID_KEYS -> parts += copy.noKeys
        else -> Unit
    }
    if (outcome.skipped.isNotEmpty() &&
        outcome.failure != PairingImportFailure.NOT_JSON &&
        outcome.failure != PairingImportFailure.UNRECOGNISED
    ) {
        val list = outcome.skipped.joinToString(", ") { skip ->
            val name = skip.extraIndex?.let { copy.extraEntry(it) } ?: skip.name
            val problem = when (skip.problem) {
                PairingSlotProblem.MISSING -> copy.missing
                PairingSlotProblem.INVALID_XPUB -> copy.invalidXpub
                PairingSlotProblem.UNRECOGNISED -> copy.unrecognisedEntry
            }
            copy.slot(name, problem)
        }
        parts += if (outcome.collection != null) copy.skippedImported(list) else copy.skippedOnly(list)
    }
    if (parts.isEmpty()) return null
    return parts.joinToString(" ")
}

/**
 * Tries [passwords] in order. A password that opens the ciphertext
 * stops the search, even when the plaintext then fails to parse —
 * falling through to the next candidate used to report that as a
 * wrong password.
 */
internal fun readSamouraiPairingPayloadTrying(
    envelope: String,
    passwords: List<String>
): PairingImportOutcome {
    var last = PairingImportOutcome(failure = PairingImportFailure.DECRYPT_FAILED)
    val tried = passwords.filter { it.isNotEmpty() }.distinct()
    if (tried.isEmpty()) return last
    for (password in tried) {
        val outcome = readSamouraiPairingPayload(envelope, password)
        last = outcome
        if (outcome.failure != PairingImportFailure.DECRYPT_FAILED) return outcome
    }
    return last
}

internal fun readSamouraiPairingPayload(envelope: String, password: String): PairingImportOutcome {
    val outer = try {
        JSONObject(envelope)
    } catch (e: JSONException) {
        return PairingImportOutcome(failure = PairingImportFailure.UNRECOGNISED)
    }
    val declared = declaredAshigaruPairingVersion(outer)
    val payload = if (outer.has("payload") && !outer.isNull("payload")) outer.optString("payload", "") else ""
    if (payload.isEmpty()) {
        return unrecognized(declared, decrypted = false)
    }
    val decrypted = try {
        AESUtil.decrypt(payload, CharSequenceX(password), AESUtil.DefaultPBKDF2Iterations)
    } catch (e: Exception) {
        return PairingImportOutcome(failure = PairingImportFailure.DECRYPT_FAILED)
    }
    return parseDecryptedPairing(decrypted, declared)
}

/**
 * String version from an `external` object, otherwise the sibling
 * string `version`. Numbers are not pairing versions.
 */
internal fun declaredAshigaruPairingVersion(outer: JSONObject): String? {
    if (outer.has("external") && !outer.isNull("external")) {
        val external = outer.get("external")
        if (external is JSONObject) {
            stringVersion(external)?.let { return it }
        }
    }
    return stringVersion(outer)
}

internal fun isNewerPairingVersion(
    declared: String?,
    supported: String = SUPPORTED_ASHIGARU_PAIRING_VERSION
): Boolean {
    val left = versionParts(declared) ?: return false
    val right = versionParts(supported) ?: return false
    val width = maxOf(left.size, right.size)
    for (i in 0 until width) {
        val d = left.getOrElse(i) { 0 }
        val s = right.getOrElse(i) { 0 }
        if (d != s) return d > s
    }
    return false
}

private fun parseDecryptedPairing(decrypted: String, declared: String?): PairingImportOutcome {
    val trimmed = decrypted.trim()
    val json = try {
        JSONObject(trimmed)
    } catch (e: JSONException) {
        val jsonArray = try {
            JSONArray(trimmed)
            true
        } catch (ignored: JSONException) {
            false
        }
        return if (jsonArray) {
            unrecognized(declared, decrypted = true)
        } else {
            notJson(declared)
        }
    }
    val watchOnly = if (json.has("watch-only")) json.optJSONArray("watch-only") else null
    if (watchOnly != null) {
        return parseWatchOnly(watchOnly, declared)
    }
    if (isLegacyWalletShape(json)) {
        return PairingImportOutcome(failure = PairingImportFailure.LEGACY_FORMAT)
    }
    return unrecognized(declared, decrypted = true)
}

private data class KeySlot(val index: Int, val label: String, val type: AddressTypes)

/** Positions in the watch-only array. Index 0 is the fingerprint, not a key. */
private val KEY_SLOTS = listOf(
    KeySlot(1, "Deposit BIP44", AddressTypes.BIP44),
    KeySlot(2, "Deposit BIP49", AddressTypes.BIP49),
    KeySlot(3, "Deposit BIP84", AddressTypes.BIP84),
    KeySlot(4, "Premix", AddressTypes.BIP84),
    KeySlot(5, "Postmix", AddressTypes.BIP84),
    KeySlot(6, "Bad Bank", AddressTypes.BIP84)
)

/**
 * Stored order is the one the edit list already assumes: BIP84, the
 * Whirlpool accounts, then BIP44 and BIP49. The last two are the rows
 * the wallet-import key count hides.
 */
private val STORE_ORDER = listOf(3, 4, 5, 6, 1, 2)

private fun parseWatchOnly(array: JSONArray, declared: String?): PairingImportOutcome {
    val fingerprint = if (array.length() > 0 && !array.isNull(0)) {
        array.opt(0)?.toString()?.trim().orEmpty()
    } else {
        ""
    }
    val skipped = ArrayList<PairingSlotSkip>()
    val byIndex = HashMap<Int, PubKeyModel>()
    for (slot in KEY_SLOTS) {
        if (slot.index >= array.length()) {
            skipped += PairingSlotSkip(slot.label, PairingSlotProblem.MISSING)
            continue
        }
        if (array.isNull(slot.index)) {
            skipped += PairingSlotSkip(slot.label, PairingSlotProblem.INVALID_XPUB)
            continue
        }
        val value = array.opt(slot.index)?.toString()?.trim().orEmpty()
        if (FormatsUtil.isValidXpub(value)) {
            byIndex[slot.index] = PubKeyModel(
                pubKey = value,
                label = slot.label,
                type = slot.type,
                fingerPrint = fingerprint
            )
        } else {
            skipped += PairingSlotSkip(slot.label, PairingSlotProblem.INVALID_XPUB)
        }
    }
    for (index in 7 until array.length()) {
        skipped += PairingSlotSkip(
            name = "",
            problem = PairingSlotProblem.UNRECOGNISED,
            extraIndex = index + 1
        )
    }
    val fullyParsed = skipped.isEmpty()
    val newer = newerVersionOrNull(declared, fullyParsed)
    if (byIndex.isEmpty()) {
        return PairingImportOutcome(
            failure = PairingImportFailure.NO_VALID_KEYS,
            newerAshigaruVersion = newer,
            skipped = skipped
        )
    }
    val collection = PubKeyCollection(collectionLabel = "My Wallet", isImportFromWallet = true)
    for (index in STORE_ORDER) {
        byIndex[index]?.let { collection.pubs.add(it) }
    }
    return PairingImportOutcome(
        collection = collection,
        newerAshigaruVersion = newer,
        skipped = skipped
    )
}

private fun isLegacyWalletShape(json: JSONObject): Boolean {
    val wallet = json.optJSONObject("wallet") ?: return false
    return wallet.has("fingerprint") ||
        wallet.has("accounts") ||
        wallet.has("bip49_accounts") ||
        wallet.has("bip84_accounts")
}

/** Newer-version copy applies only after the ciphertext has opened. */
private fun unrecognized(declared: String?, decrypted: Boolean): PairingImportOutcome {
    return PairingImportOutcome(
        failure = PairingImportFailure.UNRECOGNISED,
        newerAshigaruVersion = if (decrypted) newerVersionOrNull(declared, fullyParsed = false) else null
    )
}

private fun notJson(declared: String?): PairingImportOutcome {
    return PairingImportOutcome(
        failure = PairingImportFailure.NOT_JSON,
        newerAshigaruVersion = newerVersionOrNull(declared, fullyParsed = false)
    )
}

private fun newerVersionOrNull(declared: String?, fullyParsed: Boolean): String? {
    if (fullyParsed) return null
    val label = displayVersion(declared) ?: return null
    if (!isNewerPairingVersion(label)) return null
    return label
}

private fun displayVersion(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val label = raw.trim().removePrefix("v").removePrefix("V").trim()
    if (label.isEmpty() || versionParts(label) == null) return null
    return label
}

private fun stringVersion(json: JSONObject): String? {
    for (key in listOf("version", "version_name", "versionName")) {
        if (!json.has(key) || json.isNull(key)) continue
        val value = json.get(key)
        if (value is String && value.isNotBlank()) return value.trim()
    }
    return null
}

private fun versionParts(raw: String?): List<Int>? {
    val label = raw?.trim()?.removePrefix("v")?.removePrefix("V")?.trim() ?: return null
    if (label.isEmpty()) return null
    val numeric = label.takeWhile { it.isDigit() || it == '.' }.trim('.')
    if (numeric.isEmpty()) return null
    val parts = numeric.split('.').filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    return parts.map { it.toIntOrNull() ?: return null }
}
