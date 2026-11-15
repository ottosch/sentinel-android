package com.samourai.sentinel.ui.dojo

import com.samourai.sentinel.ui.webview.ExplorerBinding
import org.bouncycastle.crypto.digests.SHA3Digest
import java.net.URI

/**
 * Which Dojo Bay directory the Community Dojos list reads, and the rules for
 * a user-entered one.
 *
 * Dojo Bay is something anyone can run, so the directory address is a user
 * setting. Only v3 onion addresses are accepted, over plain http: the fetch
 * always goes through the app's Tor proxy (see [CommunityDojoRepository]),
 * Tor already encrypts an onion connection end to end, and there is no
 * direct fallback. Clearnet addresses are refused outright, so a directory
 * never leaves the Tor network through an exit relay. No user info, query or
 * fragment either, so nothing extra rides the request.
 *
 * The address is the instance's root; [DojoBayConstants.DIRECTORY_PATH] is
 * read under it.
 */
object DojoBaySource {

    sealed class Validation {
        /** [baseUrl] is the normalized value to store. */
        data class Valid(val baseUrl: String) : Validation()
        data class Invalid(val reason: Reason) : Validation()
    }

    enum class Reason { EMPTY, MALFORMED, NOT_ONION, NOT_HTTP, BAD_ONION, BAD_PORT, HAS_EXTRAS }

    private val V3_ONION_LABEL = Regex("^[a-z2-7]{56}$")

    /** Normalizes and checks a user-entered directory address. */
    fun validate(input: String?): Validation {
        var raw = input?.trim().orEmpty()
        if (raw.isEmpty()) return Validation.Invalid(Reason.EMPTY)
        // A bare host is taken as http (Tor encrypts onion traffic itself).
        if (!raw.contains("://")) raw = "http://$raw"
        val uri = try {
            URI(raw)
        } catch (e: Exception) {
            return Validation.Invalid(Reason.MALFORMED)
        }
        val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: return Validation.Invalid(Reason.MALFORMED)
        if (!host.endsWith(".onion")) return Validation.Invalid(Reason.NOT_ONION)
        // The service label is the one directly before ".onion";
        // a subdomain in front of it is allowed by Tor.
        val label = host.removeSuffix(".onion").substringAfterLast('.')
        if (!isV3OnionLabel(label)) return Validation.Invalid(Reason.BAD_ONION)
        if (uri.scheme?.lowercase() != "http") return Validation.Invalid(Reason.NOT_HTTP)
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) {
            return Validation.Invalid(Reason.HAS_EXTRAS)
        }
        if (uri.port != -1 && uri.port !in 1..65535) return Validation.Invalid(Reason.BAD_PORT)
        // :80 is http's own port; dropping it keeps one spelling per address.
        val port = if (uri.port != -1 && uri.port != 80) ":${uri.port}" else ""
        var path = uri.rawPath.orEmpty()
        // A file is not an instance root.
        if (path.lowercase().endsWith(".json")) return Validation.Invalid(Reason.MALFORMED)
        if (!path.endsWith("/")) path += "/"
        return Validation.Valid("http://$host$port$path")
    }

    /**
     * A v3 onion service label (Tor rend-spec-v3, "Encoding onion
     * addresses"): base32 of pubkey (32 bytes) || checksum (2) || version
     * (0x03), where checksum = SHA3-256(".onion checksum" || pubkey ||
     * version)[0..2]. Catches a typo before it is saved.
     */
    internal fun isV3OnionLabel(label: String): Boolean {
        if (!V3_ONION_LABEL.matches(label)) return false
        val bytes = base32Decode(label) ?: return false
        if (bytes.size != 35 || bytes[34] != 3.toByte()) return false
        val digest = SHA3Digest(256)
        val prefix = ".onion checksum".toByteArray(Charsets.US_ASCII)
        digest.update(prefix, 0, prefix.size)
        digest.update(bytes, 0, 32)
        digest.update(bytes[34])
        val out = ByteArray(32)
        digest.doFinal(out, 0)
        return out[0] == bytes[32] && out[1] == bytes[33]
    }

    private fun base32Decode(s: String): ByteArray? {
        val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in s) {
            val v = alphabet.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xff)
            }
        }
        return out.toByteArray()
    }

    /** The stored custom base, or the default when none is stored or it no longer validates. */
    fun effectiveBaseUrl(stored: String?): String {
        if (stored.isNullOrBlank()) return DojoBayConstants.BASE_URL
        return when (val v = validate(stored)) {
            is Validation.Valid -> v.baseUrl
            is Validation.Invalid -> DojoBayConstants.BASE_URL
        }
    }

    fun isDefault(baseUrl: String): Boolean =
        baseUrl.equals(DojoBayConstants.BASE_URL, ignoreCase = true)

    /** The file the app actually requests for [baseUrl]. */
    fun directoryUrl(baseUrl: String): String =
        baseUrl.trimEnd('/') + "/" + DojoBayConstants.DIRECTORY_PATH

    /** Short form for the screen and for log lines: `dojoba…u2yd.onion`. Never the full onion. */
    fun label(baseUrl: String): String {
        ExplorerBinding.shortenOnion(baseUrl)?.let { return it }
        return try {
            URI(baseUrl).host ?: ExplorerBinding.truncateExplorerLabel(baseUrl)
        } catch (e: Exception) {
            ExplorerBinding.truncateExplorerLabel(baseUrl)
        }
    }
}
