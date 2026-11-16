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
 * [screen] is one iterative pass. It accepts only strict RFC 8259: no
 * comments, no single quotes, no bare words other than true, false and
 * null, no '=' or ';', no closer at depth 0, no unclosed value, and no
 * trailing text. Android's parser accepts all of those, and a comment
 * or a single quote can hide thousands of brackets from a scan that
 * only knows double quotes. The pass also caps depth and the number of
 * values, so a flat `[{},{},...]` cannot build millions of objects.
 * It throws [UnsafeJsonException] with no cause, before org.json runs.
 *
 * The only backstop is the [StackOverflowError] catch around the parse
 * call itself. Nothing else is caught. [org.json.JSONObject.toString]
 * and the nested getters walk the tree by recursion, and that walk is
 * outside the catch. A document that fails this pass never becomes a
 * tree, and one that passes is at most [MAX_JSON_DEPTH] containers deep.
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
     * Largest character count [screen] will walk. This matches the
     * largest routed body ([WALLET_MAX_BYTES], and the previous-transaction
     * envelope). Smaller endpoints use their own socket caps.
     */
    const val MAX_JSON_BYTES = 1_000_000

    /**
     * Login, refresh, and the pairing POST. A real authorization body
     * is a few hundred bytes.
     */
    const val AUTH_MAX_BYTES = 64 * 1024

    /** Fee estimates are a handful of integer tiers. */
    const val FEES_MAX_BYTES = 64 * 1024

    /**
     * Import status, rescan, broadcast, segwit import, and exchange
     * rates. None of those documents is a wallet page.
     */
    const val SMALL_BODY_MAX_BYTES = 64 * 1024

    /**
     * One `/wallet` page: the newest transactions plus unspent outputs.
     * A page over this fails that pub instead of being parsed.
     */
    const val WALLET_MAX_BYTES = 1_000_000

    /** One esplora-shaped transaction (`/tx/{txid}`). */
    const val TX_MAX_BYTES = 512 * 1024

    /**
     * Objects, arrays, strings, numbers, and the three literals.
     * A heavy wallet page — 50 transactions with a hundred inputs and
     * outputs each, plus several thousand unspent outputs — is on the
     * order of a few tens of thousands of values. 200_000 is several
     * times that, and about a tenth of the empty-object list that fits
     * in a megabyte (`{}` is two bytes and one value). Each object is
     * a heap allocation, so the count is what stops that list from
     * exhausting the process. `OutOfMemoryError` is an [Error].
     */
    const val MAX_JSON_VALUES = 200_000

    const val DEPTH_MESSAGE = "JSON nesting is deeper than $MAX_JSON_DEPTH"
    const val SIZE_MESSAGE = "JSON is longer than $MAX_JSON_BYTES characters"
    const val VALUES_MESSAGE = "JSON has more than $MAX_JSON_VALUES values"
    const val SYNTAX_MESSAGE = "JSON is not strict RFC 8259"
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
     * no recursion. Strings are double-quoted only, and a backslash
     * escapes one character: `\"` stays in the string, `\\` followed
     * by a quote ends it, and `\u0022` is a character inside the
     * string rather than a closer.
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
        StrictJson(body).check()
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

    /**
     * One value, then nothing but whitespace. Containers are an explicit
     * stack so a 10,000-deep document cannot overflow this pass.
     */
    private class StrictJson(private val text: String) {
        private var i = 0
        private var values = 0
        private val stack = ArrayDeque<Frame>()

        fun check() {
            var started = false
            var need = Need.VALUE
            while (true) {
                skipWs()
                if (i >= text.length) {
                    if (started && need == Need.AFTER && stack.isEmpty()) return
                    syntax()
                }
                val c = text[i]
                when (need) {
                    Need.VALUE, Need.VALUE_OR_END -> {
                        if (need == Need.VALUE_OR_END && c == ']') {
                            i++
                            close(Frame.ARRAY)
                            need = Need.AFTER
                            continue
                        }
                        if (c == '}' || c == ']') syntax()
                        started = true
                        when (c) {
                            '{' -> {
                                open(Frame.OBJECT)
                                need = Need.KEY_OR_END
                            }
                            '[' -> {
                                open(Frame.ARRAY)
                                need = Need.VALUE_OR_END
                            }
                            '"' -> {
                                string()
                                count()
                                need = Need.AFTER
                            }
                            else -> {
                                literal()
                                count()
                                need = Need.AFTER
                            }
                        }
                    }
                    Need.KEY_OR_END, Need.KEY -> {
                        if (need == Need.KEY_OR_END && c == '}') {
                            i++
                            close(Frame.OBJECT)
                            need = Need.AFTER
                            continue
                        }
                        if (c != '"') syntax()
                        string()
                        count()
                        need = Need.COLON
                    }
                    Need.COLON -> {
                        if (c != ':') syntax()
                        i++
                        need = Need.VALUE
                    }
                    Need.AFTER -> {
                        if (stack.isEmpty()) syntax()
                        when (stack.last()) {
                            Frame.ARRAY -> when (c) {
                                ']' -> {
                                    i++
                                    close(Frame.ARRAY)
                                }
                                ',' -> {
                                    i++
                                    need = Need.VALUE
                                }
                                else -> syntax()
                            }
                            Frame.OBJECT -> when (c) {
                                '}' -> {
                                    i++
                                    close(Frame.OBJECT)
                                }
                                ',' -> {
                                    i++
                                    need = Need.KEY
                                }
                                else -> syntax()
                            }
                        }
                    }
                }
            }
        }

        private fun open(frame: Frame) {
            if (stack.size + 1 > MAX_JSON_DEPTH) {
                throw UnsafeJsonException(DEPTH_MESSAGE)
            }
            i++
            count()
            stack.addLast(frame)
        }

        private fun close(expected: Frame) {
            if (stack.isEmpty() || stack.removeLast() != expected) syntax()
        }

        private fun count() {
            values++
            if (values > MAX_JSON_VALUES) {
                throw UnsafeJsonException(VALUES_MESSAGE)
            }
        }

        private fun string() {
            if (i >= text.length || text[i] != '"') syntax()
            i++
            while (i < text.length) {
                val c = text[i++]
                when {
                    c == '"' -> return
                    c == '\\' -> escape()
                    c < ' ' -> syntax()
                }
            }
            syntax()
        }

        private fun escape() {
            if (i >= text.length) syntax()
            when (val c = text[i++]) {
                '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                'u' -> {
                    repeat(4) {
                        if (i >= text.length || text[i] !in HEX) syntax()
                        i++
                    }
                }
                else -> syntax()
            }
        }

        private fun literal() {
            val c = text[i]
            if (c == '-' || c.isDigit()) {
                number()
                return
            }
            if (!c.isLetter()) syntax()
            val start = i
            while (i < text.length && text[i].isLetter()) i++
            when (text.substring(start, i)) {
                "true", "false", "null" -> Unit
                else -> syntax()
            }
        }

        private fun number() {
            if (text[i] == '-') {
                i++
                if (i >= text.length || !text[i].isDigit()) syntax()
            }
            if (i >= text.length || !text[i].isDigit()) syntax()
            if (text[i] == '0') {
                i++
                if (i < text.length && text[i].isDigit()) syntax()
            } else {
                while (i < text.length && text[i].isDigit()) i++
            }
            if (i < text.length && text[i] == '.') {
                i++
                if (i >= text.length || !text[i].isDigit()) syntax()
                while (i < text.length && text[i].isDigit()) i++
            }
            if (i < text.length && (text[i] == 'e' || text[i] == 'E')) {
                i++
                if (i < text.length && (text[i] == '+' || text[i] == '-')) i++
                if (i >= text.length || !text[i].isDigit()) syntax()
                while (i < text.length && text[i].isDigit()) i++
            }
        }

        private fun skipWs() {
            while (i < text.length && text[i].isJsonWs()) i++
        }

        private fun syntax(): Nothing = throw UnsafeJsonException(SYNTAX_MESSAGE)

        private enum class Frame { OBJECT, ARRAY }

        private enum class Need { VALUE, VALUE_OR_END, KEY, KEY_OR_END, COLON, AFTER }

        private companion object {
            val HEX = "0123456789abcdefABCDEF".toSet()
        }
    }
}

private fun Char.isJsonWs(): Boolean = this == ' ' || this == '\t' || this == '\n' || this == '\r'

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
