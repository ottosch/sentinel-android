package com.samourai.sentinel.data.repository

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * BIP-329 lines go through Android's org.json. The plain-JVM org.json
 * jar is a different parser, so this runs under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Bip329ParserNestingTest {

    @Test
    fun deeplyNestedLineIsRefusedAndANormalLineStillParses() {
        val ref = "0123456789abcdef".repeat(4)
        val nested = buildString {
            append("""{"type":"tx","ref":"$ref","label":""")
            repeat(100_000) { append('[') }
            append('1')
            repeat(100_000) { append(']') }
            append('}')
        }
        val deep = Bip329Parser.parse(nested, "mainnet", 1L)
        assertTrue(deep == null || deep is Bip329Parser.Parsed.Ignored)

        val parsed = Bip329Parser.parse(
            """{"type":"tx","ref":"$ref","label":"rent"}""",
            "mainnet",
            1L,
        )
        assertTrue(parsed is Bip329Parser.Parsed.Other)
        val entry = (parsed as Bip329Parser.Parsed.Other).entry
        assertEquals("rent", entry.label)
        assertEquals(ref, entry.ref)
        assertEquals("tx", entry.type)
    }
}
