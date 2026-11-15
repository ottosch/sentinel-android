package com.samourai.sentinel.ui.dojo

import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.ui.dojo.CommunityDojoFetchPolicy.FIRST_AFTER_TOR_START_TIMEOUT_SECONDS
import com.samourai.sentinel.ui.dojo.CommunityDojoFetchPolicy.NORMAL_TIMEOUT_SECONDS
import com.samourai.sentinel.ui.dojo.CommunityDojoRepository.Failure
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ConnectException
import java.net.Proxy
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException

class CommunityDojoFetchPolicyTest {

    @Before
    fun setUp() = CommunityDojoFetchPolicy.resetForTest()

    @After
    fun tearDown() = CommunityDojoFetchPolicy.resetForTest()

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
    fun `the first attempt after a Tor start gets the longer limit, the retry and later fetches the normal one`() {
        assertEquals(FIRST_AFTER_TOR_START_TIMEOUT_SECONDS, CommunityDojoFetchPolicy.timeoutFor(attempt = 1, socksGeneration = 4))
        // The automatic retry is a normal attempt.
        assertEquals(NORMAL_TIMEOUT_SECONDS, CommunityDojoFetchPolicy.timeoutFor(attempt = 2, socksGeneration = 4))
        CommunityDojoFetchPolicy.settle(4)
        // Same Tor session: normal.
        assertEquals(NORMAL_TIMEOUT_SECONDS, CommunityDojoFetchPolicy.timeoutFor(attempt = 1, socksGeneration = 4))
        // Tor restarted (new SOCKS session): longer again.
        assertEquals(FIRST_AFTER_TOR_START_TIMEOUT_SECONDS, CommunityDojoFetchPolicy.timeoutFor(attempt = 1, socksGeneration = 6))
        assertEquals(NORMAL_TIMEOUT_SECONDS, CommunityDojoFetchPolicy.timeoutFor(attempt = 2, socksGeneration = 6))
    }

    @Test
    fun `limits - normal stays 120 s, the first fetch 240 s, and Tor never gives up before the app`() {
        assertEquals(120L, NORMAL_TIMEOUT_SECONDS)
        assertEquals(240L, FIRST_AFTER_TOR_START_TIMEOUT_SECONDS)
        assertEquals(NORMAL_TIMEOUT_SECONDS, CommunityDojoRepository.TIMEOUT_SECONDS)
        assertTrue(SentinelTorManager.SOCKS_TIMEOUT_SECONDS > FIRST_AFTER_TOR_START_TIMEOUT_SECONDS)
        assertEquals(363L, CommunityDojoFetchPolicy.WORST_CASE_SECONDS)
    }

    @Test
    fun `the chosen limit reaches the client`() {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", 9050))
        val first = CommunityDojoRepository.buildClient(proxy, FIRST_AFTER_TOR_START_TIMEOUT_SECONDS)
        assertEquals(240_000, first.callTimeoutMillis)
        assertEquals(240_000, first.connectTimeoutMillis)
        assertEquals(240_000, first.readTimeoutMillis)
        val normal = CommunityDojoRepository.buildClient(proxy)
        assertEquals(120_000, normal.callTimeoutMillis)
    }
}
