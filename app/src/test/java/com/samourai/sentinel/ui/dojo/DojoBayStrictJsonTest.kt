package com.samourai.sentinel.ui.dojo

import com.google.gson.Gson
import com.samourai.sentinel.ui.dojo.CommunityDojoRepository.Failure
import com.samourai.sentinel.ui.dojo.CommunityDojoRepository.FetchException
import com.samourai.sentinel.ui.dojo.DojoBaySignatures.NodeCheck
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Everything Dojo Bay sends, and the saved list, is read by our own
 * JsonReader in Strictness.STRICT with a nesting limit of 32 (DojoBayJson):
 * what JSON.parse refuses is refused, deep nesting fails as malformed JSON,
 * and a leading byte-order mark is refused. On an operator.json 404 the list
 * is not parsed at all.
 */
class DojoBayStrictJsonTest {

    @get:Rule
    val tmp = TemporaryFolder()

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

    private fun nested(levels: Int) = "[".repeat(levels) + "]".repeat(levels)

    private val deep = nested(5_000)

    private fun client() = OkHttpClient.Builder()
        .followRedirects(false)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    private val listRequests = mutableListOf<String>()

    private fun serve(dojos: MockResponse, operator: MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/data/dojos.json" -> dojos.also { listRequests.add(request.path!!) }
                "/data/operator.json" -> operator
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private fun failureOf(block: suspend () -> Unit): FetchException {
        try {
            runBlocking { block() }
        } catch (e: FetchException) {
            return e
        } catch (e: Throwable) {
            fail("expected a FetchException, got ${e.javaClass.name}")
        }
        fail("expected a FetchException")
        throw AssertionError()
    }

    private fun badDirectory(body: String) {
        try {
            CommunityDojoRepository.parseDirectory(body)
            fail("expected BAD_RESPONSE")
        } catch (e: FetchException) {
            assertEquals(Failure.BAD_RESPONSE, e.failure)
        }
    }

    // ---- the grammar: JSON.parse, not Gson's LEGACY_STRICT ------------------

    @Test
    fun `accepts what JSON parse accepts`() {
        for (ok in listOf(
            "{}", "[]", "null", "true", "0", "-0.5e+3", "\"\"", """{"a":[1,2,{"b":null}],"c":"\u00e9\n"}""",
            " \t\r\n{\"a\":1} \n", """{"a":"\"\\\/\b\f\n\r\t\u0041"}""", """{"a":1,"a":2}""",
        )) {
            assertNotNull(ok, DojoBayJson.parseTree(ok))
        }
        // Duplicate names keep the last value, like JSON.parse.
        assertEquals(2, DojoBayJson.parseTree("""{"a":1,"a":2}""")!!.asJsonObject["a"].asInt)
    }

    @Test
    fun `refuses NULL, a single-quote escape and raw control characters`() {
        for (bad in listOf(
            """{"explorer":NULL}""", """{"a":TRUE}""", """{"a":False}""",
            """{"x":"a\'b"}""",
            "{\"x\":\"a\u0001b\"}", "{\"x\":\"a\nb\"}", "{\"x\":\"a\tb\"}", "{\"x\":\"a\u001Fb\"}",
        )) {
            assertNull(bad, DojoBayJson.parseTree(bad))
        }
    }

    @Test
    fun `refuses what the shared lenient parser would accept`() {
        for (bad in listOf(
            "{a:1}", "{'a':1}", "{\"a\":'b'}", "{\"a\":1,}", "[1,]", "{\"a\":1;\"b\":2}",
            "// c\n{}", "# c\n{}", "/* c */{}", "{\"a\":NaN}", "{\"a\":Infinity}", "{\"a\":01}",
            "{} {}", "{}x", "", "   ", "{\"a\"=1}", "{\"a\"=>1}",
        )) {
            assertNull(bad, DojoBayJson.parseTree(bad))
        }
    }

    @Test
    fun `refuses a leading byte-order mark`() {
        assertNull(DojoBayJson.parseTree("\uFEFF{}"))
        assertNotNull(DojoBayJson.parseTree("{}"))
        assertNull(DojoBayJson.decodeBody(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), '{'.code.toByte(), '}'.code.toByte())))
        assertNull(DojoBayJson.decodeBody(byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0, '{'.code.toByte())))
        assertNull(DojoBayJson.decodeBody(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), '{'.code.toByte(), 0)))
        assertEquals("{}", DojoBayJson.decodeBody("{}".toByteArray()))
        badDirectory("\uFEFF{\"nodes\":[]}")
    }

    @Test
    fun `a body with a byte-order mark is a bad response, for the list and operator json`() {
        val base = server.url("/").toString()
        val bom = Buffer().write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        serve(MockResponse().setBody("""{"nodes":[]}"""), MockResponse().setBody(bom.clone().writeUtf8("{}")))
        assertEquals(Failure.BAD_RESPONSE, failureOf { CommunityDojoRepository.fetchBoth(client(), base) }.failure)
        serve(MockResponse().setBody(bom.clone().writeUtf8("""{"nodes":[]}""")), MockResponse().setResponseCode(404))
        val listUrl = server.url("/data/dojos.json").toString()
        assertEquals(Failure.BAD_RESPONSE, failureOf { CommunityDojoRepository.fetchWith(client(), listUrl) }.failure)
    }

    // ---- the nesting limit ---------------------------------------------------

    @Test
    fun `nesting is limited to 32 levels`() {
        assertEquals(32, DojoBayJson.NESTING_LIMIT)
        assertNotNull(DojoBayJson.parseTree(nested(32)))
        assertNull(DojoBayJson.parseTree(nested(33)))
        assertNull(DojoBayJson.parseTree("{\"a\":" + nested(32) + "}"))
        val start = System.nanoTime()
        assertNull(DojoBayJson.parseTree(nested(100_000)))
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2_000)
    }

    // ---- signed listing text -------------------------------------------------

    @Test
    fun `signed text with NULL, a single-quote escape or a control character is refused, as Dojo Bay refuses it`() {
        val alice = DojoBayTestVectors.ThrowawayPayNym(11)
        for (text in listOf("""{"explorer":NULL}""", """{"x":"a\'b"}""", "{\"x\":\"a\u0001b\"}", "\uFEFF{}")) {
            val node = CommunityDojoNode(payload = DojoBayTestVectors.testPayload(), signed = alice.listing(text))
            assertEquals(text, NodeCheck.Unverified(DojoBaySignatures.Failure.NOT_A_PAIRING), DojoBaySignatures.checkNode(node))
        }
    }

    @Test
    fun `a signed pairing nested over 32 is refused, 5000 deep without a crash, a normal one verifies`() {
        val alice = DojoBayTestVectors.ThrowawayPayNym(12)
        val over = DojoBayTestVectors.TEST_PAIRING_JSON.dropLast(1) + ",\"x\":${nested(32)}}"
        val node = CommunityDojoNode(payload = DojoBayTestVectors.testPayload(), signed = alice.listing(over))
        assertEquals(NodeCheck.Unverified(DojoBaySignatures.Failure.NOT_A_PAIRING), DojoBaySignatures.checkNode(node))
        val huge = CommunityDojoNode(
            payload = DojoBayTestVectors.testPayload(),
            signed = alice.listing(DojoBayTestVectors.TEST_PAIRING_JSON.dropLast(1) + ",\"x\":$deep}"),
        )
        assertTrue(DojoBaySignatures.checkNode(huge) is NodeCheck.Unverified)
        val normal = CommunityDojoNode(
            payload = DojoBayTestVectors.testPayload(),
            signed = alice.listing(DojoBayTestVectors.TEST_PAIRING_JSON),
        )
        assertTrue(DojoBaySignatures.checkNode(normal) is NodeCheck.Verified)
    }

    // ---- operator.json ---------------------------------------------------------

    @Test
    fun `operator json nested 5000 deep, over 32, or with NULL is a bad response, a real one still verifies`() {
        val base = server.url("/").toString()
        val alice = DojoBayTestVectors.ThrowawayPayNym(14)
        for (body in listOf(deep, """{"onion":$deep}""", """{"onion":NULL}""", "{\"onion\":${nested(32)}}")) {
            serve(MockResponse().setBody("""{"nodes":[]}"""), MockResponse().setBody(body))
            assertEquals(Failure.BAD_RESPONSE, failureOf { CommunityDojoRepository.fetchBoth(client(), base) }.failure)
        }
        serve(MockResponse().setBody("""{"nodes":[]}"""), MockResponse().setBody(Gson().toJson(alice.operatorDoc(base))))
        val dir = runBlocking { CommunityDojoRepository.fetchBoth(client(), base) }
        assertEquals(DojoBaySignatures.OperatorCheck.Verified(alice.code), dir.operator)
    }

    // ---- operator.json 404: the list is not parsed ------------------------------

    @Test
    fun `operator json 404 with a list nested 5000 deep is no operator statement, and the list is not parsed`() {
        val base = server.url("/").toString()
        serve(
            MockResponse().setBody("""{"nodes":[{"id":"a","payload":$deep}]}"""),
            MockResponse().setResponseCode(404),
        )
        val e = failureOf { CommunityDojoRepository.fetchBoth(client(), base) }
        assertEquals(Failure.NO_OPERATOR_STATEMENT, e.failure)
        assertEquals(404, e.httpCode)
        assertEquals(1, listRequests.size)
    }

    @Test
    fun `operator json 404 with a list that is not JSON at all is still no operator statement`() {
        val base = server.url("/").toString()
        serve(MockResponse().setBody("<html>not a directory</html>"), MockResponse().setResponseCode(404))
        assertEquals(Failure.NO_OPERATOR_STATEMENT, failureOf { CommunityDojoRepository.fetchBoth(client(), base) }.failure)
        // The list's own HTTP error still decides when it fails too.
        serve(MockResponse().setResponseCode(404), MockResponse().setResponseCode(404))
        assertEquals(Failure.HTTP_ERROR, failureOf { CommunityDojoRepository.fetchBoth(client(), base) }.failure)
    }

    // ---- dojos.json ---------------------------------------------------------------

    @Test
    fun `a directory nested 5000 deep or over 32 is a bad response, not a crash`() {
        for (body in listOf(
            deep,
            """{"nodes":[{"id":"a","payload":$deep}]}""",
            """{"nodes":[{"id":"a","payload":{"pairing":$deep}}]}""",
            // {"nodes":[{"payload":{"x": is 4 levels before the array.
            """{"nodes":[{"id":"a","payload":{"x":${nested(29)}}}]}""",
        )) {
            badDirectory(body)
        }
        assertEquals(1, CommunityDojoRepository.parseDirectory("""{"nodes":[{"id":"a","payload":{"x":${nested(28)}}}]}""").size)
    }

    @Test
    fun `a directory is read strictly, not with the shared lenient parser`() {
        for (body in listOf(
            """{nodes:[]}""", """{'nodes':[]}""", """{"nodes":[],}""", "// c\n{\"nodes\":[]}",
            """{"nodes":[{"id":"a","name":NULL}]}""", """{"nodes":[{"id":"a\'"}]}""", "{\"nodes\":[{\"id\":\"a\u0001\"}]}",
            """{"nodes":[]} trailing""",
        )) {
            badDirectory(body)
        }
        assertEquals(0, CommunityDojoRepository.parseDirectory("""{"nodes":[]}""").size)
    }

    // ---- the saved list -------------------------------------------------------------

    private fun savedList(nodesJson: String, prefix: String = "") = File(tmp.root, CommunityDojoCache.FILE_NAME).writeText(
        prefix + """{"source":"${DojoBayConstants.BASE_URL}","fetched_at_ms":1,"operator_code":"PM8Tcode",""" +
            """"nodes":$nodesJson}"""
    )

    @Test
    fun `a saved list nested 5000 deep, not strict, or with a BOM reads as no saved list`() {
        val cache = CommunityDojoCache.forDirectory(tmp.root)
        for ((nodes, prefix) in listOf(
            """[{"id":"a","payload":$deep}]""" to "",
            """[{"id":"a","name":NULL}]""" to "",
            """[{"id":"a"}]""" to "\uFEFF",
        )) {
            savedList(nodes, prefix)
            assertNull(nodes.take(40), cache.read(DojoBayConstants.BASE_URL))
        }
        savedList("""[{"id":"a"}]""")
        assertNotNull(cache.read(DojoBayConstants.BASE_URL))
    }

    @Test
    fun `a normal saved list still round-trips`() {
        val cache = CommunityDojoCache.forDirectory(tmp.root)
        val nodes = listOf(CommunityDojoNode(id = "t1", payload = DojoBayTestVectors.testPayload(), signed = "block"))
        cache.write(DojoBayConstants.BASE_URL, nodes, 1L, "PM8Tcode")
        val read = cache.read(DojoBayConstants.BASE_URL)
        assertNotNull(read)
        assertEquals("t1", read!!.nodes.single().id)
    }
}
