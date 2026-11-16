package com.samourai.sentinel.ui.webview

import android.util.Log
import com.samourai.sentinel.util.LogRedaction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.samourai.wallet.SamouraiWalletConst
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Proxy
import java.net.SocketException
import java.net.SocketTimeoutException

class ExplorerHealthTest {

    private val paired = ExplorerBinding.ExplorerResolution.Bound(
        kind = ExplorerBinding.ExplorerKind.PAIRED,
        baseUrl = "http://synthetic-self-explorer.onion",
        displayUrl = "http://synthetic-self-explorer.onion",
    )
    private val custom = paired.copy(kind = ExplorerBinding.ExplorerKind.CUSTOM)
    private val mempool = paired.copy(
        kind = ExplorerBinding.ExplorerKind.MEMPOOL,
        baseUrl = "https://example.invalid/testnet",
        displayUrl = "https://example.invalid/testnet",
    )

    @Test
    fun `probe route is tor or nothing`() {
        assertEquals(ExplorerProbeRoute.TOR, explorerProbeRoute(torReady = true, proxyAvailable = true))
        assertEquals(ExplorerProbeRoute.BLOCK, explorerProbeRoute(torReady = true, proxyAvailable = false))
        assertEquals(ExplorerProbeRoute.BLOCK, explorerProbeRoute(torReady = false, proxyAvailable = true))
        assertEquals(ExplorerProbeRoute.BLOCK, explorerProbeRoute(torReady = false, proxyAvailable = false))
    }

    @Test
    fun `unbound wins over tor and a reachable paired explorer is the only green state`() {
        assertEquals(
            ExplorerHealth.NONE,
            explorerHealth(
                ExplorerBinding.ExplorerResolution.Unbound,
                torReady = false,
                torInProgress = false,
                proxyAvailable = false,
                probe = ExplorerProbeOutcome.PENDING,
            ).health,
        )
        assertEquals(ExplorerHealth.TOR_STARTING, state(paired, torReady = false, torInProgress = true))
        assertEquals(ExplorerHealth.TOR_REQUIRED, state(paired, torReady = false, torInProgress = false))
        assertEquals(
            ExplorerHealth.CHECKING,
            state(paired, torReady = true, proxy = true, probe = ExplorerProbeOutcome.PENDING),
        )
        assertEquals(
            ExplorerHealth.UNREACHABLE,
            state(paired, torReady = true, proxy = true, probe = ExplorerProbeOutcome.UNREACHABLE),
        )
        assertEquals(
            ExplorerHealth.UNCONFIRMED,
            state(paired, torReady = true, proxy = true, probe = ExplorerProbeOutcome.UNCONFIRMED),
        )
        assertEquals(
            ExplorerHealth.BOUND,
            state(paired, torReady = true, proxy = true, probe = ExplorerProbeOutcome.REACHABLE),
        )
        assertEquals(
            ExplorerHealth.THIRD_PARTY,
            state(custom, torReady = true, proxy = true, probe = ExplorerProbeOutcome.REACHABLE),
        )
        assertEquals(
            ExplorerHealth.THIRD_PARTY,
            state(mempool, torReady = true, proxy = true, probe = ExplorerProbeOutcome.REACHABLE),
        )
    }

    @Test
    fun `the operator line is only shown on a green paired row`() {
        val operator = "SG Directory Dojo"
        assertTrue(explorerOperatorVisible(ExplorerHealth.BOUND, operator))
        assertFalse(explorerOperatorVisible(ExplorerHealth.CHECKING, operator))
        assertFalse(explorerOperatorVisible(ExplorerHealth.UNREACHABLE, operator))
        assertFalse(explorerOperatorVisible(ExplorerHealth.UNCONFIRMED, operator))
        assertFalse(explorerOperatorVisible(ExplorerHealth.TOR_STARTING, operator))
        assertFalse(explorerOperatorVisible(ExplorerHealth.TOR_REQUIRED, operator))
        assertFalse(explorerOperatorVisible(ExplorerHealth.NONE, operator))
        assertFalse(explorerOperatorVisible(ExplorerHealth.THIRD_PARTY, operator))
        assertFalse(explorerOperatorVisible(ExplorerHealth.BOUND, null))
        assertFalse(explorerOperatorVisible(ExplorerHealth.BOUND, " "))
        assertFalse(explorerOperatorVisible(ExplorerHealth.BOUND, ""))
    }

    @Test
    fun `a timeout result expires before the three minute window`() {
        val now = 1_700_000_000_000L
        val key = "PAIRED:http://explorer.example"
        val short = ExplorerProbeMemory.UNCONFIRMED_FRESH_FOR_MS
        assertTrue(short < ExplorerProbeMemory.FRESH_FOR_MS)
        assertTrue(short < 60_000L)
        val fresh = ExplorerProbeMemory.decide(
            ExplorerProbeMemory.Record(key, ExplorerProbeOutcome.UNCONFIRMED, now - short, 1, "127.0.0.1:9050"),
            key,
            now,
        )
        assertEquals(ExplorerProbeMemory.Plan.USE_FRESH, fresh.plan)
        val stale = ExplorerProbeMemory.decide(
            ExplorerProbeMemory.Record(key, ExplorerProbeOutcome.UNCONFIRMED, now - short - 1, 1, "127.0.0.1:9050"),
            key,
            now,
        )
        assertEquals(ExplorerProbeMemory.Plan.REPROBE_KEEPING_LAST, stale.plan)
        val stillGreen = ExplorerProbeMemory.decide(
            ExplorerProbeMemory.Record(key, ExplorerProbeOutcome.REACHABLE, now - short - 1, 1, "127.0.0.1:9050"),
            key,
            now,
        )
        assertEquals(ExplorerProbeMemory.Plan.USE_FRESH, stillGreen.plan)
    }

    @Test
    fun `the second line is tap to retry unless the row is green`() {
        val operator = "SG Directory Dojo"
        assertEquals("Checking… 0s", explorerCheckingLabel(0))
        assertEquals("Checking… 12s", explorerCheckingLabel(12_000))
        assertEquals("Checking… 12s", explorerCheckingLabel(12_999))
        assertEquals("Checking… 0s", explorerCheckingLabel(-5))
        assertEquals("Tap to retry", explorerDetailText(ExplorerHealth.CHECKING, operator))
        assertEquals("Tap to retry", explorerDetailText(ExplorerHealth.UNCONFIRMED, operator))
        assertEquals("Tap to retry", explorerDetailText(ExplorerHealth.UNREACHABLE, operator))
        assertEquals(operator, explorerDetailText(ExplorerHealth.BOUND, operator))
        assertNull(explorerDetailText(ExplorerHealth.BOUND, " "))
        assertNull(explorerDetailText(ExplorerHealth.TOR_STARTING, operator))
        assertNull(explorerDetailText(ExplorerHealth.TOR_REQUIRED, operator))
        assertNull(explorerDetailText(ExplorerHealth.NONE, operator))
        assertNull(explorerDetailText(ExplorerHealth.THIRD_PARTY, operator))
        assertTrue(explorerRowRetryable(ExplorerHealth.CHECKING))
        assertTrue(explorerRowRetryable(ExplorerHealth.UNCONFIRMED))
        assertTrue(explorerRowRetryable(ExplorerHealth.UNREACHABLE))
        assertFalse(explorerRowRetryable(ExplorerHealth.BOUND))
        assertFalse(explorerRowRetryable(ExplorerHealth.TOR_STARTING))
        val strings = source(
            "src/main/res/values/strings.xml",
            "app/src/main/res/values/strings.xml",
        )
        assertTrue(strings.contains("explorer_status_checking_elapsed\" translatable=\"false\">Checking… %1\$ds</string>"))
        assertTrue(strings.contains("explorer_status_unconfirmed\" translatable=\"false\">Could not confirm</string>"))
        assertTrue(strings.contains("explorer_status_tap_to_retry\" translatable=\"false\">Tap to retry</string>"))
        assertTrue(strings.contains("explorer_row_retry_description\" translatable=\"false\">Block Explorer, %1\$s. Tap to retry</string>"))
    }

    @Test
    fun `timeout is not red and a refusal or non-2xx on both paths is`() {
        assertEquals(FetchVerdict.NO_RESPONSE, failureVerdict(SocketTimeoutException("timeout")))
        assertEquals(
            FetchVerdict.NO_RESPONSE,
            failureVerdict(SocketTimeoutException("failed to connect to synthetic-self-explorer.onion/80")),
        )
        assertEquals(FetchVerdict.NO_RESPONSE, failureVerdict(InterruptedIOException("timeout")))
        assertEquals(
            FetchVerdict.NO_RESPONSE,
            failureVerdict(ConnectException("Failed to connect to synthetic-self-explorer.onion:80")),
        )
        assertEquals(FetchVerdict.DEFINITIVE, failureVerdict(ConnectException("Connection refused")))
        val wrapped = ConnectException("Failed to connect to synthetic-self-explorer.onion:80").apply {
            initCause(ConnectException("Connection refused"))
        }
        assertEquals(FetchVerdict.DEFINITIVE, failureVerdict(wrapped))
        assertEquals(FetchVerdict.DEFINITIVE, failureVerdict(SocketException("SOCKS: Host unreachable")))
        assertEquals(FetchVerdict.DEFINITIVE, failureVerdict(SocketException("SOCKS: Connection refused")))
        assertEquals(FetchVerdict.DEFINITIVE, failureVerdict(NoRouteToHostException("No route to host")))
        assertEquals(
            FetchVerdict.NO_RESPONSE,
            failureVerdict(SocketException("SOCKS: Network unreachable")),
        )
        assertEquals("SocketTimeoutException", probeFailureLabel(SocketTimeoutException("onion")))
        assertEquals("ConnectException", probeFailureLabel(ConnectException("Connection refused")))
        assertEquals("NoRouteToHostException", probeFailureLabel(NoRouteToHostException("No route to host")))
        assertEquals("SocketException", probeFailureLabel(SocketException("SOCKS: Host unreachable")))
        assertEquals("InterruptedIOException", probeFailureLabel(InterruptedIOException("timeout")))
        assertEquals("IOException", probeFailureLabel(object : IOException("PM8Tcrafted") {}))
        assertEquals("error", probeFailureLabel(IllegalStateException("OnionHostError")))
        assertEquals("SocketTimeoutException", statusToken("SocketTimeoutException"))
        assertEquals("error", statusToken("OnionHostError"))
        assertEquals("error", statusToken("SSLHandshakeError"))
        assertEquals("error", statusToken("PM8T" + "A".repeat(40) + "Error"))

        assertEquals(
            ProbeResult.Kind.REACHABLE,
            classifyFetches(listOf(FetchVerdict.DEFINITIVE, FetchVerdict.REACHABLE)),
        )
        assertEquals(
            ProbeResult.Kind.UNREACHABLE,
            classifyFetches(listOf(FetchVerdict.DEFINITIVE, FetchVerdict.DEFINITIVE)),
        )
        assertEquals(
            ProbeResult.Kind.UNCONFIRMED,
            classifyFetches(listOf(FetchVerdict.NO_RESPONSE)),
        )
        assertEquals(
            ProbeResult.Kind.UNCONFIRMED,
            classifyFetches(listOf(FetchVerdict.DEFINITIVE, FetchVerdict.NO_RESPONSE)),
        )
        assertEquals(ProbeResult.Kind.UNCONFIRMED, classifyFetches(emptyList()))
        assertEquals(FetchVerdict.DEFINITIVE, UrlFetch(false, "404").verdict)
        assertEquals(FetchVerdict.REACHABLE, UrlFetch(true, "200").verdict)
    }

    @Test
    fun `the live probe maps timeout to amber, refusal to red, and 2xx to green`() = runBlocking {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", 9050))
        val base = "http://synthetic-self-explorer.onion"
        suspend fun run(fetch: suspend (okhttp3.OkHttpClient, String) -> UrlFetch): ProbeResult =
            ExplorerHealthProbe.runProbe(
                baseUrl = base,
                endpointAtSchedule = "127.0.0.1:9050",
                torReady = { true },
                currentProxy = { proxy },
                currentEndpoint = { "127.0.0.1:9050" },
                fetchUrl = fetch,
            )

        val timeout = run { _, _ ->
            UrlFetch(false, "SocketTimeoutException", FetchVerdict.NO_RESPONSE)
        }
        assertEquals(ProbeResult.Kind.UNCONFIRMED, timeout.kind)
        assertEquals("SocketTimeoutException", timeout.detail)

        val refused = run { _, _ ->
            UrlFetch(false, "ConnectException", FetchVerdict.DEFINITIVE)
        }
        assertEquals(ProbeResult.Kind.UNREACHABLE, refused.kind)
        assertEquals("ConnectException", refused.detail)

        val bothHttp = run { _, _ -> UrlFetch(false, "503") }
        assertEquals(ProbeResult.Kind.UNREACHABLE, bothHttp.kind)
        assertEquals("503", bothHttp.detail)

        var n = 0
        val mixed = run { _, _ ->
            n += 1
            if (n == 1) {
                UrlFetch(false, "404")
            } else {
                UrlFetch(false, "SocketTimeoutException", FetchVerdict.NO_RESPONSE)
            }
        }
        assertEquals(ProbeResult.Kind.UNCONFIRMED, mixed.kind)

        val ok = run { _, url ->
            if (url.endsWith("/api/blocks/tip/height")) UrlFetch(false, "404") else UrlFetch(true, "200")
        }
        assertEquals(ProbeResult.Kind.REACHABLE, ok.kind)
        assertEquals("200", ok.detail)
        assertEquals(ProbeResult.Reachable, ok)
    }

    @Test
    fun `release keeps one redacted result line and drops the rest`() {
        val onionHost = "abcd" + "e".repeat(48) + "wxyz"
        val txid = "ab".repeat(32)
        val xpub = "xpub" + "1".repeat(100)
        val nasty = "http://user:secret@$onionHost.onion/api/blocks/tip/height?at=supersecret"
        val line = ExplorerProbeLog.resultLine(nasty, "UNCONFIRMED", "SocketTimeoutException", 30_012)
        assertEquals(
            "result outcome=UNCONFIRMED status=SocketTimeoutException elapsedMs=30012",
            line,
        )
        assertFalse(line.contains(onionHost))
        assertFalse(line.contains("onion"))
        assertFalse(line.contains("supersecret"))
        assertFalse(line.contains("secret"))
        assertFalse(line.contains("http"))
        assertFalse(line.contains("host="))
        assertFalse(line.contains("/api"))
        assertEquals(line, LogRedaction.redact(line))

        val reachable = ExplorerProbeLog.resultLine("https://mempool.space", "REACHABLE", "200", 18)
        assertEquals("result outcome=REACHABLE status=200 elapsedMs=18", reachable)
        val refused = ExplorerProbeLog.resultLine("http://203.0.113.10", "UNREACHABLE", "ConnectException", 4)
        assertEquals("result outcome=UNREACHABLE status=ConnectException elapsedMs=4", refused)
        val empty = ExplorerProbeLog.resultLine(nasty, "UNREACHABLE", "", 1)
        assertEquals("result outcome=UNREACHABLE status=none elapsedMs=1", empty)
        val noUrls = ExplorerProbeLog.resultLine(nasty, "UNREACHABLE", "empty", 1)
        assertEquals("result outcome=UNREACHABLE status=empty elapsedMs=1", noUrls)

        val dirty = ExplorerProbeLog.resultLine(nasty, "UNCONFIRMED", "404?at=supersecret", 5)
        assertEquals("result outcome=UNCONFIRMED status=error elapsedMs=5", dirty)
        val asDetail = ExplorerProbeLog.resultLine("http://example.test", "UNREACHABLE", nasty, 2)
        assertEquals("result outcome=UNREACHABLE status=error elapsedMs=2", asDetail)
        assertFalse(asDetail.contains(onionHost))
        val asOutcome = ExplorerProbeLog.resultLine(nasty, nasty, "200", 2)
        assertEquals("result outcome=error status=200 elapsedMs=2", asOutcome)
        val txLine = ExplorerProbeLog.resultLine(nasty, "REACHABLE", txid, 3)
        assertEquals("result outcome=REACHABLE status=error elapsedMs=3", txLine)
        assertFalse(txLine.contains(txid))
        val keyLine = ExplorerProbeLog.resultLine(nasty, "UNREACHABLE", xpub, 3)
        assertEquals("result outcome=UNREACHABLE status=error elapsedMs=3", keyLine)
        assertFalse(keyLine.contains("xpub"))
        val ipLine = ExplorerProbeLog.resultLine("http://203.0.113.10/x", "UNREACHABLE", "203.0.113.10", 3)
        assertEquals("result outcome=UNREACHABLE status=error elapsedMs=3", ipLine)
        assertFalse(ipLine.contains("203.0.113.10"))
        val onionDetail = ExplorerProbeLog.resultLine(nasty, "UNCONFIRMED", "$onionHost.onion", 3)
        assertEquals("result outcome=UNCONFIRMED status=error elapsedMs=3", onionDetail)
        assertFalse(onionDetail.contains("onion"))

        assertTrue(releaseKeepsExplorerProbe(Log.INFO, ExplorerProbeLog.TAG, line, null))
        assertTrue(releaseKeepsExplorerProbe(Log.INFO, ExplorerProbeLog.TAG, reachable, null))
        assertFalse(releaseKeepsExplorerProbe(Log.DEBUG, ExplorerProbeLog.TAG, line, null))
        assertFalse(releaseKeepsExplorerProbe(Log.INFO, ExplorerProbeLog.TAG, line, IllegalStateException("x")))
        assertFalse(releaseKeepsExplorerProbe(Log.INFO, "OkHttp", line, null))
        val shortened = ExplorerBinding.shortenOnion(nasty)!!
        assertFalse(
            releaseKeepsExplorerProbe(
                Log.INFO,
                ExplorerProbeLog.TAG,
                "result host=$shortened outcome=UNCONFIRMED status=SocketTimeoutException elapsedMs=1",
                null,
            ),
        )
        assertFalse(
            releaseKeepsExplorerProbe(
                Log.INFO,
                ExplorerProbeLog.TAG,
                "result host=$onionHost.onion outcome=UNCONFIRMED status=none elapsedMs=1",
                null,
            ),
        )
        val leaked = LogRedaction.redact("result host=$onionHost.onion outcome=UNCONFIRMED status=none elapsedMs=1")
        assertFalse(leaked.contains(onionHost))
        assertTrue(leaked.contains("ONION_REDACTED"))
        assertFalse(
            releaseKeepsExplorerProbe(
                Log.INFO,
                ExplorerProbeLog.TAG,
                "done host=x outcome=REACHABLE elapsedMs=1",
                null,
            ),
        )
        assertFalse(
            releaseKeepsExplorerProbe(
                Log.INFO,
                ExplorerProbeLog.TAG,
                "start host=x socks=127.0.0.1:9050",
                null,
            ),
        )
        assertFalse(
            releaseKeepsExplorerProbe(
                Log.INFO,
                ExplorerProbeLog.TAG,
                "url host=x path=/api result=200 elapsedMs=1",
                null,
            ),
        )
        assertFalse(
            releaseKeepsExplorerProbe(
                Log.INFO,
                ExplorerProbeLog.TAG,
                "cache hit host=x outcome=REACHABLE ageMs=1",
                null,
            ),
        )
        assertFalse(
            releaseKeepsExplorerProbe(
                Log.INFO,
                ExplorerProbeLog.TAG,
                "abort host=x reason=socks-changed socks=none",
                null,
            ),
        )
        assertFalse(
            releaseKeepsExplorerProbe(
                Log.INFO,
                ExplorerProbeLog.TAG,
                "result outcome=UNCONFIRMED status=OnionHostError elapsedMs=1",
                null,
            ),
        )
        assertFalse(
            releaseKeepsExplorerProbe(
                Log.INFO,
                ExplorerProbeLog.TAG,
                "result outcome=UNREACHABLE status=PM8T" + "A".repeat(40) + "Error elapsedMs=1",
                null,
            ),
        )
        val probeSrc = source(
            "src/main/java/com/samourai/sentinel/ui/webview/ExplorerHealthProbe.kt",
            "app/src/main/java/com/samourai/sentinel/ui/webview/ExplorerHealthProbe.kt",
        )
        val coordSrc = source(
            "src/main/java/com/samourai/sentinel/ui/webview/ExplorerProbeCoordinator.kt",
            "app/src/main/java/com/samourai/sentinel/ui/webview/ExplorerProbeCoordinator.kt",
        )
        val logSrc = source(
            "src/main/java/com/samourai/sentinel/ui/webview/ExplorerProbeLog.kt",
            "app/src/main/java/com/samourai/sentinel/ui/webview/ExplorerProbeLog.kt",
        )
        assertFalse(probeSrc.contains("simpleName"))
        assertFalse(coordSrc.contains("simpleName"))
        assertFalse(logSrc.contains("simpleName"))
        assertTrue(probeSrc.contains("probeFailureLabel"))
        assertTrue(coordSrc.contains("probeFailureLabel"))
        assertTrue(logSrc.contains("error is TimeoutCancellationException -> \"TimeoutCancellationException\""))
        val app = source(
            "src/main/java/com/samourai/sentinel/SentinelApplication.kt",
            "app/src/main/java/com/samourai/sentinel/SentinelApplication.kt",
        )
        val tree = app.substringAfter("class CrashReportingTree")
        assertTrue(tree.contains("releaseKeepsExplorerProbe"))
        assertTrue(tree.contains("Log.println"))
        assertTrue(tree.contains("LogRedaction.redact"))
        assertTrue(tree.indexOf("releaseKeepsExplorerProbe") < tree.indexOf("if (t == null)"))
    }

    @Test
    fun `debug probe lines keep a shortened onion and drop addresses and secrets`() {
        val onionHost = "abcd" + "e".repeat(48) + "wxyz"
        val txid = "cd".repeat(32)
        val nasty = "http://user:secret@$onionHost.onion/api/blocks/tip/height?at=supersecret"
        val url = ExplorerProbeLog.urlLine(nasty, "200", 12)
        assertTrue(url.contains("path=/api/blocks/tip/height"))
        assertTrue(url.contains("result=200"))
        assertTrue(url.contains(ExplorerBinding.shortenOnion(nasty)!!))
        assertFalse(url.contains(onionHost))
        assertFalse(url.contains("supersecret"))
        assertFalse(url.contains("secret@"))
        assertFalse(url.contains("?"))

        assertEquals("ip", ExplorerProbeLog.host("http://203.0.113.10:8333/explorer"))
        assertEquals("ip", ExplorerProbeLog.host("http://[2001:db8::1]/"))
        assertEquals("mempool.space", ExplorerProbeLog.host("https://mempool.space/tx/abc"))
        assertEquals("/", ExplorerProbeLog.pathOf("http://example.test/tx/$txid"))
        assertEquals("/", ExplorerProbeLog.pathOf("http://example.test/wallet/xpub" + "6".repeat(100)))
        assertEquals("none", ExplorerProbeLog.socksLabel("none"))
        assertEquals("127.0.0.1:9050", ExplorerProbeLog.socksLabel("127.0.0.1:9050"))
        assertEquals("[::1]:9050", ExplorerProbeLog.socksLabel("[::1]:9050"))
        assertEquals("unknown", ExplorerProbeLog.socksLabel("203.0.113.10:9050"))
        assertEquals("unknown", ExplorerProbeLog.socksLabel("2001:db8::1:9050"))
        val paymentCode = SamouraiWalletConst.samouraiDonationPCode
        assertTrue(paymentCode.startsWith("PM8T"))
        assertEquals("/", ExplorerProbeLog.pathOf("http://example.test/pay/$paymentCode"))
        val payLine = ExplorerProbeLog.urlLine("http://example.test/pay/$paymentCode", "200", 4)
        assertTrue(payLine.contains("path=/"))
        assertFalse(payLine.contains(paymentCode))
        assertFalse(payLine.contains("PM8T"))
        val dotted = ExplorerProbeLog.urlLine("https://mempool.space/api", "203.0.113.10", 1)
        assertTrue(dotted.contains("result=error"))
        assertFalse(dotted.contains("203.0.113.10"))
    }

    @Test
    fun `the health check builds a client only after the tor gate`() {
        val src = source(
            "app/src/main/java/com/samourai/sentinel/ui/webview/ExplorerHealthProbe.kt",
            "src/main/java/com/samourai/sentinel/ui/webview/ExplorerHealthProbe.kt",
        )
        val body = src.substringAfter("fun probe(")
        val routeAt = body.indexOf("explorerProbeRoute")
        val builderAt = body.indexOf("OkHttpClient.Builder")
        assertTrue(routeAt >= 0 && builderAt > routeAt)
        assertTrue(src.contains(".proxy(proxy)"))
        assertTrue(src.contains("TlsPolicy.install"))
        assertFalse(src.contains("Proxy.NO_PROXY"))
        assertFalse(src.contains("callTimeout(180"))
    }

    private fun state(
        resolution: ExplorerBinding.ExplorerResolution,
        torReady: Boolean,
        torInProgress: Boolean = false,
        proxy: Boolean = false,
        probe: ExplorerProbeOutcome = ExplorerProbeOutcome.PENDING,
    ): ExplorerHealth = explorerHealth(resolution, torReady, torInProgress, proxy, probe).health

    private fun source(vararg relative: String): String {
        val file = relative.map { File(it) }.firstOrNull { it.isFile }
            ?: error("source not found from ${File(".").absolutePath}: ${relative.toList()}")
        return file.readText()
    }
}
