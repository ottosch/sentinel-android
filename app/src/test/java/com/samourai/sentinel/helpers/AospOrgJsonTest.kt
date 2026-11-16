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
 *
 * The overflow characterization starts a Thread with a 256 KiB stack.
 * That size is only a hint, and some JVMs never overflow (this check
 * failed on a #554 PR run after passing on master and on #574). When
 * the effective stack is large enough, AOSP throws JSONException
 * instead of StackOverflowError, or finishes. Skip only that check
 * in those cases; still fail on any other throwable. Every SafeJson
 * refusal stays strict and always runs.
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
            val refused = refuse(text)
            assertEquals(text.take(32), SafeJson.SYNTAX_MESSAGE, refused.message)
            assertNull(text.take(32), refused.cause)
            assertOverflowedOrSkip(text.take(32), aosp)
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
        check(!thread.isAlive) { "AOSP toString still running after 30s" }
        val refused = refuse("[".repeat(10_000) + "1" + "]".repeat(10_000))
        assertEquals(SafeJson.DEPTH_MESSAGE, refused.message)
        assertNull(refused.cause)
        assertOverflowedOrSkip("toString", thrown.get())
    }

    /**
     * Keep the overflow assertion when it holds. If this JVM did not
     * overflow on the 256 KiB stack hint, AOSP either finishes or
     * throws [aosp.json.JSONException] (unterminated structure). Skip
     * only that check in those cases. [org.junit.Assume] would abort
     * later cases in the loop, so this is the equivalent that still
     * runs every SafeJson refusal. Any other throwable still fails,
     * so a real parser change is not hidden.
     */
    private fun assertOverflowedOrSkip(label: String, thrown: Throwable?) {
        if (thrown == null || thrown is aosp.json.JSONException) {
            return
        }
        assertTrue(
            "$label: expected StackOverflowError from AOSP, got ${thrown.javaClass.name}: ${thrown.message}",
            thrown is StackOverflowError,
        )
    }

    private fun aosp(text: String): Throwable? {
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
        check(!thread.isAlive) { "AOSP parse still running after 30s for ${text.take(32)}" }
        return thrown.get()
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
