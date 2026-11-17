package com.samourai.sentinel.tor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #584 review: Tor's NOTICE and WARN lines are masked before they reach
 * logcat in debug builds. The inputs are Tor 0.4.9 log lines in the shape
 * kmp-tor delivers them (message text, no timestamp or severity prefix).
 * Fingerprints and addresses are documentation values, not real relays.
 */
class TorLogMaskingTest {

    private val fp = "9695DFC35FFEB861329B9F1AB04C46397020CE31"
    private val appDir = "/data/user/0/com.samourai.sentinel.staging"

    private fun assertGone(out: String, vararg secrets: String) {
        for (s in secrets) assertFalse("'$s' must not survive in: $out", out.contains(s))
    }

    @Test
    fun `problem bootstrapping warning loses the guard fingerprint and address`() {
        val line = "Problem bootstrapping. Stuck at 14% (handshake): Handshaking with a relay. " +
            "(DONE; DONE; count 1; recommendation warn; host $fp at 192.0.2.34:9101)"
        val out = TorLogMasking.mask(line)
        assertGone(out, fp, "192.0.2.34", "9101")
        assertEquals(
            "Problem bootstrapping. Stuck at 14% (handshake): Handshaking with a relay. " +
                "(DONE; DONE; count 1; recommendation warn; host FINGERPRINT_REDACTED at IP_REDACTED)",
            out,
        )
    }

    @Test
    fun `problem bootstrapping warning with an IPv6 guard is masked`() {
        val line = "Problem bootstrapping. Stuck at 10% (conn_done): Connected to a relay. " +
            "(DONE; DONE; count 3; recommendation warn; host ${fp.lowercase()} at [2001:db8:85a3::8a2e:370:7334]:443)"
        val out = TorLogMasking.mask(line)
        assertGone(out, fp.lowercase(), "2001:db8", "7334", ":443")
        assertTrue(out.endsWith("host FINGERPRINT_REDACTED at IP_REDACTED)"))
        assertTrue(out.startsWith("Problem bootstrapping. Stuck at 10% (conn_done)"))
    }

    @Test
    fun `dollar fingerprints with nicknames and bare IPv6 are masked`() {
        val tilde = TorLogMasking.mask("Your Guard \$$fp~SomeGuardRelay (\$$fp) is failing a very large amount of circuits.")
        assertGone(tilde, fp, "SomeGuardRelay")
        assertEquals("Your Guard NICK_REDACTED is failing a very large amount of circuits.", tilde)

        val equals = TorLogMasking.mask("Circuit build failed at \$$fp=relay2 on 2001:db8::1")
        assertEquals("Circuit build failed at FINGERPRINT_REDACTED on IP_REDACTED", equals)
    }
    @Test
    fun `guard nicknames do not survive`() {
        val issueForm = TorLogMasking.mask("Your Guard SomeGuardRelay (\$$fp) is failing a very large amount of circuits.")
        assertGone(issueForm, fp, "SomeGuardRelay")

        val tildeForm = TorLogMasking.mask("Your Guard \$$fp~SomeGuardRelay is failing a very large amount of circuits.")
        assertGone(tildeForm, fp, "SomeGuardRelay")

        // Ordinary words before a non-fingerprint parenthetical stay.
        val normal = TorLogMasking.mask("Bootstrapped 45% (handshake): Handshaking with a relay.")
        assertEquals("Bootstrapped 45% (handshake): Handshaking with a relay.", normal)
    }

    @Test
    fun `lines longer than the limit are cut before masking`() {
        val long = "/data/user/0/com.samourai.sentinel.staging/app_tor/geoip" + "x".repeat(4_000)
        val out = TorLogMasking.mask("Parsing GEOIP IPv4 file $long")
        assertGone(out, appDir)
    }

    @Test
    fun `ed25519 relay identities are masked`() {
        val ed = "3e2Z8pKMzJ0Vb7q0V1z0hXqoYvTQ0YbF2y8b9mH1aWk"
        assertEquals(43, ed.length)
        val out = TorLogMasking.mask("Received a bad CERTS cell from relay with ed25519 id $ed: Ed25519 identity mismatch")
        assertGone(out, ed)
        assertTrue(out.contains("ed25519 id ED25519_REDACTED: Ed25519 identity mismatch"))
    }

    @Test
    fun `socks and control listener addresses and ports are masked`() {
        assertEquals("Opening Socks listener on IP_REDACTED", TorLogMasking.mask("Opening Socks listener on 127.0.0.1:43217"))
        assertEquals(
            "Opened Socks listener connection (ready) on IP_REDACTED.",
            TorLogMasking.mask("Opened Socks listener connection (ready) on 127.0.0.1:43217."),
        )
        assertEquals("Socks listener listening on port PORT_REDACTED.", TorLogMasking.mask("Socks listener listening on port 43217."))
        assertEquals("Control listener listening on port PORT_REDACTED.", TorLogMasking.mask("Control listener listening on port 9051."))
        assertEquals(
            "Opening Control listener on PATH_REDACTED",
            TorLogMasking.mask("Opening Control listener on $appDir/app_tor/ctrl.sock"),
        )
        assertEquals("SocksPort PORT_REDACTED", TorLogMasking.mask("SocksPort 43217"))
    }

    @Test
    fun `data directory and cache paths are masked`() {
        val geoip = TorLogMasking.mask("Parsing GEOIP IPv4 file $appDir/app_tor/geoip.")
        assertEquals("Parsing GEOIP IPv4 file PATH_REDACTED.", geoip)

        val torrc = TorLogMasking.mask("Read configuration file \"$appDir/app_tor/torrc-defaults\".")
        assertEquals("Read configuration file \"PATH_REDACTED\".", torrc)

        val cache = TorLogMasking.mask(
            "Unable to open file \"$appDir/app_tor_cache/cached-microdesc-consensus.tmp\" in mode 'w': Permission denied"
        )
        assertGone(cache, appDir, "app_tor_cache", "com.samourai")
        assertTrue(cache.endsWith("in mode 'w': Permission denied"))

        val storage = TorLogMasking.mask("DataDirectory /storage/emulated/0/Android/data/com.samourai.sentinel/tor is not writable")
        assertEquals("DataDirectory PATH_REDACTED is not writable", storage)
    }

    @Test
    fun `stall diagnostics stay readable`() {
        val kept = listOf(
            "Bootstrapped 70% (loading_descriptors): Loading relay descriptors",
            "Bootstrapped 100% (done): Done",
            "Expiring wedged directory conn (fd 87, purpose 14)",
            "I learned some more directory information, but not enough to build a circuit: We need more microdescriptors: we have 0/7426, and can only build 0% of likely paths.",
            "Tor 0.4.9.16 running on Linux with Libevent 2.1.12-stable, OpenSSL 3.0.15, Zlib 1.3.1, Liblzma N/A, Libzstd N/A and Unknown N/A as libc.",
            "Tried for 120 seconds to get a connection to [scrubbed]:443. Giving up. (waiting for circuit)",
            "Our clock is 2 hours, 0 minutes behind the time published in the consensus at 12:34:56.",
        )
        for (line in kept) assertEquals(line, TorLogMasking.mask(line))
    }

    @Test
    fun `app-wide redaction still applies to Tor lines`() {
        val host = "abcdefghijklmnopqrstuvwxyz234567"
        val txid = "a".repeat(64)
        val out = TorLogMasking.mask("Closing stream to '$host.onion' for $txid: hidden service is unavailable")
        assertGone(out, "$host.onion", txid)
        assertTrue(out.contains("ONION_REDACTED"))
        assertTrue(out.contains("HEX_REDACTED"))
        assertTrue(out.endsWith("hidden service is unavailable"))
    }

    @Test
    fun `both Tor log observers forward the masked line`() {
        val path = "java/com/samourai/sentinel/tor/SentinelTorManager.kt"
        val src = listOf(File("src/main/$path"), File("app/src/main/$path")).first { it.isFile }.readText()
        assertTrue(src.contains("Timber.tag(TOR_LOG_TAG).i(TorLogMasking.mask(line))"))
        assertTrue(src.contains("Timber.tag(TOR_LOG_TAG).w(TorLogMasking.mask(line))"))
        assertFalse("no unmasked Tor line", Regex("""Timber\.tag\(TOR_LOG_TAG\)\.\w\(line\)""").containsMatchIn(src))
    }

    // #588: the IPv6 patterns are linear, so a line within the cap is masked, not dropped.

    /** Runs [block] on a thread with a 256 KiB stack; returns elapsed ms, rethrows any failure. */
    private fun onSmallStack(block: () -> Unit): Long {
        var failure: Throwable? = null
        val started = System.nanoTime()
        val worker = Thread(null, {
            try {
                block()
            } catch (t: Throwable) {
                failure = t
            }
        }, "tor-mask-small-stack", 256L * 1024)
        worker.start()
        worker.join(30_000)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertFalse("still running after $elapsedMs ms", worker.isAlive)
        failure?.let { throw AssertionError("masking threw on a long line", it) }
        return elapsedMs
    }

    private val longInputs = listOf(
        "1:".repeat(50_000),
        "[" + "1:".repeat(50_000),
        "[1:".repeat(33_000),
        "::" + "ab:".repeat(33_000),
        "a".repeat(100_000),
        "host " + "0123456789abcdef:".repeat(6_000) + " at [" + ":".repeat(100_000),
    )

    @Test
    fun `lines up to the cap of colons and hex are masked on a 256 KiB stack`() {
        // The old bare-IPv6 pattern overflowed here at about 655 characters,
        // well under the 2,048 cap, so kmp-tor dropped the line.
        val elapsedMs = onSmallStack {
            for (input in longInputs) TorLogMasking.mask(input)
            val atCap = "on " + "2001:db8::1 ".repeat(170)
            val out = TorLogMasking.mask(atCap)
            assertGone(out, "2001:db8")
            assertTrue(out.startsWith("on IP_REDACTED IP_REDACTED"))
        }
        assertTrue("took $elapsedMs ms", elapsedMs < 5_000)
    }

    @Test
    fun `the IPv6 patterns stay linear on 100k-character input`() {
        val elapsedMs = onSmallStack {
            for (input in longInputs) {
                TorLogMasking.IPV6_BRACKETED.replace(input, "IP_REDACTED")
                TorLogMasking.IPV6_BARE.replace(input) { if (TorLogMasking.isIpv6(it.value)) "IP_REDACTED" else it.value }
            }
        }
        assertTrue("took $elapsedMs ms", elapsedMs < 5_000)
    }

    @Test
    fun `linear IPv6 patterns still mask real addresses and keep clock times`() {
        assertEquals("on IP_REDACTED", TorLogMasking.mask("on 2001:db8:85a3:0:0:8a2e:370:7334"))
        assertEquals("on IP_REDACTED.", TorLogMasking.mask("on fe80::1."))
        assertEquals("on IP_REDACTED", TorLogMasking.mask("on ::1"))
        assertEquals("at IP_REDACTED)", TorLogMasking.mask("at [2001:db8::1]:9001)"))
        assertEquals("at IP_REDACTED", TorLogMasking.mask("at [::1]"))
        for (kept in listOf("at 12:34:56.", "cafe: open", "Foo::bar", "a:b:c:d", "[scrubbed]:443")) {
            assertEquals(kept, TorLogMasking.mask(kept))
        }
    }

    // #593 review L1: a token that straddles the 2,048-character cut is dropped
    // whole, with the word before it, so no fragment escapes the masks.

    private val limit = 2_048
    private val truncated = " [truncated]"

    /** A Tor-like line where [secret] starts so that [before] of its characters fall inside the cut. */
    private fun straddling(secret: String, before: Int, tail: String = " is still here"): String {
        val target = limit - before
        val sb = StringBuilder("Bootstrapped 70% (loading_descriptors): Loading relay descriptors ")
        while (sb.length + 3 <= target) sb.append("ok ")
        when (target - sb.length) {
            1 -> sb.append(" ")
            2 -> sb.append("k ")
        }
        check(sb.length == target)
        return sb.append(secret).append(tail).toString()
    }

    private fun assertCutCleanly(secret: String, vararg befores: Int) {
        for (before in befores) {
            val line = straddling(secret, before)
            assertTrue(line.length > limit)
            val out = TorLogMasking.mask(line)
            val inside = secret.take(before)
            if (inside.length >= 3) assertGone(out, inside)
            val after = secret.drop(before).take(6)
            if (after.length >= 3) assertGone(out, after)
            assertTrue("marker in: ${out.takeLast(40)}", out.endsWith(truncated))
            assertTrue(out.startsWith("Bootstrapped 70% (loading_descriptors)"))
            assertTrue(out.length <= limit + truncated.length)
        }
    }

    @Test
    fun `a fingerprint straddling the cut leaves no hex fragment`() {
        assertCutCleanly(fp, 3, 10, 30, 39)
        assertCutCleanly("\$$fp", 5, 31, 40)
    }

    @Test
    fun `IPv4 and IPv6 addresses straddling the cut leave no fragment`() {
        assertCutCleanly("192.0.2.34:9101", 3, 7, 11, 14)
        assertCutCleanly("2001:db8:85a3::8a2e:370:7334", 4, 9, 15, 27)
        assertCutCleanly("[2001:db8::1]:9001", 5, 12, 17)
    }

    @Test
    fun `onion, xpub and ed25519 values straddling the cut leave no fragment`() {
        val onion = "pg6mmjiyjmcrsslvykfwnntlaru7p5svn6y2ymmju6nubxndf4pscryd.onion"
        assertCutCleanly(onion, 16, 40, 56, 60)
        val xpub = "xpub6CUGRUonZSQ4TWtTMmzXdrXDtypWKiKrhko4egpiMZbpiaQL2jkwSB1icqYh2cfDfVxdx4df189oLKnC5fSwqPfgyP3hooxujYzAu3fDVmz"
        assertCutCleanly(xpub, 20, 80, 110)
        assertCutCleanly("3e2Z8pKMzJ0Vb7q0V1z0hXqoYvTQ0YbF2y8b9mH1aWk", 10, 21, 42)
    }

    @Test
    fun `a guard nickname whose fingerprint falls past the cut is dropped`() {
        val warning = "SomeGuardRelay (\$$fp) is failing a very large amount of circuits."
        for (before in listOf(5, "SomeGuardRelay".length, "SomeGuardRelay ".length, "SomeGuardRelay (\$".length + 10)) {
            val out = TorLogMasking.mask(straddling(warning, before))
            assertGone(out, "SomeGuard", fp.take(6))
            assertTrue(out.endsWith(truncated))
        }
    }

    @Test
    fun `a line with no whitespace past the limit keeps nothing`() {
        assertEquals(truncated, TorLogMasking.mask("x".repeat(3_000) + fp))
        assertEquals(truncated, TorLogMasking.mask("1:".repeat(50_000)))
    }

    @Test
    fun `a line exactly at the limit is not cut`() {
        val plain = straddling("end", 3, tail = "")
        assertEquals(limit, plain.length)
        assertEquals(plain, TorLogMasking.mask(plain))

        val withFp = straddling("host $fp", "host $fp".length, tail = "")
        assertEquals(limit, withFp.length)
        val out = TorLogMasking.mask(withFp)
        assertTrue(out.endsWith("host FINGERPRINT_REDACTED"))
        assertFalse(out.contains(truncated))
    }

    // #593 review L2: the nickname rule must not eat the end of an IPv6 address.

    @Test
    fun `an IPv6 address just before a parenthesised fingerprint is masked whole`() {
        assertEquals(
            "Guard at IP_REDACTED (FINGERPRINT_REDACTED) is down",
            TorLogMasking.mask("Guard at 2a0b:f4c2:2::1 (\$$fp) is down"),
        )
        assertEquals(
            "Guard at IP_REDACTED (FINGERPRINT_REDACTED) is down",
            TorLogMasking.mask("Guard at 2a0b:f4c2:2:: (\$$fp) is down"),
        )
        // The nickname form is unchanged.
        assertEquals(
            "Your Guard NICK_REDACTED is failing more circuits than usual.",
            TorLogMasking.mask("Your Guard SomeGuardRelay (\$$fp) is failing more circuits than usual."),
        )
    }
}
