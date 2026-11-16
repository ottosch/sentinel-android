package com.samourai.sentinel.ui.dojo

import com.samourai.sentinel.ui.dojo.CommunityDojoFetchPolicy.NORMAL_TIMEOUT_SECONDS
import com.samourai.sentinel.ui.dojo.CommunityDojoRepository.Failure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.Proxy
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException

class CommunityDojoFetchPolicyTest {

    private fun retriesAfter(e: Exception, attempt: Int = 1) =
        CommunityDojoFetchPolicy.shouldRetry(CommunityDojoRepository.classify(e).failure, attempt)

    @Test
    fun `one retry whichever side gave up - the app's timeout or Tor failing the stream`() {
        // The app's own timeouts.
        assertTrue(retriesAfter(SocketTimeoutException()))
        assertTrue(retriesAfter(java.io.InterruptedIOException("timeout")))
        // Tor gave up first (its SOCKS timeout), or failed the stream.
        assertTrue(retriesAfter(SocketException("SOCKS: TTL expired")))
        assertTrue(retriesAfter(SocketException("SOCKS: Host unreachable")))
        assertTrue(retriesAfter(SocketException("SOCKS server general failure")))
        // The Tor proxy refused or reset the connection.
        assertTrue(retriesAfter(ConnectException("Connection refused")))
        assertTrue(retriesAfter(SocketException("Connection reset")))
    }

    @Test
    fun `exactly one retry - the second attempt is never retried`() {
        for (failure in listOf(Failure.TIMEOUT, Failure.UNREACHABLE)) {
            assertTrue(CommunityDojoFetchPolicy.shouldRetry(failure, 1))
            assertFalse(CommunityDojoFetchPolicy.shouldRetry(failure, 2))
            assertFalse(CommunityDojoFetchPolicy.shouldRetry(failure, 3))
        }
        assertEquals(1, CommunityDojoFetchPolicy.AUTO_RETRIES)
    }

    @Test
    fun `an answer from Dojo Bay or Tor not being available is not retried`() {
        for (failure in Failure.values().filter { it != Failure.TIMEOUT && it != Failure.UNREACHABLE }) {
            assertFalse(failure.name, CommunityDojoFetchPolicy.shouldRetry(failure, 1))
        }
    }

    @Test
    fun `every attempt gets 120 s - Tor's default SOCKS timeout - and the retry is the only extra wait`() {
        assertEquals(120L, NORMAL_TIMEOUT_SECONDS)
        assertEquals(NORMAL_TIMEOUT_SECONDS, CommunityDojoRepository.TIMEOUT_SECONDS)
        assertEquals(243L, CommunityDojoFetchPolicy.WORST_CASE_SECONDS)
    }

    @Test
    fun `Tor's SocksTimeout is left at its default`() {
        val path = "java/com/samourai/sentinel/tor/SentinelTorManager.kt"
        val src = listOf(java.io.File("src/main/$path"), java.io.File("app/src/main/$path")).first { it.isFile }.readText()
        assertFalse(src.contains("SocksTimeout"))
    }

    @Test
    fun `the limit reaches the client`() {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", 9050))
        val client = CommunityDojoRepository.buildClient(proxy)
        assertEquals(120_000, client.callTimeoutMillis)
        assertEquals(120_000, client.connectTimeoutMillis)
        assertEquals(120_000, client.readTimeoutMillis)
    }

    @Test
    fun `each attempt gets its own isolation, so the retry gets a fresh circuit`() {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", 9050))
        val first = TorIsolatedSocks.fromProxy(proxy)
        val retry = TorIsolatedSocks.fromProxy(proxy)
        assertTrue(first.isolationToken != retry.isolationToken)
    }
}
