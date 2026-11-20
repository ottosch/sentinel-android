package com.samourai.sentinel.core

import com.samourai.sentinel.ui.utils.PrefsUtil
import okhttp3.Headers

/**
 * The version the paired Dojo reports about itself.
 *
 * Dojo stamps its running version on every API response in the
 * `X-Dojo-Version` header (lib/http-server/http-server: a global
 * middleware sets it from DOJO_VERSION_TAG, before auth runs, so the
 * ordinary API key is enough; it has been there since at least v1.16).
 * The `version` inside a pairing code is frozen when the code is made,
 * and a directory's version is unsigned, so neither is shown once the
 * Dojo has answered.
 *
 * One value is stored ([PrefsUtil.dojoReportedVersion]) and it belongs to
 * the current pairing: every pairing change clears it ([clear], from
 * DojoUtility), so it needs no URL or timestamp of its own.
 *
 * No extra request is made. The pairing login sets it for the new pairing
 * ([setFromPairingLogin]); after that [observe] reads the header from the
 * Dojo calls Sentinel already makes (wallet, fees, token refresh and so
 * on). A different version replaces the stored one, the same version
 * writes nothing, and a failed call, a missing header or a value that
 * isn't version-shaped keeps it.
 *
 * Display only. The value is read for the paired Dojo screen and nothing
 * else: it never touches the pairing payload, the Dojo URL or its Tor or
 * clearnet endpoint, the API key or the auth tokens.
 *
 * A response that left before the last pairing change is not saved: the
 * caller captures [pairingGeneration] when it sends, and [observe] drops
 * the answer if the pairing has changed since. The check and the write
 * share one lock with [clear] and [setFromPairingLogin].
 */
object DojoReportedVersion {

    const val HEADER = "X-Dojo-Version"

    /** The [PrefsUtil] property (and SharedPreferences key) holding the value. */
    const val KEY = "dojoReportedVersion"

    /**
     * The whole header value must be 1 to 32 of A-Z, a-z, 0-9, '.', '+'
     * and '-' (the rule the directory version used). Nothing is trimmed,
     * stripped or rewritten first, and the class is ASCII only.
     */
    private val SHAPE = Regex("^[0-9A-Za-z.+-]{1,32}$")

    private val lock = Any()

    @Volatile
    private var generation = 0L

    /** [raw] if it is version-shaped ([SHAPE]) exactly as sent, else null. */
    fun parse(raw: String?): String? = raw?.takeIf { SHAPE.matches(it) }

    /**
     * The header off a Dojo answer: its one X-Dojo-Version value, or null
     * when there is none or more than one (a Dojo sends exactly one).
     */
    fun headerOf(headers: Headers): String? = headers.values(HEADER).singleOrNull()

    /**
     * The value to write after a Dojo answer, or null to write nothing:
     * only a successful answer with a good header that differs from [stored].
     */
    fun next(stored: String?, successful: Boolean, header: String?): String? {
        if (!successful) return null
        val version = parse(header) ?: return null
        return version.takeIf { it != stored }
    }

    /** Changes with every pairing change. Capture it when a Dojo request is sent. */
    fun pairingGeneration(): Long = generation

    /**
     * Reads the header off a Dojo answer and saves it if [next] says so, but
     * only if the pairing is still the one [sentGeneration] was taken from.
     * True when it wrote.
     */
    fun observe(prefs: PrefsUtil, sentGeneration: Long, successful: Boolean, header: String?): Boolean =
        synchronized(lock) {
            if (sentGeneration != generation) return false
            val updated = next(prefs.dojoReportedVersion, successful, header) ?: return false
            prefs.dojoReportedVersion = updated
            true
        }

    /**
     * The pairing login's answer, for the pairing just committed: always
     * applied (it is the new Dojo speaking), and anything still in flight
     * from before is dropped. A missing or odd header leaves the value
     * empty, so the pairing code's version shows until the next answer.
     */
    fun setFromPairingLogin(prefs: PrefsUtil, header: String?) {
        synchronized(lock) {
            generation++
            prefs.dojoReportedVersion = parse(header)
        }
    }

    /** A pairing change: the value goes with the old pairing. */
    fun clear(prefs: PrefsUtil) {
        synchronized(lock) {
            generation++
            prefs.dojoReportedVersion = null
        }
    }

    /**
     * The version to show for the paired Dojo: the one it reported. Until
     * the first answer, the pairing code's own version, as before. Never a
     * directory's. Null when there is neither.
     */
    fun display(stored: String?, pairingCodeVersion: String?): String? =
        stored?.takeIf { it.isNotBlank() } ?: pairingCodeVersion?.takeIf { it.isNotBlank() }
}
