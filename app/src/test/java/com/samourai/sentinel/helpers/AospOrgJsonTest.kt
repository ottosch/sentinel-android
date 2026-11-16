package com.samourai.sentinel.helpers

import aosp.json.JSONArray
import aosp.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * #567. These inputs are run through AOSP libcore org.json (copied
 * under aosp.json; the unit-test jar is a different parser and wraps
 * [StackOverflowError] in a [org.json.JSONException]). A comment, a
 * single quote, a bare key, `=>`, or `;` lets that parser recurse on
 * brackets a double-quote scan never counts. [SafeJson] must refuse
 * the same text with [UnsafeJsonException] and no cause.
 *
 * [org.json.JSONArray.toString] walks a tree by recursion and is not
 * inside SafeJson's catch. A tree that deep is only possible if the
 * pre-scan let the document through.
 */
class AospOrgJsonTest {

    @Test
    fun lenientNestingOverflowsAospAndIsRefusedByThePreScan() {
        val hidden = "[".repeat(10_000)
        val cases = listOf(
            "/*\"*/$hidden",
            "//\"\n$hidden",
            "#\"\n$hidden",
            "['\"',$hidden",
            "{a:$hidden}",
            "{\"a\"=>$hidden}",
            "[1;$hidden",
            """{"authorizations":{"access_token":/*"*/$hidden}}""",
        )
        for (text in cases) {
            val aosp = aosp(text)
            assertTrue(text.take(32), aosp is StackOverflowError)
            val refused = refuse(text)
            assertEquals(text.take(32), SafeJson.SYNTAX_MESSAGE, refused.message)
            assertNull(text.take(32), refused.cause)
        }
    }

    @Test
    fun aospToStringReturnsNullWhenANumberIsNotFinite() {
        val parsed = aosp.json.JSONObject(
            """{"authorizations":{"access_token":"a"},"x":[1e400]}"""
        )
        assertEquals("a", parsed.getJSONObject("authorizations").getString("access_token"))
        assertNull(parsed.toString())
    }

    @Test
    fun aospToStringOfADeepTreeOverflowsOutsideTheParse() {
        var inner = JSONArray()
        repeat(10_000) {
            val outer = JSONArray()
            outer.put(inner)
            inner = outer
        }
        val thrown = AtomicReference<Throwable>()
        val thread = Thread(
            null,
            Runnable {
                try {
                    inner.toString()
                } catch (t: Throwable) {
                    thrown.set(t)
                }
            },
            "aosp-tostring",
            256L * 1024,
        )
        thread.isDaemon = true
        thread.start()
        thread.join(30_000)
        assertTrue(thrown.get() is StackOverflowError)
        val refused = refuse("[".repeat(10_000) + "1" + "]".repeat(10_000))
        assertEquals(SafeJson.DEPTH_MESSAGE, refused.message)
        assertNull(refused.cause)
    }

    private fun aosp(text: String): Throwable {
        val thrown = AtomicReference<Throwable>()
        val thread = Thread(
            null,
            Runnable {
                try {
                    JSONTokener(text).nextValue()
                } catch (t: Throwable) {
                    thrown.set(t)
                }
            },
            "aosp-parse",
            256L * 1024,
        )
        thread.isDaemon = true
        thread.start()
        thread.join(30_000)
        return thrown.get() ?: AssertionError("AOSP parsed ${text.take(32)}")
    }

    private fun refuse(text: String): UnsafeJsonException {
        try {
            SafeJson.parseObject(text)
        } catch (e: UnsafeJsonException) {
            return e
        }
        try {
            SafeJson.parseArray(text)
        } catch (e: UnsafeJsonException) {
            return e
        }
        throw AssertionError("SafeJson parsed ${text.take(32)}")
    }
}
