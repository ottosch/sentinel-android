package com.samourai.sentinel.ui.dojo

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.annotations.SerializedName
import com.samourai.sentinel.helpers.toJSON
import com.samourai.wallet.bip47.rpc.PaymentCode
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.params.TestNet3Params
import org.bouncycastle.util.encoders.Base64

/**
 * Client-side checks of the BIP47 signatures a Dojo Bay publishes, so the app
 * does not have to trust the directory to have checked them.
 *
 * This mirrors Dojo Bay's own verifier (github.com/Dojobay/dojobay,
 * server/crypto.ts at a3125f5):
 *  - Signatures are Bitcoin signed messages ("Bitcoin Signed Message:\n"
 *    prefix, the same on mainnet and testnet), armored in BEGIN/END BITCOIN
 *    SIGNED MESSAGE blocks, made with the BIP47 notification key of the
 *    signer's payment code (PayNym → Sign message).
 *  - A payment code may sign from either derivation of its notification
 *    address, mainnet or testnet (notificationAddresses, crypto.ts:141).
 *  - A listing's `signed` block covers the pairing JSON
 *    `{"pairing":…,"explorer":…}` plus a trailing "BIP47:" line with the
 *    signer's code (parseSignedBlock, crypto.ts:211; verifySignedPayload,
 *    crypto.ts:263). The JSON is parsed strictly and compared structurally,
 *    key order and whitespace ignored but every field, type and null kept
 *    (sameSignedPayload and stableStringify, crypto.ts:246), against the
 *    listing's `{"pairing":…,"explorer":…}` exactly as served
 *    (canonicalPairing, crypto.ts:131). The one leniency the app adds: a
 *    listing whose string `pairing.version` differs from the signed string
 *    version, and nothing else, is accepted as version drift and paired
 *    with the signed version (fix-payload-version.mjs).
 *  - The instance binding, data/operator.json, signs "<onion URL>/", a blank
 *    line, then "BIP47: <code>" (verifyOperatorDoc, crypto.ts:370), and must
 *    name the address it was fetched from (crypto.ts:396). URLs are compared
 *    as Dojo Bay compares them: trimmed, trailing slashes dropped, case
 *    sensitive.
 *  - A signature must decode to exactly 65 bytes (bitcoinjs-message).
 *
 * Dojo Bay's signatures carry no timestamp or nonce (README: "The signature
 * never expires"), so freshness cannot be proven; see [NodeCheck.Verified].
 */
object DojoBaySignatures {

    // ---- block parsing (crypto.ts:211-227 and :378-381) -----------------

    data class SignedBlock(
        /** Everything the signature covers. */
        val message: String,
        val address: String,
        val signature: String,
    )

    /**
     * The longest signed block read, in characters. The block regexes below
     * are kept exactly as crypto.ts has them, and they backtrack, so time
     * grows with the square of the input; real blocks are well under 1 KB
     * (the longest in the vectors is 693 characters). A longer block is
     * refused before any regex runs.
     */
    const val MAX_SIGNED_CHARS = 4 * 1024

    /** A BIP47 payment code: "PM8T" and 112 more Base58 characters (116 in all). */
    private val PAYMENT_CODE_SHAPE = Regex("^PM8T[1-9A-HJ-NP-Za-km-z]{112}$")

    /** True when [code] is shaped like a payment code (shape only, no decode). */
    fun isPaymentCodeShape(code: String?): Boolean = code != null && PAYMENT_CODE_SHAPE.matches(code)

    private val LISTING_MSG = Regex("BEGIN BITCOIN SIGNED MESSAGE-----\\n([\\s\\S]*?)\\n-----BEGIN BITCOIN SIGNATURE")
    private val LISTING_SIG = Regex("\\n([A-Za-z0-9+/=]{80,})\\n-----END BITCOIN SIGNATURE")
    // verifyOperatorDoc tolerates a swallowed newline after BEGIN and blank lines before END.
    private val CLAIM_MSG = Regex("BEGIN BITCOIN SIGNED MESSAGE-----[ \\t]*\\n?([\\s\\S]*?)\\n-----BEGIN BITCOIN SIGNATURE")
    private val CLAIM_SIG = Regex("\\n([A-Za-z0-9+/=]{80,})\\n*-----END BITCOIN SIGNATURE")
    private val ADDRESS = Regex("Address:\\s*(\\S+)")
    private val LISTING_TAIL = Regex("^([\\s\\S]*?)\\n\\s*BIP47:\\s*\\n?(\\S+)$")
    private val CLAIM_BIP47 = Regex("BIP47:\\s*(PM8T[1-9A-HJ-NP-Za-km-z]+)")

    /** A listing block: message trimmed, as parseSignedBlock does. */
    fun parseListingBlock(text: String?): SignedBlock? {
        if (text.isNullOrEmpty() || text.length > MAX_SIGNED_CHARS) return null
        val t = text.replace("\r\n", "\n")
        val msg = LISTING_MSG.find(t)?.groupValues?.get(1) ?: return null
        val addr = ADDRESS.find(t)?.groupValues?.get(1) ?: return null
        val sig = LISTING_SIG.find(t)?.groupValues?.get(1) ?: return null
        return SignedBlock(msg.trim(), addr.trim(), sig.trim())
    }

    /** An operator/URL claim block: trailing newlines dropped, as verifyOperatorDoc does. */
    fun parseClaimBlock(text: String?): SignedBlock? {
        if (text.isNullOrEmpty() || text.length > MAX_SIGNED_CHARS) return null
        val t = text.replace("\r\n", "\n")
        val msg = CLAIM_MSG.find(t)?.groupValues?.get(1) ?: return null
        val addr = ADDRESS.find(t)?.groupValues?.get(1) ?: return null
        val sig = CLAIM_SIG.find(t)?.groupValues?.get(1) ?: return null
        return SignedBlock(msg.trimEnd('\n'), addr.trim(), sig.trim())
    }

    // ---- primitives ------------------------------------------------------

    /**
     * Mainnet and testnet notification addresses of [paymentCode]; empty if
     * it is not a valid code. Anything not shaped like a payment code
     * ([PAYMENT_CODE_SHAPE]) is refused before the Base58 decode, which takes
     * time growing with the square of the input length.
     */
    fun notificationAddresses(paymentCode: String?): List<String> {
        if (paymentCode.isNullOrBlank()) return emptyList()
        val trimmed = paymentCode.trim()
        if (!PAYMENT_CODE_SHAPE.matches(trimmed)) return emptyList()
        val code = try {
            PaymentCode(trimmed).takeIf { it.isValid }
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        val out = ArrayList<String>(2)
        for (params in listOf<NetworkParameters>(MainNetParams.get(), TestNet3Params.get())) {
            try {
                val a = code.notificationAddress(params).addressString
                if (a !in out) out.add(a)
            } catch (e: Exception) {
                // skip, like crypto.ts:144
            }
        }
        return out
    }

    /** Bitcoin signed-message check against a legacy (P2PKH) address, the kind a notification key signs from. */
    fun verifyMessage(message: String, address: String, signature: String): Boolean {
        val params: NetworkParameters = when (address.firstOrNull()) {
            '1' -> MainNetParams.get()
            'm', 'n' -> TestNet3Params.get()
            else -> return false
        }
        return try {
            // bitcoinjs-message requires exactly 65 bytes; ECKey would
            // ignore trailing bytes.
            if (Base64.decode(signature).size != 65) return false
            ECKey.signedMessageToKey(message, signature).toAddress(params).toString() == address
        } catch (e: Exception) {
            false
        }
    }

    // ---- listings --------------------------------------------------------

    enum class Failure {
        /** No `signed` block. Dojo Bay itself refuses to publish these (build-public.ts:228). */
        MISSING_SIGNATURE,
        UNREADABLE_BLOCK,
        BAD_SIGNATURE,
        /** The signer is not the notification address of the code in the block (or of the listed code). */
        SIGNER_NOT_CODE,
        /** The listing's pairing details are not the ones that were signed. */
        PAYLOAD_MISMATCH,
        /** The signed text is not a pairing payload. */
        NOT_A_PAIRING,
    }

    sealed class NodeCheck {
        /**
         * [paymentCode] is the code the signature proves, which is what the
         * app shows as the owner; the directory's own `paymentCode` and
         * `name` fields are not signed. [signedPayloadJson] is the pairing
         * JSON rebuilt from inside the signed block, and is what the app
         * pairs with - never the directory's unsigned copy.
         *
         * Not proven: that this is the owner's latest payload. With no
         * timestamp in Dojo Bay's format, a directory could serve an older
         * block the same owner once signed.
         */
        data class Verified(
            val paymentCode: String,
            val signedPayloadJson: String,
            /** True when the listing differs from the signed payload only in pairing.version (informational). */
            val versionDrift: Boolean,
        ) : NodeCheck()

        data class Unverified(val failure: Failure) : NodeCheck()
    }

    fun checkNode(node: CommunityDojoNode): NodeCheck {
        val block = node.signed?.takeIf { it.isNotBlank() }
            ?: return NodeCheck.Unverified(Failure.MISSING_SIGNATURE)
        val parsed = parseListingBlock(block) ?: return NodeCheck.Unverified(Failure.UNREADABLE_BLOCK)
        if (!verifyMessage(parsed.message, parsed.address, parsed.signature)) {
            return NodeCheck.Unverified(Failure.BAD_SIGNATURE)
        }
        val tail = LISTING_TAIL.find(parsed.message)
        val pairingText = tail?.groupValues?.get(1)?.trim() ?: parsed.message
        // Whose signature this is. With a BIP47 line, the code in the signed
        // text must own the signing address (crypto.ts:281-289). Without one,
        // the listed code must own it - the role the authenticated session
        // plays on the server (crypto.ts:293-296).
        val signerCode = tail?.groupValues?.get(2) ?: node.paymentCode
        if (signerCode.isNullOrBlank() || parsed.address !in notificationAddresses(signerCode)) {
            return NodeCheck.Unverified(Failure.SIGNER_NOT_CODE)
        }
        // JSON.parse, strictly: no single quotes, comments or trailing data.
        val signedTree = parseStrictJson(pairingText) as? JsonObject
            ?: return NodeCheck.Unverified(Failure.NOT_A_PAIRING)
        val signed = try {
            Gson().fromJson(signedTree, CommunityDojoPayload::class.java)
        } catch (e: Exception) {
            null
        }
        val signedPairing = signed?.pairing
        if (signedPairing == null || signedPairing.url.isNullOrBlank()) {
            return NodeCheck.Unverified(Failure.NOT_A_PAIRING)
        }
        // canonicalPairing(node.payload): only "pairing" and "explorer", as
        // served; an absent key stays absent and a null stays null.
        val listedRaw = node.payload?.rawOrModel() as? JsonObject
        val expected = JsonObject().also { o ->
            listedRaw?.get("pairing")?.let { o.add("pairing", it) }
            listedRaw?.get("explorer")?.let { o.add("explorer", it) }
        }
        // A number the comparison can't take (stable() is null) refuses the
        // listing: in the signed text it is not a pairing we read, in the
        // listed copy it can't match what was signed.
        val signedKey = stable(signedTree) ?: return NodeCheck.Unverified(Failure.NOT_A_PAIRING)
        val listedKey = stable(expected) ?: return NodeCheck.Unverified(Failure.PAYLOAD_MISMATCH)
        var drift = false
        if (signedKey != listedKey) {
            if (isVersionDrift(signedTree, expected)) {
                drift = true
            } else {
                return NodeCheck.Unverified(Failure.PAYLOAD_MISMATCH)
            }
        }
        val pairingJson = DojoPairing(pairing = signedPairing, explorer = signed.explorer).toJSON()
            ?: return NodeCheck.Unverified(Failure.NOT_A_PAIRING)
        return NodeCheck.Verified(signerCode, pairingJson, drift)
    }

    /**
     * One JSON value and nothing after it, as JSON.parse reads it
     * ([DojoBayJson]: Strictness.STRICT, nesting limit, no byte-order mark);
     * null if [text] is not one.
     */
    internal fun parseStrictJson(text: String): JsonElement? = DojoBayJson.parseTree(text)

    /** The longest number literal [stable] reads; real pairings carry no numbers at all. */
    const val MAX_NUMBER_CHARS = 32

    /** At most 3 exponent digits: |exponent| < 1000, past anything a double holds. */
    const val MAX_EXPONENT_DIGITS = 3

    /**
     * A number literal by value, as a JavaScript number (an IEEE double):
     * "1", "1.0" and "1e0" are the same, and -0 is 0, as JSON.stringify
     * writes them. The literal is converted straight to a double, never
     * expanded into decimal digits, so "1e100000000" costs nothing. Null for
     * a literal longer than [MAX_NUMBER_CHARS], with an exponent of more than
     * [MAX_EXPONENT_DIGITS] digits, or one that is not a finite double
     * (JavaScript would turn 1e400 into Infinity, which JSON.stringify writes
     * as null): such numbers are refused, not compared.
     */
    internal fun numberKey(literal: String): String? {
        if (literal.length > MAX_NUMBER_CHARS) return null
        val exponent = literal.substringAfter('e', literal.substringAfter('E', "")).trimStart('+', '-').trimStart('0')
        if (exponent.length > MAX_EXPONENT_DIGITS) return null
        val d = literal.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        return if (d == 0.0) "0" else d.toString()
    }

    /**
     * stableStringify (crypto.ts): keys sorted at every level, values typed.
     * Numbers compare by value, like JavaScript numbers ([numberKey]). Null
     * when the tree holds a number [numberKey] refuses.
     */
    internal fun stable(e: JsonElement?): String? {
        return when {
            e == null || e is JsonNull -> "null"
            e is JsonArray -> e.map { stable(it) ?: return null }.joinToString(",", "[", "]")
            e is JsonObject -> e.keySet().sorted()
                .map { k -> JsonPrimitive(k).toString() + ":" + (stable(e.get(k)) ?: return null) }
                .joinToString(",", "{", "}")
            e is JsonPrimitive && e.isNumber -> numberKey(e.asString)?.let { "n:$it" }
            e is JsonPrimitive && e.isBoolean -> "b:" + e.asBoolean
            else -> "s:" + JsonPrimitive(e.asString).toString()
        }
    }

    /** Only pairing.version differs, and it is a string on both sides. */
    private fun isVersionDrift(signed: JsonObject, listed: JsonObject): Boolean {
        val sp = signed.get("pairing") as? JsonObject ?: return false
        val lp = listed.get("pairing") as? JsonObject ?: return false
        val sv = sp.get("version") as? JsonPrimitive ?: return false
        val lv = lp.get("version") as? JsonPrimitive ?: return false
        if (!sv.isString || !lv.isString) return false
        fun withoutVersion(o: JsonObject, p: JsonObject) = o.deepCopy().also { c ->
            c.add("pairing", p.deepCopy().also { it.remove("version") })
        }
        val a = stable(withoutVersion(signed, sp)) ?: return false
        return a == stable(withoutVersion(listed, lp))
    }

    // ---- the directory's own identity (data/operator.json) ---------------

    data class OperatorDoc(
        @SerializedName("onion") val onion: String? = null,
        @SerializedName("paymentCode") val paymentCode: String? = null,
        @SerializedName("verifySigned") val verifySigned: String? = null,
    )

    enum class OperatorFailure { MISSING, UNREADABLE_BLOCK, WRONG_ADDRESS, CODE_MISMATCH, SIGNER_NOT_CODE, BAD_SIGNATURE }

    sealed class OperatorCheck {
        data class Verified(val paymentCode: String) : OperatorCheck()
        data class Failed(val failure: OperatorFailure) : OperatorCheck()
    }

    /** verifyOperatorDoc's norm(): trimmed, trailing slashes dropped, case kept. */
    private fun normUrl(u: String?): String = u.orEmpty().trim().trimEnd('/')

    /**
     * verifyOperatorDoc with expectedOnion = the base address the document
     * was fetched from. Trailing slashes are ignored; case is not.
     */
    fun checkOperator(doc: OperatorDoc?, fetchedFromBaseUrl: String): OperatorCheck {
        val code = doc?.paymentCode?.takeIf { it.isNotBlank() }
        if (doc == null || code == null || doc.verifySigned.isNullOrBlank()) {
            return OperatorCheck.Failed(OperatorFailure.MISSING)
        }
        val parsed = parseClaimBlock(doc.verifySigned) ?: return OperatorCheck.Failed(OperatorFailure.UNREADABLE_BLOCK)
        val firstLine = parsed.message.split("\n").first().trim()
        if (normUrl(firstLine) != normUrl(doc.onion) || normUrl(doc.onion) != normUrl(fetchedFromBaseUrl)) {
            return OperatorCheck.Failed(OperatorFailure.WRONG_ADDRESS)
        }
        val bip = CLAIM_BIP47.find(parsed.message)?.groupValues?.get(1)
        if (bip == null || bip != code) return OperatorCheck.Failed(OperatorFailure.CODE_MISMATCH)
        if (parsed.address !in notificationAddresses(code)) return OperatorCheck.Failed(OperatorFailure.SIGNER_NOT_CODE)
        if (!verifyMessage(parsed.message, parsed.address, parsed.signature)) {
            return OperatorCheck.Failed(OperatorFailure.BAD_SIGNATURE)
        }
        return OperatorCheck.Verified(code)
    }

    /** `PM8TJf…cD9` for the screen. */
    fun shortCode(code: String): String =
        if (code.length <= 16) code else code.take(8) + "…" + code.takeLast(6)
}
