package com.samourai.sentinel.ui.dojo

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import com.google.gson.internal.bind.TypeAdapters
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.samourai.sentinel.data.txfacts.TxFactsResolver
import com.samourai.sentinel.helpers.toJSON
import com.samourai.sentinel.ui.webview.ExplorerBinding

/**
 * Response shape of the Dojo Bay community directory
 * (<directory>/data/dojos.json; PublicNode in Dojo Bay's types.d.ts).
 */
data class CommunityDojoDirectory(
    @SerializedName("generated_at")
    val generatedAt: String? = null,
    @SerializedName("nodes")
    val nodes: List<CommunityDojoNode>? = null
)

data class CommunityDojoNode(
    @SerializedName("id")
    val id: String? = null,
    @SerializedName("network")
    val network: String? = null,
    @SerializedName("name")
    val name: String? = null,
    @SerializedName("status")
    val status: String? = null,
    @SerializedName("jurisdiction")
    val jurisdiction: String? = null,
    @SerializedName("country")
    val country: String? = null,
    @SerializedName("version")
    val version: String? = null,
    @SerializedName("checked_at")
    val checkedAt: String? = null,
    @SerializedName("payload")
    val payload: CommunityDojoPayload? = null,
    // Dojo Bay's display name and code for the owner. Not signed; the app
    // shows the code the signature proves instead (DojoBaySignatures).
    @SerializedName("paynym")
    val paynym: String? = null,
    @SerializedName("paymentCode")
    val paymentCode: String? = null,
    // The owner's BIP47-signed pairing block (PublicNode.signed in Dojo
    // Bay's types.d.ts).
    @SerializedName("signed")
    val signed: String? = null,
) {
    val isOnline: Boolean
        get() = status.equals("active", ignoreCase = true)

    /**
     * The pairing payload as the exact `{"pairing":{...}}` JSON string that
     * [DojoUtility.validate] and the rest of the existing pairing flow expect,
     * built from the same [Pairing] model used for manual/QR pairing.
     */
    fun pairingPayloadJson(): String? {
        val pairing = payload?.pairing ?: return null
        return DojoPairing(pairing = pairing, explorer = payload.explorer).toJSON()
    }

    /**
     * Renders [country] (an ISO 3166-1 alpha-2 code, e.g. "SG") as its Unicode
     * flag emoji by combining the two Regional Indicator Symbol letters - null
     * for anything that isn't a plain two-letter code, rather than showing a
     * placeholder glyph for bad directory data.
     */
    val flagEmoji: String?
        get() {
            val code = country?.trim()?.uppercase() ?: return null
            if (code.length != 2 || code.any { it !in 'A'..'Z' }) return null
            val regionalIndicatorBase = 0x1F1E6 // Regional Indicator Symbol Letter A
            return code.map { letter ->
                String(Character.toChars(regionalIndicatorBase + (letter - 'A')))
            }.joinToString("")
        }
}

/**
 * One community-list line: version, jurisdiction, online or offline,
 * then that Dojo's block explorer when its host ends in .onion.
 * The explorer goes through [ExplorerBinding.truncateExplorerLabel],
 * the same cut the Network Block Explorer row uses. A missing
 * explorer, or a clearnet one, shows no address.
 */
fun communityDojoSubtitle(node: CommunityDojoNode): String {
    val explorer = node.payload?.explorer?.url
        ?.takeIf { it.isNotBlank() && TxFactsResolver.isOnionHost(it) }
        ?.let { ExplorerBinding.truncateExplorerLabel(it) }
    return listOfNotNull(
        node.version?.takeIf { it.isNotBlank() }?.let { "v$it" },
        node.jurisdiction?.takeIf { it.isNotBlank() },
        if (node.isOnline) "online" else "offline",
        explorer,
    ).joinToString(" • ")
}

@JsonAdapter(CommunityDojoPayloadAdapter::class)
data class CommunityDojoPayload(
    @SerializedName("pairing")
    val pairing: Pairing? = null,
    // #119 Route A: most directory nodes also declare a sibling
    // explorer; carried into the reconstructed pairing JSON so the
    // pairing flow's capture sites see it.
    @SerializedName("explorer")
    val explorer: Explorer? = null
) {
    /**
     * The payload exactly as the directory served it (and as the saved list
     * stores it), so a listing is compared with its signed text as JSON,
     * the way Dojo Bay's sameSignedPayload does, with every field, type and
     * null kept. Null for a payload built in code; [rawOrModel] then falls
     * back to the model. Not part of equals or copy.
     */
    @Transient
    var raw: JsonElement? = null

    fun rawOrModel(): JsonElement = raw ?: JsonObject().also { o ->
        pairing?.let { o.add("pairing", Gson().toJsonTree(it)) }
        explorer?.let { o.add("explorer", Gson().toJsonTree(it)) }
    }
}

/** Reads the model and keeps the raw JSON beside it; writes the raw JSON back unchanged. */
class CommunityDojoPayloadAdapter : TypeAdapter<CommunityDojoPayload>() {
    private val plain = Gson()

    override fun write(out: JsonWriter, value: CommunityDojoPayload?) {
        if (value == null) {
            out.nullValue()
            return
        }
        TypeAdapters.JSON_ELEMENT.write(out, value.rawOrModel())
    }

    override fun read(input: JsonReader): CommunityDojoPayload? {
        val element = TypeAdapters.JSON_ELEMENT.read(input)
        if (element == null || element is JsonNull) return null
        val obj = element as? JsonObject
        val model = CommunityDojoPayload(
            pairing = obj?.get("pairing")?.takeIf { it.isJsonObject }
                ?.let { runCatching { plain.fromJson(it, Pairing::class.java) }.getOrNull() },
            explorer = obj?.get("explorer")?.takeIf { it.isJsonObject }
                ?.let { runCatching { plain.fromJson(it, Explorer::class.java) }.getOrNull() },
        )
        model.raw = element
        return model
    }
}
