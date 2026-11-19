package com.samourai.sentinel.ui.dojo

import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The /api/v1/nym answer is streamed, not built into a tree (#597 review
 * L2): a 1 MiB answer used to become up to ~46 MB of Gson tree, and an
 * OutOfMemoryError in that parse wasn't caught, so it killed the process.
 * Big valid answers still give the name, the streamed reading agrees with
 * the tree rule [PayNymFetcher.nymFor], hostile answers give no name, and
 * on a small heap nothing escapes to the uncaught handler.
 */
class PayNymNameStreamTest {

    private val code = "PM8TJUJHkfdjkb5g5XaJuvnC63rUz7LXHnVqsnCd17dhDRupn9gW3QrgJJdNNGxsR4LSe4kFBMwarLbJVRQur9ZQfY4rtTHWWgdjLeE1svskeNoCEoum"

    private class Fixed(private val body: ByteArray) : PayNymTransport {
        override suspend fun get(path: String, maxBytes: Int): PayNymReply? = null
        override suspend fun post(path: String, json: String, maxBytes: Int) = PayNymReply(200, null, body)
    }

    private fun name(body: String) = runBlocking { PayNymFetcher(Fixed(body.toByteArray())).name(code) }

    @Test
    fun `an answer the size of +max's (65,763 bytes) gives the name`() {
        val body = PayNymAnswers.sized(PayNymAnswers.REALISTIC_FOLLOWER, 65_763, code)
        assertEquals(65_763, body.toByteArray().size)
        assertEquals("+victimnym", name(body))
    }

    @Test
    fun `answers just under the 1 MiB cap give the name, in every shape`() {
        val size = PayNymLookup.MAX_NYM_BYTES - 64
        for (unit in listOf(PayNymAnswers.REALISTIC_FOLLOWER, "{}", "0", "[]", "[" .repeat(29) + "]".repeat(29))) {
            val body = PayNymAnswers.sized(unit, size, code)
            assertTrue(body.toByteArray().size <= PayNymLookup.MAX_NYM_BYTES)
            assertEquals(unit.take(20), "+victimnym", name(body))
        }
    }

    @Test
    fun `the streamed reading agrees with the tree rule`() {
        val docs = listOf(
            """{"nymName":"+a","codes":[{"code":"$code"}]}""",
            """{"nymName":"a","codes":[{"code":"PM8Tother"},{"code":"$code"}]}""",
            """{"nymName":"+a","codes":[{"code":"PM8Tother"}]}""",
            """{"nymName":"+a"}""",
            """{"codes":[{"code":"$code"}],"nymName":"+a"}""",
            // A repeated key counts by its last value.
            """{"nymName":"+first","nymName":"+last","codes":[{"code":"$code"}]}""",
            """{"nymName":"+a","codes":[{"code":"$code"}],"codes":[]}""",
            """{"nymName":"+a","codes":[],"codes":[{"code":"$code"}]}""",
            """{"nymName":"+a","nymName":5,"nym":"+b","codes":[{"code":"$code"}]}""",
            """{"nymName":"+a","codes":[{"code":"$code","code":"x"}]}""",
            // Names that aren't strings, and the fallbacks.
            """{"nymName":null,"nym_name":"+b","codes":[{"code":"$code"}]}""",
            """{"nymName":{"x":1},"nym":"c","codes":[{"code":"$code"}]}""",
            """{"nymName":["+a"],"codes":[{"code":"$code"}]}""",
            // codes that aren't an array, items that aren't objects, codes that aren't strings.
            """{"nymName":"+a","codes":{"code":"$code"}}""",
            """{"nymName":"+a","codes":["$code",1,null,[]]}""",
            """{"nymName":"+a","codes":[{"code":{"v":"$code"}},{"code":null},{"code":true}]}""",
            // Bad names.
            """{"nymName":"+a b","codes":[{"code":"$code"}]}""",
            """{"nymName":"+${"a".repeat(65)}","codes":[{"code":"$code"}]}""",
            "[]", "\"x\"", "1", "null",
        )
        for (d in docs) {
            val tree = DojoBayJson.parseTree(d)
            val expected = (tree as? JsonObject)?.let { PayNymFetcher.nymFor(it, code) }
            assertEquals(d, expected, PayNymFetcher.nymFromAnswer(d, code))
        }
    }

    @Test
    fun `hostile or malformed answers give no name and don't throw`() {
        val good = """{"nymName":"+a","codes":[{"code":"$code"}]"""
        val hostile = listOf(
            // Nesting over the limit, deep enough to recurse if it were followed.
            """{"nymName":"+a","codes":[{"code":"$code"}],"x":""" + "[".repeat(500_000) + "]".repeat(500_000) + "}",
            """{"nymName":"+a","codes":[{"code":"$code"}],"x":""" + "[".repeat(40) + "]".repeat(40) + "}",
            // Truncated, trailing data, lenient syntax, a byte-order mark.
            good,
            "$good}{}",
            "$good,'x':1}",
            """{nymName:"+a","codes":[{"code":"$code"}]}""",
            """{"nymName":"+a","codes":[{"code":"$code"}]} // c""",
            "\uFEFF$good}",
            "",
            "<html>",
        )
        for (h in hostile) assertNull(h.take(40), name(h))
        // DojoBayJson turns an Error in the reading into "nothing" too.
        assertNull(DojoBayJson.stream<String>("{}") { throw OutOfMemoryError("test") })
        assertNull(DojoBayJson.stream<String>("{}") { throw StackOverflowError() })
    }

    private fun probe(xmx: String, unit: String): Pair<Int, String> {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val p = ProcessBuilder(
            java, "-Xmx$xmx", "-XX:+UseSerialGC", "-cp", System.getProperty("java.class.path"),
            PayNymSmallHeapProbe::class.java.name, unit,
        ).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        assertTrue("probe timed out", p.waitFor(120, TimeUnit.SECONDS))
        return p.exitValue() to text.lines().filter { it.startsWith("PROBE") }.joinToString(" | ")
    }

    @Test
    fun `a 1 MiB answer on a 40 MB heap gives the name and nothing reaches the uncaught handler`() {
        // The review's PoC: with the tree parse, [{},{},...] at 1 MiB on
        // -Xmx40m reached the uncaught handler and killed the process.
        for (unit in listOf("{}", "0")) {
            val (exit, out) = probe("40m", unit)
            assertEquals(out, 0, exit)
            assertTrue(out, out.contains("PROBE name=+victimnym"))
        }
    }
}

/** Builds /api/v1/nym answers of an exact byte size, padded with one repeated unit. */
object PayNymAnswers {
    const val REALISTIC_FOLLOWER =
        """{"code":"PM8Txxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx","nymId":"nymABCDEFGHIJKLMNOPQRSTUV","nymName":"+somefollower00","segwit":true}"""

    /**
     * {"nymName":"+victimnym",...,"codes":[...code...],"followers":[unit,unit,...],"following":[]}
     * of exactly [size] bytes (units are ASCII; the last bytes are spaces).
     */
    fun sized(unit: String, size: Int, code: String): String {
        val head = """{"nymName":"+victimnym","nymID":"x","codes":[{"claimed":true,"code":"$code","segwit":true}],"followers":["""
        val tail = """],"following":[]}"""
        val sb = StringBuilder(size)
        sb.append(head)
        var first = true
        while (sb.length + (if (first) 0 else 1) + unit.length + tail.length <= size) {
            if (!first) sb.append(',')
            sb.append(unit)
            first = false
        }
        sb.append(tail)
        while (sb.length < size) sb.insert(sb.length - 1, ' ')
        return sb.toString()
    }
}

/** Child-JVM probe: the dialog's launch shape (no CoroutineExceptionHandler) with a 1 MiB answer. */
object PayNymSmallHeapProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        Thread.setDefaultUncaughtExceptionHandler { th, e ->
            println("PROBE UNCAUGHT on ${th.name}: ${e.javaClass.name}")
            System.out.flush()
            Runtime.getRuntime().halt(3)
        }
        val code = "PM8TJUJHkfdjkb5g5XaJuvnC63rUz7LXHnVqsnCd17dhDRupn9gW3QrgJJdNNGxsR4LSe4kFBMwarLbJVRQur9ZQfY4rtTHWWgdjLeE1svskeNoCEoum"
        val body = PayNymAnswers.sized(args[0], PayNymLookup.MAX_NYM_BYTES - 64, code).toByteArray()
        val scope = CoroutineScope(Job())
        val job = scope.launch {
            val transport = object : PayNymTransport {
                override suspend fun get(path: String, maxBytes: Int): PayNymReply? = null
                override suspend fun post(path: String, json: String, maxBytes: Int) = PayNymReply(200, null, body)
            }
            println("PROBE name=${PayNymFetcher(transport).name(code)}")
        }
        runBlocking { job.join() }
        println("PROBE done")
        System.out.flush()
    }
}
