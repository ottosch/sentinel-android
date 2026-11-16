package com.samourai.sentinel.ui.dojo

import com.samourai.sentinel.ui.dojo.CommunityDojoDisplay.SavedListBanner
import com.samourai.sentinel.ui.dojo.CommunityDojoRepository.Failure
import com.samourai.sentinel.ui.dojo.CommunityDojoRepository.FetchException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * operator.json 404: the saved list stays up with no age limit, under its own
 * banner with the saved date, and the served list is not parsed.
 */
class CommunityDojoNoOperatorStatementTest {

    private lateinit var server: MockWebServer
    private val alice = DojoBayTestVectors.ThrowawayPayNym(31)
    private val def = DojoBayConstants.BASE_URL
    private val defCode = DojoBayConstants.DEFAULT_OPERATOR_CODE

    private val signedTestnet = CommunityDojoNode(
        id = "t1", network = "testnet", name = "signed", status = "active",
        payload = DojoBayTestVectors.testPayload(), signed = alice.listing(DojoBayTestVectors.TEST_PAIRING_JSON),
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client() = OkHttpClient.Builder().followRedirects(false).callTimeout(5, TimeUnit.SECONDS).build()

    private fun serve(dojos: MockResponse, operator: MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/data/dojos.json" -> dojos
                "/data/operator.json" -> operator
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private fun fetchFailure(): Failure = try {
        runBlocking { CommunityDojoRepository.fetchBoth(client(), server.url("/").toString()) }
        fail("expected a failed fetch")
        throw AssertionError()
    } catch (e: FetchException) {
        e.failure
    }

    @Test
    fun `a 404 gives the saved list with the no-operator-statement banner and its date`() {
        // The served list is not JSON at all: it is not parsed, so it can't
        // turn the 404 into a bad response.
        serve(MockResponse().setBody("{{{ not a directory"), MockResponse().setResponseCode(404))
        val failure = fetchFailure()
        assertEquals(Failure.NO_OPERATOR_STATEMENT, failure)
        val savedAt = 1_731_700_000_000L
        val entry = CommunityDojoCache.Entry(def, savedAt, listOf(signedTestnet), defCode)
        val d = CommunityDojoDisplay.afterFailure(failure, entry, def, null, "testnet") as CommunityDojoDisplay.Cached
        assertEquals(SavedListBanner.NO_OPERATOR_STATEMENT, d.banner)
        assertEquals(savedAt, d.fetchedAtMs)
        assertEquals(listOf("t1"), d.dojos.map { it.node.id })
        assertTrue(d.dojos.single().verified != null)
    }

    @Test
    fun `there is no age limit - a list saved years ago is still shown`() {
        for (savedAt in listOf(1L, System.currentTimeMillis() - 5L * 365 * 24 * 3600 * 1000)) {
            val entry = CommunityDojoCache.Entry(def, savedAt, listOf(signedTestnet), defCode)
            val d = CommunityDojoDisplay.afterFailure(Failure.NO_OPERATOR_STATEMENT, entry, def, null, "testnet")
                as CommunityDojoDisplay.Cached
            assertEquals(SavedListBanner.NO_OPERATOR_STATEMENT, d.banner)
            assertEquals(savedAt, d.fetchedAtMs)
        }
    }

    @Test
    fun `other failures over a saved list keep the unreachable banner`() {
        val entry = CommunityDojoCache.Entry(def, 5L, listOf(signedTestnet), defCode)
        for (failure in Failure.values().filter { it != Failure.NO_OPERATOR_STATEMENT }) {
            val d = CommunityDojoDisplay.afterFailure(failure, entry, def, null, "testnet") as CommunityDojoDisplay.Cached
            assertEquals(failure.name, SavedListBanner.UNREACHABLE, d.banner)
        }
    }

    @Test
    fun `a 404 with no saved list is the no-operator-statement failure`() {
        assertEquals(
            CommunityDojoDisplay.Failed(Failure.NO_OPERATOR_STATEMENT),
            CommunityDojoDisplay.afterFailure(Failure.NO_OPERATOR_STATEMENT, null, def, null, "testnet"),
        )
    }

    @Test
    fun `a saved list from another operator is not shown on a 404`() {
        val entry = CommunityDojoCache.Entry(def, 5L, listOf(signedTestnet), alice.code)
        assertEquals(
            CommunityDojoDisplay.Failed(Failure.NO_OPERATOR_STATEMENT),
            CommunityDojoDisplay.afterFailure(Failure.NO_OPERATOR_STATEMENT, entry, def, null, "testnet"),
        )
    }

    @Test
    fun `the banner text leads with the saved date and says why it can't be re-checked`() {
        val path = "res/values/strings.xml"
        val xml = listOf(File("src/main/$path"), File("app/src/main/$path")).first { it.isFile }.readText()
        val line = xml.lines().single { it.contains("name=\"community_dojo_cached_no_operator\"") }
        assertTrue(line, line.contains(">Saved list from %1\$s · Dojo Bay has no operator statement, so it can\\'t be re-checked<"))
    }
}
