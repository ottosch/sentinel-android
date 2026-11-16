package com.samourai.sentinel.ui.dojo

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.Strictness
import com.google.gson.internal.bind.TypeAdapters
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.samourai.sentinel.helpers.gson
import java.io.StringReader

/**
 * The reader for everything Dojo Bay sends (dojos.json, data/operator.json,
 * a listing's signed pairing text) and for the saved copy of the list.
 *
 * It is our own [JsonReader] in [Strictness.STRICT], which accepts what
 * JSON.parse accepts and nothing else: no upper-case NULL/TRUE/FALSE, no `\'`
 * escape, no raw control characters in strings, no comments, no unquoted
 * names. (`isLenient = false` is [Strictness.LEGACY_STRICT], which still
 * accepts the first three.) It never goes through the shared
 * [com.samourai.sentinel.helpers.fromJSON], whose Gson is LENIENT and would
 * override the reader's strictness: values are read with a [com.google.gson.TypeAdapter]
 * on this reader, which keeps it.
 *
 * Nesting is limited to [NESTING_LIMIT] levels (real documents are about 4
 * deep), so a deeply nested document fails as malformed JSON instead of
 * recursing. A leading byte-order mark is refused, as JSON.parse refuses it
 * (Gson's reader would skip it). StackOverflowError is still caught at each
 * boundary, though the limit keeps the recursion shallow.
 */
internal object DojoBayJson {

    const val NESTING_LIMIT = 32

    private const val BOM = '\uFEFF'

    /** A body as bytes, or null when it starts with a UTF-8, UTF-16 or UTF-32 byte-order mark. */
    fun decodeBody(bytes: ByteArray): String? {
        fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
        val utf8 = at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF
        val utf16 = (at(0) == 0xFE && at(1) == 0xFF) || (at(0) == 0xFF && at(1) == 0xFE)
        val utf32be = at(0) == 0x00 && at(1) == 0x00 && at(2) == 0xFE && at(3) == 0xFF
        if (utf8 || utf16 || utf32be) return null
        return String(bytes, Charsets.UTF_8)
    }

    private fun reader(text: String): JsonReader? {
        if (text.isEmpty() || text[0] == BOM) return null
        return JsonReader(StringReader(text)).apply {
            strictness = Strictness.STRICT
            nestingLimit = NESTING_LIMIT
        }
    }

    /** One JSON value and nothing after it, or null. */
    fun parseTree(text: String): JsonElement? = read(text) { TypeAdapters.JSON_ELEMENT.read(it) }

    /** [text] as a [T], read by [with]'s adapter on the strict reader; null if it isn't one strict JSON value. */
    fun <T> parse(text: String, type: Class<T>, with: Gson = gson): T? =
        read(text) { with.getAdapter(type).read(it) }

    private inline fun <T> read(text: String, block: (JsonReader) -> T?): T? {
        val reader = reader(text) ?: return null
        return try {
            val value = block(reader)
            if (reader.peek() != JsonToken.END_DOCUMENT) null else value
        } catch (e: Exception) {
            null
        } catch (e: StackOverflowError) {
            null
        }
    }
}
