package com.samourai.sentinel.ui.dojo

import java.net.URLEncoder

/**
 * How the app writes out a listing's signer: the full BIP47 payment code the
 * listing's signature proved. Never the directory's own `paymentCode` or
 * `paynym` fields, which are not signed.
 */
object SignerCodeFormat {

    /** Characters per group in [displayCode]. */
    const val GROUP = 4

    /** Groups per line in [displayCode]: 8 groups of 4 fit the dialog at 12sp. */
    const val GROUPS_PER_LINE = 8

    /** Characters kept at each end of [collapsedCode], as on Dojo Bay's cards. */
    const val COLLAPSED_ENDS = 8

    /**
     * The full code in groups of [GROUP], [GROUPS_PER_LINE] groups to a line.
     * Every character is kept: [compact] gives back the code exactly.
     */
    fun displayCode(code: String): String =
        code.chunked(GROUP)
            .chunked(GROUPS_PER_LINE)
            .joinToString("\n") { it.joinToString(" ") }

    /** The first and last [COLLAPSED_ENDS] characters around an ellipsis. */
    fun collapsedCode(code: String): String =
        if (code.length <= 2 * COLLAPSED_ENDS + 1) code
        else code.take(COLLAPSED_ENDS) + "…" + code.takeLast(COLLAPSED_ENDS)

    /** [displayCode] back to the code: whitespace removed. */
    fun compact(display: String): String = display.filterNot { it.isWhitespace() }
}

/**
 * The public PayNym (paynym.rs) onion and the links built from a signed code.
 * The app only ever reaches it over Tor; there is no clearnet fallback.
 */
object PayNymLinks {

    const val ONION_HOST = "paynym25chftmsywv4v2r67agbrr62lcxagsf4tymbzpeeucucy2ivad.onion"
    const val BASE_URL = "http://$ONION_HOST"

    private fun encoded(code: String): String = URLEncoder.encode(code, "UTF-8")

    /** The signer's PayNym page, or null when [signedCode] is not shaped like a payment code. */
    fun profileUrl(signedCode: String?): String? {
        val code = signedCode?.takeIf { DojoBaySignatures.isPaymentCodeShape(it) } ?: return null
        return "$BASE_URL/${encoded(code)}"
    }

    /** The avatar path on [ONION_HOST] for [code] (shape-checked by the caller). */
    fun avatarPath(code: String): String = "/${encoded(code)}/avatar"

    /** The PayNym name lookup path; the code goes in the POST body. */
    const val NYM_PATH = "/api/v1/nym"
}

/**
 * Listings that share a name but not a signer. A directory name is unsigned,
 * so anyone can list a Dojo under a name already in use; the app points it out
 * so the user checks the full code.
 */
object SignerNameCollisions {

    /** The name as compared: trimmed, case-folded. Null for a blank name. */
    fun key(name: String?): String? = name?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    /** The names (as [key]) used by verified listings with more than one signer code. */
    fun names(items: List<CheckedDojo>): Set<String> =
        items.mapNotNull { item ->
            val code = item.verified?.paymentCode ?: return@mapNotNull null
            val name = key(item.node.name) ?: return@mapNotNull null
            name to code
        }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.toSet().size > 1 }
            .keys

    /** True when [item] is a verified listing whose name another signer also uses. */
    fun collides(item: CheckedDojo, names: Set<String>): Boolean =
        item.verified != null && key(item.node.name)?.let { it in names } == true
}
