package com.samourai.sentinel.tor

import com.samourai.sentinel.util.LogRedaction

/**
 * #584 review: masking for Tor's own NOTICE and WARN lines before they
 * reach logcat in debug builds. Debug APKs are public CI artifacts, and
 * Tor's SafeLogging does not scrub everything: the "Problem bootstrapping"
 * warning names the guard relay by fingerprint and IP:port, and notices
 * show the app's data paths and the SOCKS port.
 *
 * Only the identifying values are replaced. The rest of each line, such
 * as "Bootstrapped 70%" or "Expiring wedged directory conn", is kept so a
 * stall can still be read from a capture. The app-wide rules in
 * [LogRedaction] (onion hosts, xpubs, txids, addresses) run afterwards,
 * so this does not depend on which Timber trees are planted.
 *
 * Only SentinelTorManager's BuildConfig.DEBUG block calls this.
 */
internal object TorLogMasking {

    /**
     * Absolute filesystem paths (/data/..., /storage/..., any /a/b). The
     * slash must not follow a word character, so "N/A" and "1/2" stay.
     * A trailing full stop is left outside the match.
     */
    private val PATH = Regex("""(?<![\w./])/[\w.\-+@~%/]*[\w\-+@~%/]""")

    /**
     * Relay identity fingerprints: 40 hex characters, with or without a
     * leading $, upper or lower case, with an optional ~nickname or
     * =nickname. Exactly 40: a 64-character txid is not touched here.
     */
    private val FINGERPRINT = Regex("""(?<![0-9A-Za-z$])\$?[0-9A-Fa-f]{40}(?:[~=][A-Za-z0-9]{1,19})?(?![0-9A-Za-z])""")

    /** Base64 ed25519 relay identities (43 characters, unpadded). */
    private val ED25519_ID = Regex("""(?<![A-Za-z0-9+/])[A-Za-z0-9+/]{43}(?![A-Za-z0-9+/=])""")

    /**
     * Bracketed IPv6, optionally with a port: [2001:db8::1]:443. The
     * part before the first colon cannot contain a colon and both runs
     * are possessive, so a failed match never backtracks: linear, not
     * quadratic, on a long line (#588).
     */
    internal val IPV6_BRACKETED = Regex("""\[[0-9A-Fa-f.]*+:[0-9A-Fa-f:.]*+](?::\d{1,5}+)?""")

    /**
     * IPv4, optionally with a port. Not after "Tor " or "version ", so
     * the four-part Tor version number stays readable.
     */
    private val IPV4 = Regex("""(?<![\w.])(?<!Tor )(?<!version )(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?(?!\w)(?!\.\d)""")

    /**
     * Bare IPv6 candidates: one possessive run of hex digits and colons,
     * with at least one colon. [isIpv6] then keeps the full eight groups
     * or a compressed form with "::" and at least one group, so clock
     * times (12:34:56) do not match. The old pattern repeated a group
     * and overflowed a 256 KiB stack at about 655 characters, under
     * [LINE_LIMIT]; this one has no nested repetition, so its cost is
     * linear and its stack depth constant (#588).
     */
    internal val IPV6_BARE = Regex("""(?<![\w:])[0-9A-Fa-f]*+:[0-9A-Fa-f:]*+(?!\w)""")

    internal fun isIpv6(candidate: String): Boolean {
        val groups = candidate.split(':')
        if (groups.size > 9 || groups.any { it.length > 4 }) return false
        val compressed = candidate.indexOf("::")
        return if (compressed >= 0) {
            candidate.indexOf("::", compressed + 1) < 0 && groups.count { it.isNotEmpty() } in 1..7
        } else {
            groups.size == 8 && groups.all { it.isNotEmpty() }
        }
    }

    /** "Socks listener listening on port 9050", "Control listener ... port 9051". */
    private val LISTENER_PORT = Regex(
        """(?i)(\b(?:socks|control|dns|trans|natd|http tunnel|metrics|extor)\w*\s+listener\b[^\n]*?\bport\s+)\d{1,5}\b"""
    )

    /** Port options as Tor echoes them: SocksPort 9050, ControlPort=9051. */
    private val PORT_OPTION = Regex(
        """(?i)(\b(?:Socks|Control|DNS|Trans|NATD|HTTPTunnel|Metrics)Port\s*[= ]\s*)\d{1,5}\b"""
    )

    /**
     * #588: Tor names the guard by nickname as well as fingerprint:
     * "Your Guard <nick> ($FP)". [FINGERPRINT] blanks the hex (with its
     * own ~nick/=nick suffix), but the bare nickname before " ($FP)"
     * survived. Runs after [FINGERPRINT] on the masked text and blanks
     * the word hugging the masked parenthetical, so
     * "host FINGERPRINT_REDACTED at IP" (no paren) keeps its "host".
     */
    private val GUARD_NICKNAME = Regex("""\w+ \(FINGERPRINT_REDACTED\)""")

    /**
     * #588: bare-IPv6 and bracketed-IPv6 matching is quadratic or can
     * overflow the regex stack on long lines, and no log line this
     * diagnoses runs near this long. Cut to 2k chars before masking;
     * kmp-tor would drop an over-long line on a regex failure anyway
     * (fails closed), so a cut tail only loses diagnostics.
     */
    private const val LINE_LIMIT = 2_048

    fun mask(line: String): String {
        val cut = line.take(LINE_LIMIT)
        return LogRedaction.redact(
            cut
                .replace(PATH, "PATH_REDACTED")
                .replace(FINGERPRINT, "FINGERPRINT_REDACTED")
                .replace(GUARD_NICKNAME, "NICK_REDACTED")
                .replace(ED25519_ID, "ED25519_REDACTED")
                .replace(IPV6_BRACKETED, "IP_REDACTED")
                .replace(IPV4, "IP_REDACTED")
                .replace(IPV6_BARE) { if (isIpv6(it.value)) "IP_REDACTED" else it.value }
                .replace(LISTENER_PORT) { "${it.groupValues[1]}PORT_REDACTED" }
                .replace(PORT_OPTION) { "${it.groupValues[1]}PORT_REDACTED" }
        )
    }
}
