package com.samourai.sentinel.ui.dojo

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.coroutines.CoroutineContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * The PayNym avatar and +name lookup: the fetcher's redirect, status, PNG and
 * size rules on a fake transport, the name's code match, no request at all
 * without Tor, and the 7-day cache. Codes are constructed strings of the
 * right shape; nothing reaches the network.
 */
class PayNymLookupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val code = "PM8TJUJHkfdjkb5g5XaJuvnC63rUz7LXHnVqsnCd17dhDRupn9gW3QrgJJdNNGxsR4LSe4kFBMwarLbJVRQur9ZQfY4rtTHWWgdjLeE1svskeNoCEoum"
    private val avatarPath = "/$code/avatar"
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)

    private class FakeTransport(private val replies: (method: String, path: String, body: String?) -> PayNymReply?) : PayNymTransport {
        val requests = mutableListOf<Triple<String, String, String?>>()
        var closed = false
        override suspend fun get(path: String, maxBytes: Int): PayNymReply? {
            requests.add(Triple("GET", path, null))
            return replies("GET", path, null)
        }
        override suspend fun post(path: String, json: String, maxBytes: Int): PayNymReply? {
            requests.add(Triple("POST", path, json))
            return replies("POST", path, json)
        }
        override fun close() {
            closed = true
        }
    }

    private fun nymDoc(name: String = "+examplenym", codes: List<String> = listOf(code)) =
        """{"nymID":"x","nymName":"$name","codes":[${codes.joinToString(",") { """{"claimed":true,"segwit":true,"code":"$it"}""" }}]}"""

    private fun ok(body: ByteArray) = PayNymReply(200, null, body)
    private fun redirect(to: String?, status: Int = 302) = PayNymReply(status, to, ByteArray(0))

    private fun avatarWith(vararg replies: PayNymReply?): Pair<ByteArray?, FakeTransport> {
        var i = 0
        val t = FakeTransport { _, _, _ -> replies.getOrNull(i++) }
        return runBlocking { PayNymFetcher(t).avatar(code) } to t
    }

    // ---- avatar ----------------------------------------------------------------

    @Test
    fun `a 200 PNG is the avatar, fetched from the signed code's path`() {
        val (bytes, t) = avatarWith(ok(png))
        assertArrayEquals(png, bytes)
        assertEquals(listOf(Triple("GET", avatarPath, null)), t.requests)
    }

    @Test
    fun `one same-host redirect is followed, relative or absolute`() {
        for (to in listOf("/static/a.png", "a.png", "http://${PayNymLinks.ONION_HOST}/static/a.png", "http://${PayNymLinks.ONION_HOST}:80/x?s=1")) {
            val (bytes, t) = avatarWith(redirect(to), ok(png))
            assertArrayEquals(to, png, bytes)
            assertEquals(2, t.requests.size)
        }
        val (_, t) = avatarWith(redirect("a.png", 301), ok(png))
        assertEquals("/$code/a.png", t.requests[1].second)
        for (status in listOf(301, 302, 307, 308)) assertArrayEquals(png, avatarWith(redirect("/b", status), ok(png)).first)
    }

    @Test
    fun `a second redirect, another host, https, another port or no location is no avatar`() {
        assertNull(avatarWith(redirect("/a"), redirect("/b"), ok(png)).first)
        for (to in listOf(
            "http://example.com/a.png", "https://${PayNymLinks.ONION_HOST}/a.png", "http://${PayNymLinks.ONION_HOST}:8080/a.png",
            "http://evil${PayNymLinks.ONION_HOST}/a.png", "//example.com/a.png", null, "",
        )) {
            val (bytes, t) = avatarWith(redirect(to), ok(png))
            assertNull(to, bytes)
            assertEquals(to, 1, t.requests.size)
        }
    }

    @Test
    fun `only 200 counts, and the body must start with the PNG signature`() {
        for (status in listOf(201, 204, 304, 400, 404, 500)) assertNull(avatarWith(PayNymReply(status, null, png)).first)
        assertNull(avatarWith(ok("GIF89a......".toByteArray())).first)
        assertNull(avatarWith(ok(png.copyOf(8))).first)
        assertNull(avatarWith(ok(ByteArray(0))).first)
        assertNull(avatarWith(PayNymReply(200, null, null)).first)
        assertNull(avatarWith(null).first)
    }

    @Test
    fun `the avatar is capped at 2 MiB`() {
        val max = png + ByteArray(PayNymLookup.MAX_AVATAR_BYTES - png.size)
        assertArrayEquals(max, avatarWith(ok(max)).first)
        assertNull(avatarWith(ok(max + byteArrayOf(0))).first)
        assertEquals(2 * 1024 * 1024, PayNymLookup.MAX_AVATAR_BYTES)
        assertEquals(25L, PayNymLookup.TIMEOUT_SECONDS)
    }

    @Test
    fun `nothing is asked for a string that is not a payment code`() {
        val t = FakeTransport { _, _, _ -> ok(png) }
        runBlocking {
            assertNull(PayNymFetcher(t).avatar("+examplenym"))
            assertNull(PayNymFetcher(t).avatar("$code/../x"))
            assertNull(PayNymFetcher(t).name("not a code"))
        }
        assertTrue(t.requests.isEmpty())
    }

    // ---- name ----------------------------------------------------------------------

    @Test
    fun `the name is asked with the signed code and shown only when the answer lists that code`() {
        val t = FakeTransport { _, _, _ -> ok(nymDoc().toByteArray()) }
        assertEquals("+examplenym", runBlocking { PayNymFetcher(t).name(code) })
        assertEquals(Triple("POST", "/api/v1/nym", """{"nym":"$code"}"""), t.requests.single())

        fun nameFrom(body: String, status: Int = 200) =
            runBlocking { PayNymFetcher(FakeTransport { _, _, _ -> PayNymReply(status, null, body.toByteArray()) }).name(code) }
        assertEquals("+examplenym", nameFrom(nymDoc(name = "examplenym")))
        assertEquals("+examplenym", nameFrom(nymDoc(codes = listOf("PM8Tother", code))))
        assertNull(nameFrom(nymDoc(codes = listOf("PM8Tother"))))
        assertNull(nameFrom("""{"nymName":"+examplenym"}"""))
        assertNull(nameFrom(nymDoc(), status = 404))
        assertNull(nameFrom(nymDoc(name = "+exa mple")))
        assertNull(nameFrom(nymDoc(name = "+<b>x</b>")))
        assertNull(nameFrom(nymDoc(name = "+" + "a".repeat(65))))
        assertNull(nameFrom("""{"nymName":NULL,"codes":[{"code":"$code"}]}"""))
        assertNull(nameFrom("\uFEFF" + nymDoc()))
        assertNull(nameFrom("<html>"))
    }

    @Test
    fun `a busy nym's answer over 64 KiB still gives its name, through the real transport cap`() {
        // A nym with a few hundred followers: the answer lists them all
        // (+max's was 65,763 bytes in Nov 2026).
        val follower = """{"code":"PM8T${"x".repeat(112)}","nymId":"nymABCDEFGHIJKLMNOPQRSTUV","nymName":"+somefollower00","segwit":true}"""
        val followers = (1..400).joinToString(",") { follower }
        val body = """{"nymName":"+examplenym","nymID":"x","codes":[{"claimed":true,"code":"$code","segwit":true}],"followers":[$followers],"following":[]}"""
        assertTrue(body.length > 64 * 1024)
        assertTrue(body.length < PayNymLookup.MAX_NYM_BYTES)
        val server = MockWebServer()
        server.start()
        try {
            val client = OkHttpClient.Builder().followRedirects(false).callTimeout(5, TimeUnit.SECONDS).build()
            val t = OkHttpPayNymTransport(client, server.url("/").toString().toHttpUrl())
            server.enqueue(MockResponse().setBody(body))
            assertEquals("+examplenym", runBlocking { PayNymFetcher(t).name(code) })
            t.close()
        } finally {
            server.shutdown()
        }
    }

    /** Holds dispatched work until the test runs it, so it shows exactly what runs there. */
    private class HeldDispatcher : CoroutineDispatcher() {
        val held = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            held.addLast(block)
        }
    }

    @Test
    fun `the nym answer is decoded and parsed on the parse dispatcher, not the caller's thread`() {
        val parse = HeldDispatcher()
        val t = FakeTransport { _, _, _ -> ok(nymDoc().toByteArray()) }
        runBlocking {
            val name = async(start = CoroutineStart.UNDISPATCHED) { PayNymFetcher(t, parse).name(code) }
            yield()
            // The reply is in, but nothing is parsed until the parse dispatcher runs it.
            assertEquals(1, t.requests.size)
            assertFalse(name.isCompleted)
            assertEquals(1, parse.held.size)
            parse.held.removeFirst().run()
            assertEquals("+examplenym", name.await())
            assertTrue(parse.held.isEmpty())
        }
    }

    @Test
    fun `the default parse dispatcher is Dispatchers Default`() {
        val f = PayNymFetcher::class.java.getDeclaredField("parseDispatcher").apply { isAccessible = true }
        assertTrue(f.get(PayNymFetcher(FakeTransport { _, _, _ -> null })) === Dispatchers.Default)
    }

    // ---- the lookup: Tor only, cached ------------------------------------------------

    private val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", 9050))

    private fun serving(name: String? = "+examplenym", avatar: ByteArray? = png) = FakeTransport { method, _, _ ->
        if (method == "GET") avatar?.let { ok(it) } else name?.let { ok(nymDoc(it).toByteArray()) }
    }

    @Test
    fun `without Tor nothing is fetched`() {
        var transports = 0
        val lookup = PayNymLookup(PayNymCache(tmp.root), torProxy = { null }, transportFor = { transports++; serving() })
        assertEquals(PayNymLookup.Result(null, null), runBlocking { lookup.load(code) })
        assertEquals(0, transports)
    }

    @Test
    fun `a lookup fetches both, closes its transport, and the next one comes from the cache`() {
        var now = 1_000_000L
        val cache = PayNymCache(tmp.root) { now }
        val made = mutableListOf<FakeTransport>()
        val lookup = PayNymLookup(cache, torProxy = { proxy }, transportFor = { serving().also { made.add(it) } })

        val first = runBlocking { lookup.load(code) }
        assertArrayEquals(png, first.avatarPng)
        assertEquals("+examplenym", first.name)
        assertEquals(1, made.size)
        assertEquals(2, made[0].requests.size)
        assertTrue(made[0].closed)

        now += PayNymLookup.CACHE_MAX_AGE_MS - 1
        assertEquals(first, runBlocking { lookup.load(code) })
        assertEquals(1, made.size)

        // After 7 days it is asked again.
        now += 2
        runBlocking { lookup.load(code) }
        assertEquals(2, made.size)
        assertEquals(7L * 24 * 60 * 60 * 1000, PayNymLookup.CACHE_MAX_AGE_MS)
    }

    @Test
    fun `failures are not cached, and a cached half is not asked again`() {
        val cache = PayNymCache(tmp.root)
        val made = mutableListOf<FakeTransport>()
        val failing = PayNymLookup(cache, torProxy = { proxy }, transportFor = { serving(name = null, avatar = null).also { made.add(it) } })
        assertEquals(PayNymLookup.Result(null, null), runBlocking { failing.load(code) })
        assertNull(cache.avatar(code))
        assertNull(cache.name(code))

        cache.putAvatar(code, png)
        val nameOnly = PayNymLookup(cache, torProxy = { proxy }, transportFor = { serving().also { made.add(it) } })
        val result = runBlocking { nameOnly.load(code) }
        assertArrayEquals(png, result.avatarPng)
        assertEquals("+examplenym", result.name)
        assertEquals(listOf("POST"), made.last().requests.map { it.first })
    }

    @Test
    fun `View on PayNym's name comes from the cache, else one name request over Tor`() {
        val cache = PayNymCache(tmp.root)
        val made = mutableListOf<FakeTransport>()
        val lookup = PayNymLookup(cache, torProxy = { proxy }, transportFor = { serving().also { made.add(it) } })

        assertEquals("+examplenym", runBlocking { lookup.name(code) })
        assertEquals(listOf("POST"), made.single().requests.map { it.first })
        assertTrue(made.single().closed)
        assertEquals("+examplenym", cache.name(code))
        // Cached now: nothing is asked again.
        assertEquals("+examplenym", runBlocking { lookup.name(code) })
        assertEquals(1, made.size)
        assertEquals(PayNymLinks.BASE_URL + "/+examplenym", runBlocking { PayNymPage.urlForSigner(lookup, code) })
    }

    @Test
    fun `no name, no Tor or no code gives no PayNym page`() {
        val noName = PayNymLookup(PayNymCache(tmp.newFolder()), torProxy = { proxy }, transportFor = { serving(name = null) })
        assertNull(runBlocking { noName.name(code) })
        assertNull(runBlocking { PayNymPage.urlForSigner(noName, code) })
        // A nym that doesn't list the signed code is no name either.
        val otherCode = PayNymLookup(PayNymCache(tmp.newFolder()), torProxy = { proxy }, transportFor = {
            FakeTransport { _, _, _ -> ok(nymDoc(codes = listOf("PM8TJ" + "x".repeat(111))).toByteArray()) }
        })
        assertNull(runBlocking { otherCode.name(code) })
        var transports = 0
        val noTor = PayNymLookup(PayNymCache(tmp.newFolder()), torProxy = { null }, transportFor = { transports++; serving() })
        assertNull(runBlocking { noTor.name(code) })
        assertNull(runBlocking { noTor.name("+examplenym") })
        assertEquals(0, transports)
    }

    @Test
    fun `the cache keeps only code-named PNGs and names, and Erase clears it`() {
        val cache = PayNymCache(tmp.root)
        cache.putAvatar("../evil", png)
        assertFalse(tmp.root.walk().any { it.name.contains("evil") })
        cache.putAvatar(code, png)
        cache.putName(code, "+examplenym")
        java.io.File(tmp.root, "$code.png").writeBytes("not a png".toByteArray())
        assertNull(cache.avatar(code))
        assertEquals("+examplenym", cache.name(code))
        cache.clear()
        assertNull(cache.name(code))
    }

    // ---- the OkHttp transport: no redirects followed, capped -----------------------

    @Test
    fun `the OkHttp transport hands redirects back, and stops at the cap`() {
        val server = MockWebServer()
        server.start()
        try {
            val client = OkHttpClient.Builder().followRedirects(false).callTimeout(5, TimeUnit.SECONDS).build()
            val t = OkHttpPayNymTransport(client, server.url("/").toString().toHttpUrl())
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/elsewhere"))
            val reply = runBlocking { t.get(avatarPath, 1024) }!!
            assertEquals(302, reply.status)
            assertEquals("/elsewhere", reply.location)
            assertEquals(avatarPath, server.takeRequest().path)

            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(2048))))
            assertNull(runBlocking { t.get(avatarPath, 1024) })

            server.enqueue(MockResponse().setBody(nymDoc()))
            val nym = runBlocking { t.post("/api/v1/nym", """{"nym":"$code"}""", 64 * 1024) }!!
            assertEquals(200, nym.status)
            val recorded = server.run { takeRequest(); takeRequest() }
            assertEquals("POST", recorded.method)
            assertEquals("""{"nym":"$code"}""", recorded.body.readUtf8())
            assertTrue(recorded.getHeader("Content-Type")!!.startsWith("application/json"))

            // A path that would leave the host is not requested.
            assertNull(runBlocking { t.get("http://example.com/x", 1024) })
            t.close()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `the Tor transport only connects through the SOCKS port`() {
        val t = OkHttpPayNymTransport.overTor(proxy)
        val field = OkHttpPayNymTransport::class.java.getDeclaredField("client").apply { isAccessible = true }
        val client = field.get(t) as OkHttpClient
        assertEquals(Proxy.NO_PROXY, client.proxy)
        assertFalse(client.followRedirects)
        assertEquals(25_000, client.callTimeoutMillis)
        assertTrue(client.socketFactory.javaClass.name.contains("TorIsolatedSocks"))
        t.close()
    }
}
