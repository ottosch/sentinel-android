package com.samourai.sentinel.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * An HTTP response from the Dojo is contact. A socket failure is not.
 * The transport label is a short kind, never the exception text.
 */
class DojoReachabilityTest {

    @Before
    fun reset() {
        DojoReachability.reset()
    }

    @After
    fun clear() {
        DojoReachability.reset()
    }

    @Test
    fun httpResponseIsContactAndIoFailureIsUnreachable() {
        DojoReachability.recordHttp(200, errorEnvelope = false, now = 10L)
        assertEquals(DojoContact.Reachable(10L), DojoReachability.current)

        DojoReachability.recordHttp(500, errorEnvelope = false, now = 11L)
        assertEquals(DojoContact.DojoError(11L, 500, "http"), DojoReachability.current)

        DojoReachability.recordHttp(401, errorEnvelope = false, now = 12L)
        assertEquals(DojoContact.DojoError(12L, 401, "auth"), DojoReachability.current)

        DojoReachability.recordHttp(403, errorEnvelope = true, now = 13L)
        assertEquals(DojoContact.DojoError(13L, 403, "auth"), DojoReachability.current)

        DojoReachability.recordHttp(200, errorEnvelope = true, now = 14L)
        assertEquals(DojoContact.DojoError(14L, 200, "envelope"), DojoReachability.current)

        val socks = SocketException("SOCKS: TTL expired")
        DojoReachability.recordTransport(socks, now = 15L)
        val unreachable = DojoReachability.current as DojoContact.Unreachable
        assertEquals(15L, unreachable.atMillis)
        assertEquals("socks", unreachable.transport)
        assertFalse(unreachable.transport.contains("TTL"))
        assertFalse(unreachable.transport.contains("SOCKS"))

        DojoReachability.recordTransport(SocketTimeoutException("read timed out"), now = 16L)
        assertEquals(DojoContact.Unreachable(16L, "timeout"), DojoReachability.current)

        DojoReachability.recordTransport(IOException("connection reset"), now = 17L)
        assertEquals(DojoContact.Unreachable(17L, "io"), DojoReachability.current)
    }

    @Test
    fun aCanceledCallDoesNotReplaceTheLastContact() {
        DojoReachability.recordHttp(200, errorEnvelope = false, now = 10L)
        DojoReachability.recordTransport(IOException("Canceled"), now = 99L)
        assertEquals(DojoContact.Reachable(10L), DojoReachability.current)

        DojoReachability.reset()
        DojoReachability.recordTransport(IOException("Canceled"), now = 99L)
        assertEquals(DojoContact.Unknown, DojoReachability.current)
    }

    @Test
    fun resetReturnsToUnknown() {
        DojoReachability.recordHttp(200, errorEnvelope = false, now = 1L)
        DojoReachability.reset()
        assertEquals(DojoContact.Unknown, DojoReachability.current)
    }

    @Test
    fun errorEnvelopeIsOnlyTheStatusFieldAtTheHead() {
        assertTrue(dojoErrorEnvelope("""{"status":"error","error":{"message":"no"}}"""))
        assertTrue(dojoErrorEnvelope("""  {"status": "error"}"""))
        assertFalse(dojoErrorEnvelope("""{"status":"ok"}"""))
        assertFalse(dojoErrorEnvelope("not json"))
        val buried = """{"wallet":"${"x".repeat(80)}","status":"error"}"""
        assertFalse(dojoErrorEnvelope(buried))
    }

    @Test
    fun callOnRecordsReachabilityAndDoesNotStoreTheExceptionText() {
        val api = source("app/src/main/java/com/samourai/sentinel/api/ApiService.kt")
        val call = api.substringAfter("private suspend fun callOn(")
            .substringBefore("fun authenticateDojo")
        assertTrue(call.contains("DojoReachability.recordTransport(e)"))
        assertTrue(call.contains("DojoReachability.recordHttp(response.code, envelope)"))
        assertTrue(call.contains("catch (e: kotlinx.coroutines.CancellationException)"))
        assertTrue(call.contains("dojoErrorEnvelope(response.peekBody"))
        assertFalse(call.contains("e.message"))
        assertFalse(call.contains("request.url.toString()"))
    }

    private fun source(relative: String): String {
        val file = sequenceOf(File(relative), File(relative.removePrefix("app/")))
            .firstOrNull { it.isFile }
            ?: error("missing $relative from ${File(".").absolutePath}")
        return file.readText()
    }
}
