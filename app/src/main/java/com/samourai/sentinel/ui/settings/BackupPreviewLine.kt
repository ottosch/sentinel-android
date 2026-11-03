package com.samourai.sentinel.ui.settings

import com.samourai.sentinel.core.DerivedNetwork
import com.samourai.sentinel.ui.dojo.normalizeDeclaredNetwork
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * The import screen's preview card, two halves:
 *
 * 1. backupPreviewLine - #404 (decision 7)'s calm summary line.
 *    Built from envelope-level fields ONLY - validatePayload parses
 *    but does not decrypt, so "network" and "time" are the only two
 *    facts worth summarizing, and that is by design.
 *
 *    Shape: network first, date last, joined with " \u00b7 " (middle
 *    dot). No "created" prefix, no source/type segment.
 *
 *    Absent means absent, never a placeholder:
 *     - "network": the optional #404 declaration, written by export
 *       only on a unanimous keychain (mixed/empty write nothing). Old
 *       and foreign (Samourai/Ashigaru) backups carry no field, so no
 *       segment. normalizeDeclaredNetwork's fail-closed rule applies:
 *       anything but "mainnet"/"testnet" (case-insensitive) is no
 *       declaration, never a guess.
 *     - "time": epoch millis; absent, zero or negative yields no
 *       segment (epoch zero is not a creation date anyone made a
 *       backup on).
 *
 *    Zero segments - the Samourai/Ashigaru shape carries neither
 *    field - is the agreed fallback: "Valid backup file". The file
 *    decrypted and classified; there is simply nothing extra to say
 *    about it.
 *
 * 2. backupEnvelopeDump - the raw envelope, restored BENEATH the
 *    line. #405 removed the old json.toString(2) dump as UI noise,
 *    but it carried real distinguishing value: format, kdf era, app
 *    version, blob size - enough to tell two same-day backups apart,
 *    which a date-only line cannot (an old backup renders date-only
 *    and reads as "the data is gone"). It returns with one change:
 *    the base64 payload blob is truncated to its head plus an
 *    approximate size - the old dump printed the entire blob,
 *    kilobytes of scroll, and the size carries the same fact.
 *    Nothing decrypts: every value shown is envelope-level, the
 *    ciphertext stays ciphertext.
 *
 *    The truncation marker is ASCII "..." on purpose: Android's
 *    org.json escapes every char in U+2000-U+20FF inside serialized
 *    string values, so a real ellipsis (U+2026) would reach the
 *    screen as a six-character backslash-u escape - noise, not a
 *    marker. ASCII survives every serializer verbatim. (The middle
 *    dot in the line above never passes through a JSON serializer,
 *    so it keeps its character.)
 *
 * Both pure on purpose: the DateFormat travels as a parameter so the
 * JVM test pins output without locale or timezone luck; the dump has
 * no date at all and no Android imports.
 */
fun backupPreviewLine(json: JSONObject, dateFormat: DateFormat): String {
    val segments = mutableListOf<String>()
    when (normalizeDeclaredNetwork(json.optString("network"))) {
        DerivedNetwork.MAINNET -> segments.add("Mainnet")
        DerivedNetwork.TESTNET -> segments.add("Testnet")
        DerivedNetwork.UNKNOWN, null -> Unit
    }
    val time = json.optLong("time", 0L)
    if (time > 0L) {
        segments.add(dateFormat.format(Date(time)))
    }
    if (segments.isEmpty()) {
        return "Valid backup file"
    }
    return segments.joinToString(" \u00b7 ")
}

/**
 * The envelope as pretty-printed JSON, payload blob truncated: first
 * 16 chars, ASCII "...", then the blob's approximate size. Base64
 * carries 3 bytes per 4 chars, so the byte estimate is chars*3/4 -
 * no decode, no dependency, deterministic for tests. A blob of 32
 * chars or fewer stays verbatim (that short cannot scroll).
 */
fun backupEnvelopeDump(json: JSONObject): String {
    val dump = JSONObject(json.toString())
    val payload = dump.optString("payload", "")
    if (payload.length > 32) {
        dump.put(
            "payload",
            payload.take(16) + "... (" + blobSizeNote(payload.length) + ")"
        )
    }
    return dump.toString(2)
}

/** "N B" under 1 KiB, "N.N KB" above - Locale.US pins the decimal point. */
private fun blobSizeNote(base64Chars: Int): String {
    val bytes = base64Chars * 3 / 4
    return if (bytes >= 1024) {
        String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    } else {
        "$bytes B"
    }
}
