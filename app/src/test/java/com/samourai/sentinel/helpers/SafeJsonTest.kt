package com.samourai.sentinel.helpers

import com.samourai.sentinel.util.ImportTooLargeException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * #567. The unit-test classpath loads org.json:json, not Android's
 * org.json. That jar does not enforce a depth cap on JSONObject(String),
 * and a deep document comes back as JSONException (it catches
 * StackOverflowError) or as a parsed value. Either result would let a
 * test go green on code that crashes a device. These tests name
 * [UnsafeJsonException] and its message, and a null cause means the
 * pre-scan rejected the text before org.json ran.
 */
class SafeJsonTest {

    @Test
    fun theUnitTestJarIsNotTheAndroidParser() {
        // org.json:json 20260814 does not apply a 512 cap to JSONObject(String):
        // depth 513 parses. A 10,000-deep array does not throw StackOverflowError
        // to the caller. The jar catches it and rethrows JSONException, with the
        // overflow as the cause. Accepting any JSONException would go green on
        // that, and the same input kills the process on Android's org.json.
        JSONObject(nestedObject(513))
        try {
            org.json.JSONArray(nestedArray(10_000))
            fail("expected the jar to wrap a stack overflow")
        } catch (thrown: JSONException) {
            assertFalse(thrown is UnsafeJsonException)
            assertTrue(thrown.cause is StackOverflowError)
            assertFalse(thrown.message == SafeJson.DEPTH_MESSAGE)
        }
    }

    @Test
    fun depthThirtyTwoParsesAndThirtyThreeIsRefusedBeforeTheParser() {
        val array = SafeJson.parseArray(nestedArray(SafeJson.MAX_JSON_DEPTH))
        var inner = array
        repeat(SafeJson.MAX_JSON_DEPTH - 1) { inner = inner.getJSONArray(0) }
        assertEquals(1, inner.getInt(0))

        val obj = SafeJson.parseObject(nestedObject(SafeJson.MAX_JSON_DEPTH))
        var node = obj
        repeat(SafeJson.MAX_JSON_DEPTH - 1) { node = node.getJSONObject("k") }
        assertEquals(1, node.getInt("k"))

        val arrays = refused(nestedArray(SafeJson.MAX_JSON_DEPTH + 1))
        assertEquals(SafeJson.DEPTH_MESSAGE, arrays.message)
        assertNull(arrays.cause)

        val objects = refusedObject(nestedObject(SafeJson.MAX_JSON_DEPTH + 1))
        assertEquals(SafeJson.DEPTH_MESSAGE, objects.message)
        assertNull(objects.cause)
    }

    @Test
    fun anArrayAtTheDepthLimitIsTheParsersErrorWhenAnObjectIsRequired() {
        val thrown = assertThrowsJson(nestedArray(SafeJson.MAX_JSON_DEPTH)) { text ->
            SafeJson.parseObject(text)
        }
        assertFalse(thrown is UnsafeJsonException)
    }

    @Test
    fun mixedNestingCountsEveryBracket() {
        // Depth 4: array, object, array, object. Under the cap, so the
        // values come back. Asking for an object is the parser's error.
        val parsed = SafeJson.parseArray("""[{"a":[1,{"b":2}]}]""")
        assertEquals(2, parsed.getJSONObject(0).getJSONArray("a").getJSONObject(1).getInt("b"))
        val wrongShape = assertThrowsJson("""[{"a":[1,{"b":2}]}]""") { SafeJson.parseObject(it) }
        assertFalse(wrongShape is UnsafeJsonException)
    }

    @Test
    fun bracketsInsideStringsDoNotCount() {
        val parsed = SafeJson.parseObject("""{"a":"[[[{{{"}""")
        assertEquals("[[[{{{", parsed.getString("a"))
        assertEquals(1, parsed.length())
    }

    @Test
    fun anEscapedQuoteDoesNotEndTheString() {
        val parsed = SafeJson.parseObject("""{"a":"say \"[[[","b":1}""")
        assertEquals("say \"[[[", parsed.getString("a"))
        assertEquals(1, parsed.getInt("b"))
    }

    @Test
    fun aBackslashThenAQuoteDoesEndTheString() {
        val parsed = SafeJson.parseObject("""{"a":"say \\","b":[[[1]]]}""")
        assertEquals("say \\", parsed.getString("a"))
        assertEquals(1, parsed.getJSONArray("b").getJSONArray(0).getJSONArray(0).getInt(0))
    }

    @Test
    fun anEscapedBackslashThenAnEscapedQuoteStaysInTheString() {
        val parsed = SafeJson.parseObject("""{"a":"say \\\"[[[","b":1}""")
        assertEquals("say \\\"[[[", parsed.getString("a"))
        assertEquals(1, parsed.getInt("b"))
    }

    @Test
    fun aTruncatedDocumentIsRefusedBeforeTheParser() {
        val thrown = refusedObject("""{"a":""")
        assertEquals(SafeJson.SYNTAX_MESSAGE, thrown.message)
        assertNull(thrown.cause)
        val cutOffEscape = refusedObject("""{"a":"\""")
        assertEquals(SafeJson.SYNTAX_MESSAGE, cutOffEscape.message)
        assertNull(cutOffEscape.cause)
    }

    @Test
    fun lenientSyntaxIsRefusedBeforeTheParser() {
        val hidden = "[".repeat(10_000)
        val cases = listOf(
            "/*\"*/$hidden",
            "//\"\n$hidden",
            "#\"\n$hidden",
            "['\"',$hidden",
            "[',[']',$hidden",
            "{a:1}",
            "{'a':1}",
            "{\"a\"=1}",
            "{\"a\"=>1}",
            "[1;2]",
            "]",
            "}",
            "{",
            "{}{}",
            "hello",
            "+1",
            "01",
        )
        for (text in cases) {
            val thrown = refusedObject(text)
            assertEquals(text.take(24), SafeJson.SYNTAX_MESSAGE, thrown.message)
            assertNull(text.take(24), thrown.cause)
        }
    }

    @Test
    fun tooManyValuesIsRefusedBeforeTheParser() {
        val body = buildString(SafeJson.MAX_JSON_VALUES * 3 + 2) {
            append('[')
            repeat(SafeJson.MAX_JSON_VALUES) { index ->
                if (index > 0) append(',')
                append("{}")
            }
            append(']')
        }
        assertTrue(body.length < SafeJson.MAX_JSON_BYTES)
        val thrown = refused(body)
        assertEquals(SafeJson.VALUES_MESSAGE, thrown.message)
        assertNull(thrown.cause)
        assertEquals(2, SafeJson.parseArray("[{},{}]").length())
    }

    @Test
    fun networkRejectsALeadingBomAndLocalStripsExactlyOne() {
        val withBom = "\uFEFF{\"a\":1}"
        val rejected = refusedObject(withBom)
        assertEquals(SafeJson.BOM_MESSAGE, rejected.message)
        assertNull(rejected.cause)

        val stripped = SafeJson.parseObject(withBom, JsonSource.LOCAL)
        assertEquals(1, stripped.getInt("a"))

        val two = refusedObject("\uFEFF\uFEFF{\"a\":1}", JsonSource.LOCAL)
        assertEquals(SafeJson.SECOND_BOM_MESSAGE, two.message)
        assertNull(two.cause)

        val twoOnTheNetwork = refusedObject("\uFEFF\uFEFF{}")
        assertEquals(SafeJson.BOM_MESSAGE, twoOnTheNetwork.message)
    }

    @Test
    fun oversizeIsRefusedBeforeNestingOrTheParser() {
        val over = "[".repeat(SafeJson.MAX_JSON_BYTES + 1)
        val thrown = refused(over)
        assertEquals(SafeJson.SIZE_MESSAGE, thrown.message)
        assertNull(thrown.cause)

        val pad = SafeJson.MAX_JSON_BYTES - """{"a":""}""".length
        val exact = "{\"a\":\"" + "x".repeat(pad) + "\"}"
        assertEquals(SafeJson.MAX_JSON_BYTES, exact.length)
        assertEquals(pad, SafeJson.parseObject(exact).getString("a").length)
    }

    @Test
    fun aCappedBodyReadStopsAtTheExtraByte() {
        val over = "x".repeat(SafeJson.MAX_JSON_BYTES + 1)
        val body = over.toResponseBody("application/json".toMediaType())
        try {
            body.readUtf8Capped()
            fail("expected the cap")
        } catch (e: ImportTooLargeException) {
            assertEquals(SafeJson.MAX_JSON_BYTES.toLong(), e.limit)
        }
    }

    @Test
    fun tenThousandDeepDoesNotOverflowASmallStack() {
        val input = nestedArray(10_000)
        val thrown = AtomicReference<Throwable>()
        val thread = Thread(
            null,
            Runnable {
                try {
                    SafeJson.parseObject(input)
                    thrown.set(AssertionError("10,000 nested arrays were parsed"))
                } catch (t: Throwable) {
                    thrown.set(t)
                }
            },
            "safe-json-small-stack",
            64L * 1024,
        )
        thread.isDaemon = true
        thread.start()
        thread.join(30_000)
        assertFalse("small-stack parse did not finish", thread.isAlive)
        val ex = thrown.get()
        assertTrue(ex.toString(), ex is UnsafeJsonException)
        assertEquals(SafeJson.DEPTH_MESSAGE, ex.message)
        assertNull(ex.cause)
    }

    private fun refused(text: String, source: JsonSource = JsonSource.NETWORK): UnsafeJsonException {
        try {
            SafeJson.parseArray(text, source)
            fail("expected UnsafeJsonException")
        } catch (e: UnsafeJsonException) {
            return e
        }
        error("unreachable")
    }

    private fun refusedObject(
        text: String,
        source: JsonSource = JsonSource.NETWORK,
    ): UnsafeJsonException {
        try {
            SafeJson.parseObject(text, source)
            fail("expected UnsafeJsonException")
        } catch (e: UnsafeJsonException) {
            return e
        }
        error("unreachable")
    }

    private fun assertThrowsJson(text: String, parse: (String) -> Any): JSONException {
        try {
            parse(text)
            fail("expected JSONException")
        } catch (e: JSONException) {
            return e
        }
        error("unreachable")
    }

    private fun nestedArray(depth: Int): String = buildString(depth * 2 + 1) {
        repeat(depth) { append('[') }
        append('1')
        repeat(depth) { append(']') }
    }

    private fun nestedObject(depth: Int): String = buildString(depth * 6 + 1) {
        repeat(depth) { append("{\"k\":") }
        append('1')
        repeat(depth) { append('}') }
    }
}
