package com.samourai.sentinel.ui.settings

import com.samourai.sentinel.core.DerivedNetwork
import com.samourai.sentinel.ui.dojo.normalizeDeclaredNetwork
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/**
 * #404 (decision 7): the import screen's calm preview line, replacing
 * the raw JSON dump. Built from envelope-level fields ONLY -
 * validatePayload parses but does not decrypt, so "network" and "time"
 * are the only two facts on offer, and that is by design.
 *
 * Shape: network first, date last, joined with " \u00b7 " (middle
 * dot). No "created" prefix, no source/type segment.
 *
 * Absent means absent, never a placeholder:
 *  - "network": the optional #404 declaration, written by export only
 *    on a unanimous keychain (mixed/empty write nothing). Old and
 *    foreign (Samourai/Ashigaru) backups carry no field, so no
 *    segment. normalizeDeclaredNetwork's fail-closed rule applies:
 *    anything but "mainnet"/"testnet" (case-insensitive) is no
 *    declaration, never a guess.
 *  - "time": epoch millis; absent, zero or negative yields no segment
 *    (epoch zero is not a creation date anyone made a backup on).
 *
 * Zero segments - the Samourai/Ashigaru shape carries neither field -
 * is the agreed fallback: "Valid backup file". The file decrypted and
 * classified; there is simply nothing extra to say about it.
 *
 * Pure on purpose: the DateFormat travels as a parameter so the JVM
 * test pins output without locale or timezone luck.
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
