package com.samourai.sentinel.ui.dojo

import com.samourai.sentinel.ui.dojo.CommunityDojoRepository.Failure
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

/**
 * A minimal SOCKS5 server standing in for Tor: records the user name and
 * target of every CONNECT, then answers the HTTP request itself.
 */
class TorIsolatedSocksTest {

    data class Seen(val user: String, val host: String, val port: Int)

    private val seen = Collections.synchronizedList(mutableListOf<Seen>())
    private val server = ServerSocket(0)
    @Volatile private var replyCode = 0x00
    @Volatile private var keepAlive = false

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) {
                    s.use { sock ->
                        val input = DataInputStream(sock.getInputStream())
                        val out = sock.getOutputStream()
                        input.readUnsignedByte(); val n = input.readUnsignedByte(); repeat(n) { input.readUnsignedByte() }
                        out.write(byteArrayOf(5, 2))
                        input.readUnsignedByte()
                        val user = String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
                        ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
                        out.write(byteArrayOf(1, 0))
                        input.readUnsignedByte(); input.readUnsignedByte(); input.readUnsignedByte()
                        check(input.readUnsignedByte() == 3)
                        val host = String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
                        val port = input.readUnsignedShort()
                        seen += Seen(user, host, port)
                        out.write(byteArrayOf(5, replyCode.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
                        if (replyCode != 0) return@use
                        // Read the HTTP request head, answer with an empty directory.
                        val reader = sock.getInputStream().bufferedReader()
                        while (reader.readLine()?.isNotEmpty() == true) Unit
                        val body = """{"nodes":[]}"""
                        val connection = if (keepAlive) "keep-alive" else "close"
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: $connection\r\n\r\n$body".toByteArray())
                        out.flush()
                        // Keep-alive: hold the connection open until the client closes it.
                        if (keepAlive) while (reader.read() != -1) Unit
                    }
                }
            }
        }
    }

    @After
    fun tearDown() = server.close()

    private val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", server.localPort))
    private val onion = "http://" + "a".repeat(56) + ".onion/data/dojos.json"

    @Test
    fun `each attempt asks Tor for its own circuit, by host name, never resolving it`() = runBlocking {
        CommunityDojoRepository.fetchWith(CommunityDojoRepository.buildClient(proxy, 5), onion)
        CommunityDojoRepository.fetchWith(CommunityDojoRepository.buildClient(proxy, 5), onion)
        assertEquals(2, seen.size)
        assertEquals("a".repeat(56) + ".onion", seen[0].host)
        assertEquals(80, seen[0].port)
        // Fresh SOCKS credentials per attempt: Tor's IsolateSOCKSAuth keeps them off each other's circuits.
        assertNotEquals(seen[0].user, seen[1].user)
        assertTrue(seen[0].user.length >= 16)
    }

    @Test
    fun `release closes the attempt's idle connection`() = runBlocking {
        keepAlive = true
        val client = CommunityDojoRepository.buildClient(proxy, 5)
        CommunityDojoRepository.fetchWith(client, onion)
        // Without release, the kept-alive connection (and its Tor stream) stays pooled.
        assertEquals(1, client.connectionPool.connectionCount())
        CommunityDojoRepository.release(client)
        assertEquals(0, client.connectionPool.connectionCount())
        assertTrue(client.dispatcher.executorService.isShutdown)
    }

    @Test
    fun `Tor refusing the stream is unreachable, and gets the retry`() {
        for ((code, text) in listOf(0x06 to "TTL expired", 0x04 to "Host unreachable", 0x01 to "general failure")) {
            replyCode = code
            try {
                runBlocking { CommunityDojoRepository.fetchWith(CommunityDojoRepository.buildClient(proxy, 5), onion) }
                fail("expected a failure for $text")
            } catch (e: CommunityDojoRepository.FetchException) {
                assertEquals(text, Failure.UNREACHABLE, e.failure)
                assertTrue(CommunityDojoFetchPolicy.shouldRetry(e.failure, 1))
            }
        }
    }

    @Test
    fun `the final message after a retryable failure says two tries were made`() {
        assertTrue(CommunityDojoFetchPolicy.failedAfterRetry(Failure.TIMEOUT, 2))
        assertTrue(CommunityDojoFetchPolicy.failedAfterRetry(Failure.UNREACHABLE, 2))
        // A single attempt never claims two tries, whatever the failure.
        assertEquals(false, CommunityDojoFetchPolicy.failedAfterRetry(Failure.TIMEOUT, 1))
        assertEquals(false, CommunityDojoFetchPolicy.failedAfterRetry(Failure.UNREACHABLE, 1))
        for (f in listOf(Failure.HTTP_ERROR, Failure.BAD_RESPONSE, Failure.TOO_LARGE, Failure.TOR_UNAVAILABLE)) {
            assertEquals(f.name, false, CommunityDojoFetchPolicy.failedAfterRetry(f, 2))
        }
    }
}
