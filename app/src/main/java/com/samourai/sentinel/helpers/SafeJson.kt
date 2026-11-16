package com.samourai.sentinel.helpers

import com.samourai.sentinel.util.ImportLimits
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Where a string came from. Network input rejects a leading U+FEFF.
 * Local input strips exactly one and then parses the rest strictly.
 */
enum class JsonSource {
    NETWORK,
    LOCAL,
}

/**
 * #567. Android's org.json walks nested `[` / `{` by recursion and has
 * no depth limit, so a few kilobytes of brackets throws
 * [StackOverflowError]. That is an [Error], and every `catch (Exception)`
 * lets it kill the process.
 *
 * The jar on the unit-test classpath (`org.json:json` in app/build.gradle)
 * is a different parser. It does not enforce a depth cap on
 * `JSONObject(String)`, and when the recursion does overflow it catches
 * [StackOverflowError] and rethrows [JSONException]. A test that accepts
 * any [JSONException] therefore passes on code that crashes a device.
 *
 * [screen] is one iterative pass. It rejects depth, size, and a leading
 * BOM before org.json is called, and throws [UnsafeJsonException] with
 * no cause. The only backstop is the [StackOverflowError] catch around
 * the parse call itself. Nothing else is caught.
 */
class UnsafeJsonException(message: String) : JSONException(message)

object SafeJson {

    /**
     * One depth for network and local input. A deeper document is
     * refused. A reviewer can tune this; a limit that is too low fails
     * the call instead of parsing.
     */
    const val MAX_JSON_DEPTH = 32

    /**
     * One ceiling for every routed network body. The socket read counts
     * bytes; [screen] counts characters. JSON text is one byte per
     * character, so the two agree on the documents this app parses.
     * Per-endpoint ceilings are a follow-up. A real payload over this
     * fails that call.
     */
    const val MAX_JSON_BYTES = 8 * 1024 * 1024

    const val DEPTH_MESSAGE = "JSON nesting is deeper than $MAX_JSON_DEPTH"
    const val SIZE_MESSAGE = "JSON is longer than $MAX_JSON_BYTES characters"
    const val BOM_MESSAGE = "JSON starts with a byte order mark"
    const val SECOND_BOM_MESSAGE = "JSON has a second byte order mark"
    const val PARSER_OVERFLOW = "JSON parser overflowed its stack"

    fun parseObject(text: String, source: JsonSource = JsonSource.NETWORK): JSONObject {
        val body = screen(text, source)
        try {
            return JSONObject(body)
        } catch (overflow: StackOverflowError) {
            throw UnsafeJsonException(PARSER_OVERFLOW).also { it.initCause(overflow) }
        }
    }

    fun parseArray(text: String, source: JsonSource = JsonSource.NETWORK): JSONArray {
        val body = screen(text, source)
        try {
            return JSONArray(body)
        } catch (overflow: StackOverflowError) {
            throw UnsafeJsonException(PARSER_OVERFLOW).also { it.initCause(overflow) }
        }
    }

    /**
     * Rejects a document org.json must not see. Iterative, one pass,
     * no recursion. Brackets inside strings do not count. A backslash
     * escapes the next character only: `\"` stays in the string, and
     * `\\` followed by a quote ends it.
     */
    internal fun screen(text: String, source: JsonSource): String {
        if (text.length > MAX_JSON_BYTES) {
            throw UnsafeJsonException(SIZE_MESSAGE)
        }
        val body = when (source) {
            JsonSource.NETWORK -> {
                if (text.isNotEmpty() && text[0] == '\uFEFF') {
                    throw UnsafeJsonException(BOM_MESSAGE)
                }
                text
            }
            JsonSource.LOCAL -> stripOneLeadingBom(text)
        }
        var index = 0
        var depth = 0
        var inString = false
        var escaped = false
        while (index < body.length) {
            val c = body[index]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{', '[' -> {
                        depth++
                        if (depth > MAX_JSON_DEPTH) {
                            throw UnsafeJsonException(DEPTH_MESSAGE)
                        }
                    }
                    '}', ']' -> if (depth > 0) depth--
                }
            }
            index++
        }
        return body
    }

    private fun stripOneLeadingBom(text: String): String {
        if (text.isEmpty() || text[0] != '\uFEFF') return text
        val rest = text.substring(1)
        if (rest.isNotEmpty() && rest[0] == '\uFEFF') {
            throw UnsafeJsonException(SECOND_BOM_MESSAGE)
        }
        return rest
    }
}

/**
 * Reads at most [maxBytes] and decodes them as UTF-8. Byte
 * `maxBytes + 1` throws before it is kept, so an oversized body never
 * becomes a String. Closes [this].
 */
fun ResponseBody.readUtf8Capped(maxBytes: Int = SafeJson.MAX_JSON_BYTES): String {
    return byteStream().use { input ->
        ImportLimits.readBoundedBytes(input, maxBytes).toString(Charsets.UTF_8)
    }
}
