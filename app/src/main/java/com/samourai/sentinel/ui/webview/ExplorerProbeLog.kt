package com.samourai.sentinel.ui.webview

import android.util.Log
import timber.log.Timber
import java.net.URI

/**
 * Logcat for the block-explorer health check. Tag [TAG] so a device
 * capture is `adb logcat -s ExplorerProbe`.
 *
 * Debug builds keep every line. Those lines name a shortened onion
 * (the same formatter as the row) or a clearnet hostname. They never
 * include a full onion, a query string, an API key, a txid, an xpub,
 * or a non-loopback IP. The local Tor SOCKS port stays, so a session
 * change is still visible on a debug build.
 *
 * Release builds keep only [result] (#523): probe outcome, an HTTP
 * status or exception class, and elapsed milliseconds. No host,
 * onion, URL, path, or address. Other tags are unchanged.
 */
internal object ExplorerProbeLog {
    const val TAG = "ExplorerProbe"

    fun start(baseUrl: String, socks: String) {
        log("start host=${host(baseUrl)} socks=${socksLabel(socks)}")
    }

    fun url(url: String, result: String, elapsedMs: Long) {
        log(urlLine(url, result, elapsedMs))
    }

    fun done(baseUrl: String, outcome: String, elapsedMs: Long) {
        log("done host=${host(baseUrl)} outcome=${token(outcome)} elapsedMs=${elapsedMs.coerceAtLeast(0L)}")
    }

    /**
     * The one line a release build keeps. [detail] is an HTTP status
     * or an exception class name. [baseUrl] is the probe target and
     * is not written: it is an onion or a clearnet address.
     *
     * REACHABLE is the green row ("Bound to …" for the paired Dojo,
     * "Using …" otherwise). UNREACHABLE is the red row. UNCONFIRMED
     * is the amber timeout. The host that those row strings
     * interpolate is not part of this line.
     */
    fun result(baseUrl: String, outcome: String, detail: String, elapsedMs: Long) {
        log(resultLine(baseUrl, outcome, detail, elapsedMs))
    }

    fun cacheHit(baseUrl: String, outcome: String, ageMs: Long) {
        log("cache hit host=${host(baseUrl)} outcome=${token(outcome)} ageMs=${ageMs.coerceAtLeast(0L)}")
    }

    fun cacheMiss(baseUrl: String) {
        log("cache miss host=${host(baseUrl)}")
    }

    fun abort(baseUrl: String, reason: String, socks: String) {
        log("abort host=${host(baseUrl)} reason=${token(reason)} socks=${socksLabel(socks)}")
    }

    internal fun urlLine(url: String, result: String, elapsedMs: Long): String =
        "url host=${host(url)} path=${pathOf(url)} result=${token(result)} elapsedMs=${elapsedMs.coerceAtLeast(0L)}"

    internal fun resultLine(baseUrl: String, outcome: String, detail: String, elapsedMs: Long): String {
        val elapsed = elapsedMs.coerceAtLeast(0L)
        val line = "result outcome=${outcomeToken(outcome)} status=${statusToken(detail)} elapsedMs=$elapsed"
        if (!RELEASE_RESULT.matches(line) || mentionsTarget(line, baseUrl)) {
            return "result outcome=error status=error elapsedMs=$elapsed"
        }
        return line
    }

    internal fun host(url: String): String {
        ExplorerBinding.shortenOnion(url)?.let { return it }
        val host = runCatching { URI(url.trim()).host }.getOrNull()?.trim().orEmpty()
        if (host.isEmpty()) return "unknown"
        ExplorerBinding.shortenOnion(host)?.let { return it }
        if (isIpAddress(host)) return "ip"
        if (host.length > 80) return "unknown"
        return host
    }

    internal fun pathOf(url: String): String {
        val path = runCatching { URI(url.trim()).rawPath }.getOrNull().orEmpty()
        if (path.isBlank()) return "/"
        if (sensitivePath(path)) return "/"
        return path
    }

    internal fun socksLabel(socks: String): String {
        val trimmed = socks.trim()
        if (trimmed.isEmpty() || trimmed.equals("none", ignoreCase = true)) return "none"
        ExplorerBinding.shortenOnion(trimmed)?.let { return it }
        if (trimmed.length > 64 || trimmed.any { it.isWhitespace() } || trimmed.contains("://") || trimmed.contains("?")) {
            return "unknown"
        }
        val host = socksHost(trimmed)
        if (host != null && isIpAddress(host) && !isLoopback(host)) return "unknown"
        return trimmed
    }

    private fun token(value: String): String {
        val trimmed = value.trim()
        val ok = trimmed.length in 1..40 && trimmed.all { ch ->
            ch.isLetterOrDigit() || ch == '_' || ch == '-'
        }
        return if (ok) trimmed else "error"
    }

    private fun log(line: String) {
        Timber.tag(TAG).i(line)
    }
}

/**
 * Whole-string shape of the only line [releaseKeepsExplorerProbe]
 * accepts. Outcome, status, and elapsed time. No host and no room
 * for a second field.
 */
internal val RELEASE_RESULT = Regex(
    "result outcome=(REACHABLE|UNREACHABLE|UNCONFIRMED|error) " +
        "status=(none|empty|error|[1-5][0-9]{2}|[A-Z][A-Za-z0-9]{0,60}(?:Exception|Error|Throwable)) " +
        "elapsedMs=[0-9]+",
)

internal fun outcomeToken(outcome: String): String {
    return when (outcome.trim()) {
        "REACHABLE", "UNREACHABLE", "UNCONFIRMED" -> outcome.trim()
        else -> "error"
    }
}

/**
 * HTTP status, the empty-URL sentinel, or an exception class name.
 * Anything else (a URL, an onion, a txid, an xpub, an address, a
 * key) becomes `error`.
 */
internal fun statusToken(detail: String): String {
    val trimmed = detail.trim()
    if (trimmed.isEmpty()) return "none"
    if (trimmed == "empty") return "empty"
    if (trimmed.length <= 3 && HTTP_STATUS.matches(trimmed)) return trimmed
    if (trimmed.length in 1..80 && EXCEPTION_CLASS.matches(trimmed)) return trimmed
    return "error"
}

private val HTTP_STATUS = Regex("[1-5][0-9]{2}")
private val EXCEPTION_CLASS = Regex("[A-Z][A-Za-z0-9]{0,60}(?:Exception|Error|Throwable)")
private val IPV4 = Regex("(?:\\d{1,3}\\.){3}\\d{1,3}")
private val HEX_RUN = Regex("[0-9a-fA-F]{32,}")
private val SENSITIVE_PATH = Regex(
    "(?i)(\\.onion|xpub|ypub|zpub|tpub|upub|vpub|apikey|api_key|access_token|\\bat=)",
)

private fun isIpAddress(host: String): Boolean {
    val value = host.trim().removePrefix("[").removeSuffix("]")
    if (IPV4.matches(value)) return true
    return value.count { it == ':' } >= 2
}

private fun isLoopback(host: String): Boolean {
    val value = host.trim().lowercase().removePrefix("[").removeSuffix("]")
    return value == "localhost" || value == "::1" || value == "0:0:0:0:0:0:0:1" || value.startsWith("127.")
}

private fun socksHost(endpoint: String): String? {
    if (endpoint.startsWith("[")) {
        val end = endpoint.indexOf(']')
        if (end <= 1) return null
        return endpoint.substring(1, end)
    }
    val colon = endpoint.lastIndexOf(':')
    if (colon <= 0) return endpoint
    return endpoint.substring(0, colon)
}

private fun sensitivePath(path: String): Boolean {
    if (SENSITIVE_PATH.containsMatchIn(path)) return true
    if (HEX_RUN.containsMatchIn(path)) return true
    if (IPV4.containsMatchIn(path)) return true
    return false
}

/**
 * True when [line] contains the probe URL or its host. The formatted
 * result never should; this drops the line if a later edit puts the
 * target back in.
 */
private fun mentionsTarget(line: String, baseUrl: String): Boolean {
    val needle = baseUrl.trim()
    if (needle.length >= 12 && line.contains(needle)) return true
    val host = runCatching { URI(needle).host }.getOrNull()?.trim().orEmpty()
    return host.length >= 8 && host.contains('.') && line.contains(host)
}

/**
 * #523: release drops every Timber line that has no throwable.
 * This is the one exception, and only for a [RELEASE_RESULT] line.
 * Debug builds still plant a normal tree, so they keep start, url,
 * cache, and abort as well. A throwable is never kept here: that
 * path writes a stack, which can carry an onion.
 */
internal fun releaseKeepsExplorerProbe(
    priority: Int,
    tag: String?,
    message: String,
    throwable: Throwable?,
): Boolean {
    if (throwable != null) return false
    if (priority < Log.INFO) return false
    if (tag != ExplorerProbeLog.TAG) return false
    return RELEASE_RESULT.matches(message)
}
