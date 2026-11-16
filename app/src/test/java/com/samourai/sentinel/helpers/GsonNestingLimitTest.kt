package com.samourai.sentinel.helpers

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonSyntaxException
import com.samourai.sentinel.data.Info
import com.samourai.sentinel.ui.dojo.DojoPairing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #555: Gson 2.9 built a JsonElement tree with no depth limit, so a few
 * thousand nested brackets threw StackOverflowError, which escapes
 * `catch (Exception)`. 2.15's JsonReader stops at its default nesting
 * limit and throws JsonSyntaxException instead.
 *
 * The deep documents are far past that limit (and past the ~10 KB of
 * brackets that overflowed 2.9). A shallow tree and the models the app
 * actually parses still succeed.
 */
class GsonNestingLimitTest {

    @Test
    fun deeplyNestedTreeThrowsJsonSyntaxException() {
        val thrown = assertThrows(JsonSyntaxException::class.java) {
            Gson().fromJson(nested(DEEP), JsonElement::class.java)
        }
        val text = generateSequence<Throwable>(thrown) { it.cause }
            .mapNotNull { it.message }
            .joinToString("\n")
        assertTrue(text, text.contains("Nesting limit"))
    }

    @Test
    fun fromJSONReturnsNullInsteadOfCrashing() {
        // Shared parser used for Dojo, wallet, and cache payloads.
        assertNull(fromJSON<JsonElement>(nested(DEEP)))
        // Reflective model: the reader still has to walk the unknown field.
        val withExtra = """{"pairing":{"url":"http://s.onion/v2"},"extra":${nested(DEEP)}}"""
        assertNull(fromJSON<DojoPairing>(withExtra))
    }

    @Test
    fun shallowTreeAndAppModelsStillParse() {
        val tree = Gson().fromJson(nested(SHALLOW), JsonElement::class.java)
        var inner = tree
        repeat(SHALLOW) { inner = inner.asJsonArray[0] }
        assertEquals(1, inner.asInt)

        val pairing = fromJSON<DojoPairing>(
            """{"pairing":{"type":"dojo.api","apikey":"synthetic","url":"http://synthetic.onion/v2"}}"""
        )
        assertEquals("http://synthetic.onion/v2", pairing!!.pairing!!.url)

        val info = fromJSON<Info>(
            """{"latest_block":{"hash":"aa","height":800000,"time":1700000000},"fees":{"2":1,"6":21}}"""
        )
        assertEquals(800000L, info!!.latest_block.height)
        assertEquals(21L, info.fees!!["6"])
        assertEquals(listOf("2", "6"), info.fees!!.keys.toList())

        val direct = Gson().fromJson(
            """{"pairing":{"url":"http://s.onion/v2"}}""",
            DojoPairing::class.java,
        )
        assertEquals("http://s.onion/v2", direct.pairing!!.url)
    }

    private fun nested(depth: Int): String = buildString(depth * 2 + 1) {
        repeat(depth) { append('[') }
        append('1')
        repeat(depth) { append(']') }
    }

    private companion object {
        // Under the default limit (255).
        const val SHALLOW = 8
        // Well past the limit, and in the size that overflowed Gson 2.9.
        const val DEEP = 4096
    }
}
