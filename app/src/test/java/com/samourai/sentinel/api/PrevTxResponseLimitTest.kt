package com.samourai.sentinel.api

import com.samourai.sentinel.api.okHttp.await
import com.samourai.sentinel.util.ImportLimits
import com.samourai.sentinel.util.ImportTooLargeException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * #503. Previous-transaction bodies are buffered inside [await], before
 * sweep or compose can reject them. The cap has to trip while the
 * socket is still streaming, and it must not apply to every other Dojo
 * call that shares [await].
 */
class PrevTxResponseLimitTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun streamedBodyPastTheCeilingIsRejectedBeforeTheStreamEnds() {
        val limit = ImportLimits.PREV_TX_RESPONSE_MAX_BYTES
        // More than the ceiling, then a stall instead of a terminal chunk.
        // A reader that buffers the whole body waits here until its read
        // timeout. A reader that counts while it goes throws at the ceiling.
        val stall = StallingChunkServer(limit.toLong() + 64 * 1024)
        stall.start()
        val client = cappedClient(readTimeoutSeconds = 8)
        val started = System.nanoTime()
        try {
            try {
                runBlocking {
                    client.newCall(url(stall.port, "/tx/abc/HEX"))
                        .await(maxBodyBytes = limit)
                }
                fail("oversized prev-tx body should be refused")
            } catch (e: ImportTooLargeException) {
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                assertEquals(limit.toLong(), e.limit)
                assertEquals("bytes", e.unit)
                assertTrue(
                    "stopped in ${elapsedMs}ms; a full read would wait out the timeout",
                    elapsedMs < 3_000,
                )
                assertTrue(
                    "server must have crossed the ceiling, sent ${stall.payloadSent.get()}",
                    stall.payloadSent.get() > limit,
                )
                assertFalse(
                    "the chunk stream had not ended",
                    stall.wroteTerminalChunk.get(),
                )
            }
            assertNoLeasedConnections(client)
        } finally {
            client.release()
            stall.close()
        }
    }

    @Test
    fun bodyInsideTheCeilingStaysReadableAndReleasesTheSocket() {
        val envelope = """{"status":"ok","data":"51"}"""
        server.enqueue(MockResponse().setBody(envelope))
        val client = cappedClient(readTimeoutSeconds = 2)
        try {
            val response = runBlocking {
                client.newCall(request("/tx/abc/HEX"))
                    .await(maxBodyBytes = ImportLimits.PREV_TX_RESPONSE_MAX_BYTES)
            }
            assertNoLeasedConnections(client)
            assertEquals(envelope, response.body!!.string())
            assertNoLeasedConnections(client)
        } finally {
            client.release()
        }
    }

    @Test
    fun chunkedBodyExactlyAtTheCapIsAccepted() {
        val cap = 32
        val payload = "a".repeat(cap)
        server.enqueue(MockResponse().setChunkedBody(Buffer().writeUtf8(payload), 8))
        val client = cappedClient(readTimeoutSeconds = 2)
        try {
            val response = runBlocking {
                client.newCall(request("/tx/abc/HEX")).await(maxBodyBytes = cap)
            }
            assertEquals(payload, response.body!!.string())
            assertNoLeasedConnections(client)
        } finally {
            client.release()
        }
    }

    @Test
    fun uncappedAwaitStillReadsPastThePrevTxCeiling() {
        // This call does not pass a cap. /wallet and /txs pass one at
        // their call sites. A body one byte over the prev-tx ceiling
        // must still come through an uncapped await().
        val size = ImportLimits.PREV_TX_RESPONSE_MAX_BYTES + 1
        server.enqueue(MockResponse().setBody("b".repeat(size)))
        val client = cappedClient(readTimeoutSeconds = 8)
        try {
            val response = runBlocking {
                client.newCall(request("/wallet")).await()
            }
            assertEquals(size, response.body!!.string().length)
            assertNoLeasedConnections(client)
        } finally {
            client.release()
        }
    }

    private fun cappedClient(readTimeoutSeconds: Long): OkHttpClient {
        return OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(readTimeoutSeconds + 2, TimeUnit.SECONDS)
            .build()
    }

    private fun request(path: String): Request =
        Request.Builder().url(server.url(path)).build()

    private fun url(port: Int, path: String): Request =
        Request.Builder().url("http://127.0.0.1:$port$path").build()

    private fun assertNoLeasedConnections(client: OkHttpClient) {
        val pool = client.connectionPool
        assertEquals(
            "leased connections: total=${pool.connectionCount()} idle=${pool.idleConnectionCount()}",
            pool.connectionCount(),
            pool.idleConnectionCount(),
        )
    }

    private fun OkHttpClient.release() {
        dispatcher.executorService.shutdown()
        connectionPool.evictAll()
    }
}

/**
 * Chunked HTTP/1.1 body of [sendBytes] payload bytes, then silence.
 * No terminal chunk, so a reader that waits for the end of the body
 * does not return.
 */
internal class StallingChunkServer(private val sendBytes: Long) : AutoCloseable {
    val payloadSent = AtomicLong()
    val wroteTerminalChunk = AtomicBoolean(false)
    private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val port: Int = socket.localPort
    private val thread = Thread(this::serve, "prev-tx-stall")

    fun start() {
        thread.isDaemon = true
        thread.start()
    }

    private fun serve() {
        try {
            socket.accept().use { client ->
                client.tcpNoDelay = true
                client.soTimeout = 15_000
                readHeaders(client.getInputStream())
                val out = client.getOutputStream()
                out.write(
                    ("HTTP/1.1 200 OK\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "Content-Type: application/json\r\n" +
                        "\r\n").toByteArray(Charsets.US_ASCII)
                )
                out.flush()
                val chunk = ByteArray(1024) { 'a'.code.toByte() }
                var sent = 0L
                while (sent < sendBytes) {
                    val n = minOf(chunk.size.toLong(), sendBytes - sent).toInt()
                    out.write((n.toString(16) + "\r\n").toByteArray(Charsets.US_ASCII))
                    out.write(chunk, 0, n)
                    out.write("\r\n".toByteArray(Charsets.US_ASCII))
                    out.flush()
                    sent += n
                    payloadSent.set(sent)
                }
                // Hold the tail open. A client that stops at the ceiling
                // closes; a client that wants the rest blocks in this read.
                // No terminal chunk. wroteTerminalChunk stays false.
                try {
                    client.getInputStream().read()
                } catch (_: IOException) {
                }
            }
        } catch (_: IOException) {
            // The client closes the socket once the ceiling is crossed.
        }
    }

    private fun readHeaders(input: InputStream) {
        val needle = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
        var matched = 0
        var seen = 0
        while (matched < needle.size) {
            val b = input.read()
            if (b < 0) throw IOException("eof before headers")
            seen++
            if (seen > 16 * 1024) throw IOException("headers too long")
            val expected = needle[matched].toInt() and 0xff
            val first = needle[0].toInt() and 0xff
            matched = if (b == expected) {
                matched + 1
            } else if (b == first) {
                1
            } else {
                0
            }
        }
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
        }
        thread.join(2_000)
    }
}
