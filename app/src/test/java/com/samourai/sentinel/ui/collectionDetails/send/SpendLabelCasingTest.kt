package com.samourai.sentinel.ui.collectionDetails.send

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #414: the coin picker chip and the UTXO screen's status label name the
 * same concept. One casing. The picker chip is translatable="false"
 * with no fallback locales, so the English string is the whole surface.
 */
class SpendLabelCasingTest {

    @Test
    fun pickerChipMatchesTheUtxoStatusLabel() {
        val xml = source(
            "src/main/res/values/strings.xml",
            "app/src/main/res/values/strings.xml",
        )
        assertEquals(
            "coin_picker_do_not_spend and do_not_spend must render identically",
            stringOf(xml, "do_not_spend"),
            stringOf(xml, "coin_picker_do_not_spend"),
        )
    }

    private fun stringOf(xml: String, key: String): String {
        val at = xml.indexOf("name=\"$key\"")
        assertTrue("missing string $key", at >= 0)
        val open = xml.indexOf('>', at)
        val close = xml.indexOf("</string>", open)
        assertTrue(open > at && close > open)
        return xml.substring(open + 1, close)
    }

    private fun source(vararg relative: String): String {
        val file = relative.map { File(it) }.firstOrNull { it.isFile }
            ?: error("source not found from ${File(".").absolutePath}: ${relative.toList()}")
        return file.readText()
    }
}