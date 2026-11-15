package com.samourai.sentinel.ui.dojo

import com.samourai.sentinel.ui.dojo.CommunityDojoRepository.Failure
import com.samourai.sentinel.ui.dojo.CommunityDojoRepository.FetchException
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
        server.enqueue(MockResponse().setBody("""{"generated_at":"x","nodes":[{"id":"a","network":"testnet","status":"active"}]}"""))
        val nodes = CommunityDojoRepository.fetchWith(client(), url())
        assertEquals(1, nodes.size)
        assertEquals("a", nodes[0].id)
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
}
