package com.samourai.sentinel.ui.dojo

import com.samourai.sentinel.ui.dojo.CommunityDojoRepository.Failure
import com.samourai.sentinel.ui.dojo.CommunityDojoRepository.FetchException
import com.samourai.sentinel.ui.dojo.DojoBaySignatures.OperatorCheck
import com.samourai.sentinel.ui.dojo.DojoBaySignatures.OperatorFailure
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * Every Dojo Bay failure mode from the outage test plan, against a local
 * server. The real app adds only the Tor proxy in front of this client.
 */
class CommunityDojoRepositoryTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(timeoutMs: Long = 2_000) = OkHttpClient.Builder()
        .followRedirects(false)
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private fun url() = server.url("/data/dojos.json").toString()

    private fun failureOf(block: suspend () -> Unit): FetchException {
        try {
            runBlocking { block() }
        } catch (e: FetchException) {
            return e
        }
        fail("expected a FetchException")
        throw AssertionError()
    }

    @Test
    fun `ok directory parses`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"generated_at":"x","nodes":[{"id":"a","network":"testnet","status":"active","signed":"s"}]}"""))
        val nodes = CommunityDojoRepository.fetchWith(client(), url())
        assertEquals(1, nodes.size)
        assertEquals("s", nodes[0].signed)
    }

    @Test
    fun `c - HTTP 500 and 404 are HTTP errors`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        val e500 = failureOf { CommunityDojoRepository.fetchWith(client(), url()) }
        assertEquals(Failure.HTTP_ERROR, e500.failure)
        assertEquals(500, e500.httpCode)
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(404, failureOf { CommunityDojoRepository.fetchWith(client(), url()) }.httpCode)
    }

    @Test
    fun `d - malformed, html, array, null and blank bodies are bad responses`() {
        for (body in listOf("""{"nodes": [ {"id": "x", """, "<html>maintenance</html>", "[]", "null", "")) {
            server.enqueue(MockResponse().setBody(body))
            assertEquals(body, Failure.BAD_RESPONSE, failureOf { CommunityDojoRepository.fetchWith(client(), url()) }.failure)
        }
    }

    @Test
    fun `d - an empty directory is an empty list, not an error`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        assertTrue(CommunityDojoRepository.fetchWith(client(), url()).isEmpty())
        server.enqueue(MockResponse().setBody("""{"nodes":[]}"""))
        assertTrue(CommunityDojoRepository.fetchWith(client(), url()).isEmpty())
    }

    @Test
    fun `b - a server that accepts and never answers is a timeout`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertEquals(Failure.TIMEOUT, failureOf { CommunityDojoRepository.fetchWith(client(500), url()) }.failure)
    }

    @Test
    fun `b - headers then a stalled body is a timeout`() {
        server.enqueue(MockResponse().setBody("{}").throttleBody(1, 1, TimeUnit.SECONDS))
        assertEquals(Failure.TIMEOUT, failureOf { CommunityDojoRepository.fetchWith(client(500), url()) }.failure)
    }

    @Test
    fun `a - nothing listening is unreachable`() {
        val port = ServerSocket(0).use { it.localPort }
        val e = failureOf { CommunityDojoRepository.fetchWith(client(), "http://127.0.0.1:$port/data/dojos.json") }
        assertEquals(Failure.UNREACHABLE, e.failure)
    }

    @Test
    fun `an oversized body is refused`() {
        server.enqueue(MockResponse().setBody("{\"nodes\":[],\"pad\":\"" + "x".repeat(CommunityDojoRepository.MAX_DIRECTORY_BYTES) + "\"}"))
        assertEquals(Failure.TOO_LARGE, failureOf { CommunityDojoRepository.fetchWith(client(), url()) }.failure)
    }

    @Test
    fun `redirects are not followed`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://evil.example/dojos.json"))
        assertEquals(302, failureOf { CommunityDojoRepository.fetchWith(client(), url()) }.httpCode)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `failure text is fixed and carries no address or server text`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("secret server text"))
        val e = failureOf { CommunityDojoRepository.fetchWith(client(), url()) }
        assertFalse(e.message!!.contains("secret"))
        assertFalse(e.message!!.contains("127.0.0.1"))
    }

    @Test
    fun `f - recovery after the service comes back`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        try {
            CommunityDojoRepository.fetchWith(client(), url())
            fail()
        } catch (e: FetchException) {
            assertEquals(Failure.HTTP_ERROR, e.failure)
        }
        server.enqueue(MockResponse().setBody("""{"nodes":[{"id":"a","network":"testnet"}]}"""))
        assertEquals(1, CommunityDojoRepository.fetchWith(client(), url()).size)
    }

    @Test
    fun `classify maps transport exceptions`() {
        assertEquals(Failure.TIMEOUT, CommunityDojoRepository.classify(java.net.SocketTimeoutException()).failure)
        assertEquals(Failure.TIMEOUT, CommunityDojoRepository.classify(java.io.InterruptedIOException("timeout")).failure)
        // What Tor answers through SOCKS for an onion it cannot reach (measured): not a timeout.
        assertEquals(Failure.UNREACHABLE, CommunityDojoRepository.classify(java.net.SocketException("SOCKS: TTL expired")).failure)
        assertEquals(Failure.UNREACHABLE, CommunityDojoRepository.classify(java.net.SocketException("SOCKS: Host unreachable")).failure)
        assertEquals(Failure.UNREACHABLE, CommunityDojoRepository.classify(java.net.ConnectException("Connection refused")).failure)
    }

    // ---- operator binding, fetched before the directory -------------------

    private fun serve(dojos: MockResponse, operator: MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/data/dojos.json" -> dojos
                "/data/operator.json" -> operator
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    @Test
    fun `operator json is fetched from the same root and checked against it`() = runBlocking {
        val base = server.url("/").toString()
        val alice = DojoBayTestVectors.ThrowawayPayNym(7)
        val doc = alice.operatorDoc(base)
        serve(
            MockResponse().setBody("""{"nodes":[]}"""),
            MockResponse().setBody(com.google.gson.Gson().toJson(doc)),
        )
        val dir = CommunityDojoRepository.fetchBoth(client(), base)
        assertEquals(OperatorCheck.Verified(alice.code), dir.operator)
    }

    @Test
    fun `operator json http error is a failed fetch, not a refusal`() {
        val base = server.url("/").toString()
        for (code in listOf(408, 429, 500, 502, 503)) {
            serve(MockResponse().setBody("""{"nodes":[]}"""), MockResponse().setResponseCode(code))
            val e = failureOf { CommunityDojoRepository.fetchBoth(client(), base) }
            assertEquals("HTTP $code", Failure.HTTP_ERROR, e.failure)
            assertEquals(code, e.httpCode)
        }
    }

    @Test
    fun `operator json 404 after the list answered is its own failure, not unreachable`() {
        val base = server.url("/").toString()
        serve(MockResponse().setBody("""{"nodes":[]}"""), MockResponse().setResponseCode(404))
        val e = failureOf { CommunityDojoRepository.fetchBoth(client(), base) }
        assertEquals(Failure.NO_OPERATOR_STATEMENT, e.failure)
        assertEquals(404, e.httpCode)
        // The directory's own 404 is still an HTTP error.
        serve(MockResponse().setResponseCode(404), MockResponse().setResponseCode(404))
        assertEquals(Failure.HTTP_ERROR, failureOf { CommunityDojoRepository.fetchBoth(client(), base) }.failure)
    }

    @Test
    fun `blank or unreadable operator json is a failed fetch, not a refusal`() {
        val base = server.url("/").toString()
        for (body in listOf("", "   ", "<html>not json</html>", "[1,2]", "{'onion':'x'}", """{"onion":"x"} trailing""")) {
            serve(MockResponse().setBody("""{"nodes":[]}"""), MockResponse().setBody(body))
            val e = failureOf { CommunityDojoRepository.fetchBoth(client(), base) }
            assertEquals("body <$body>", Failure.BAD_RESPONSE, e.failure)
        }
    }

    @Test
    fun `served operator json without code or block is a failed check`() = runBlocking {
        val base = server.url("/").toString()
        serve(MockResponse().setBody("""{"nodes":[]}"""), MockResponse().setBody("""{"onion":"$base"}"""))
        assertEquals(OperatorCheck.Failed(OperatorFailure.MISSING), CommunityDojoRepository.fetchBoth(client(), base).operator)
    }

    @Test
    fun `operator json that cannot be reached is a failed fetch, not a refusal`() {
        val base = server.url("/").toString()
        serve(MockResponse().setBody("""{"nodes":[]}"""), MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val e = failureOf { CommunityDojoRepository.fetchBoth(client(), base) }
        assertEquals(Failure.UNREACHABLE, e.failure)
    }

    @Test
    fun `operator json that never answers times out like the directory`() {
        val base = server.url("/").toString()
        serve(MockResponse().setBody("""{"nodes":[]}"""), MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val e = failureOf { CommunityDojoRepository.fetchBoth(client(timeoutMs = 500), base) }
        assertEquals(Failure.TIMEOUT, e.failure)
    }

    @Test
    fun `operator 503 with a saved list - the fetch fails and the saved list is shown`() {
        val base = server.url("/").toString()
        val code = DojoBayConstants.DEFAULT_OPERATOR_CODE
        val saved = CommunityDojoCache.Entry(base, 7L, listOf(DojoBayTestVectors.realSeedNode()), code)
        serve(MockResponse().setBody("""{"nodes":[]}"""), MockResponse().setResponseCode(503))
        val e = failureOf { CommunityDojoRepository.fetchBoth(client(), base) }
        assertEquals(Failure.HTTP_ERROR, e.failure)
        // The sheet's failure path: nothing refused, nothing cleared.
        val d = CommunityDojoDisplay.afterFailure(e.failure, saved, base, code, "mainnet")
        assertTrue(d is CommunityDojoDisplay.Cached)
        assertEquals(7L, (d as CommunityDojoDisplay.Cached).fetchedAtMs)
    }

    /** Paths requested since the last call, in order. */
    private fun requestedPaths(): List<String?> {
        val paths = mutableListOf<String?>()
        while (true) {
            val r = server.takeRequest(10, TimeUnit.MILLISECONDS) ?: break
            paths += r.path
        }
        return paths
    }

    @Test
    fun `operator json is asked for first, then the list from the same instance`() = runBlocking {
        val base = server.url("/").toString()
        val alice = DojoBayTestVectors.ThrowawayPayNym(7)
        serve(
            MockResponse().setBody("""{"nodes":[]}"""),
            MockResponse().setBody(com.google.gson.Gson().toJson(alice.operatorDoc(base))),
        )
        val dir = CommunityDojoRepository.fetchBoth(client(), base, alice.code)
        assertTrue(dir.listFetched)
        assertEquals(listOf("/data/operator.json", "/data/dojos.json"), requestedPaths())
    }

    @Test
    fun `a refusal the operator check already decides never requests the list`() = runBlocking {
        val base = server.url("/").toString()
        val alice = DojoBayTestVectors.ThrowawayPayNym(7)
        val bob = DojoBayTestVectors.ThrowawayPayNym(8)
        // Signed by a different code from the one this address is pinned to.
        serve(
            MockResponse().setBody("""{"nodes":[]}"""),
            MockResponse().setBody(com.google.gson.Gson().toJson(alice.operatorDoc(base))),
        )
        val mismatch = CommunityDojoRepository.fetchBoth(client(), base, bob.code)
        assertFalse(mismatch.listFetched)
        assertTrue(mismatch.nodes.isEmpty())
        assertEquals(listOf("/data/operator.json"), requestedPaths())
        val decision = DojoBayTrust.decide(base, mismatch.operator, bob.code)
        assertEquals(DojoBayTrust.RefusalReason.CODE_MISMATCH, (decision as DojoBayTrust.Decision.Refused).reason)
        // Refused, not "lists no Dojos": the list was never asked for.
        assertTrue(CommunityDojoDisplay.afterSuccess(mismatch, decision, "testnet") is CommunityDojoDisplay.Refused)

        // A served document without code or block.
        serve(MockResponse().setBody("""{"nodes":[]}"""), MockResponse().setBody("""{"onion":"$base"}"""))
        val missing = CommunityDojoRepository.fetchBoth(client(), base, bob.code)
        assertFalse(missing.listFetched)
        assertEquals(listOf("/data/operator.json"), requestedPaths())
    }

    @Test
    fun `operator json that cannot be fetched does not request the list, except a 404`() {
        val base = server.url("/").toString()
        serve(MockResponse().setBody("""{"nodes":[]}"""), MockResponse().setResponseCode(503))
        assertEquals(Failure.HTTP_ERROR, failureOf { CommunityDojoRepository.fetchBoth(client(), base) }.failure)
        assertEquals(listOf("/data/operator.json"), requestedPaths())
        serve(MockResponse().setBody("""{"nodes":[]}"""), MockResponse().setResponseCode(404))
        assertEquals(Failure.NO_OPERATOR_STATEMENT, failureOf { CommunityDojoRepository.fetchBoth(client(), base) }.failure)
        assertEquals(listOf("/data/operator.json", "/data/dojos.json"), requestedPaths())
    }

    @Test
    fun `a custom address without an operator code is refused with no code to fetch for`() {
        val custom = "http://" + "a".repeat(56) + ".onion/"
        assertEquals(null, DojoBayTrust.expectedCode(custom, null))
        assertEquals(null, DojoBayTrust.expectedCode(custom, "  "))
    }

    @Test
    fun `one limit covers both requests of an attempt`() {
        val base = server.url("/").toString()
        val alice = DojoBayTestVectors.ThrowawayPayNym(7)
        // Each request alone is well inside the client's 2 s; together they pass the 700 ms attempt limit.
        serve(
            MockResponse().setBody("""{"nodes":[]}""").setBodyDelay(500, TimeUnit.MILLISECONDS),
            MockResponse().setBody(com.google.gson.Gson().toJson(alice.operatorDoc(base))).setBodyDelay(500, TimeUnit.MILLISECONDS),
        )
        val e = failureOf { CommunityDojoRepository.fetchBothWithin(client(), base, alice.code, 700) }
        assertEquals(Failure.TIMEOUT, e.failure)
        // With room for both, the same attempt succeeds.
        val dir = runBlocking { CommunityDojoRepository.fetchBothWithin(client(), base, alice.code, 1_800) }
        assertTrue(dir.listFetched)
    }
}
