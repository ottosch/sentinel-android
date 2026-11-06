package com.samourai.sentinel.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * #282: pairing-password matching against the bundled EFF large wordlist.
 * Reads the real asset, so the list itself is checked too. Pure JVM.
 */
class PairingWordsTest {

    @Test
    fun bundledListIsTheEffLargeWordlist() {
        assertEquals(7776, words.size)
        for (w in listOf("abacus", "zoom", "drop-down", "felt-tip", "t-shirt", "yo-yo")) {
            assertTrue(w, words.contains(w))
        }
    }

    @Test
    fun bundledListIsPrefixFree() {
        // The greedy matcher and autoSeparate rely on this.
        val all = PairingWords.parseList(asset().readLines().asSequence())
        val sorted = all.sorted()
        for (i in 0 until sorted.size - 1) {
            assertFalse("${sorted[i]} starts ${sorted[i + 1]}", sorted[i + 1].startsWith(sorted[i]))
        }
    }

    @Test
    fun normalizeTrimsLowercasesAndJoinsWithDashes() {
        assertEquals("abacus-zoom-t-shirt", PairingWords.normalize("  Abacus  ZOOM\tt-shirt \n"))
        assertEquals("abacus-zoom-yo-yo", PairingWords.normalize("abacus - zoom -- yo-yo-"))
        assertEquals("", PairingWords.normalize("   "))
    }

    @Test
    fun threeWordsInAnyCaseAndSeparatorAreComplete() {
        assertTrue(words.analyze("abacus-zoom-abdomen").isComplete)
        assertTrue(words.analyze("Abacus Zoom ABDOMEN").isComplete)
        assertTrue(words.analyze("  abacus  zoom abdomen \n").isComplete)
    }

    @Test
    fun hyphenatedListWordsCountAsOneWord() {
        val a = words.analyze("t-shirt-drop-down-yo-yo")
        assertEquals(listOf("t-shirt", "drop-down", "yo-yo"), a.words)
        assertTrue(a.isComplete)
        assertTrue(words.analyze("felt-tip abacus t-shirt").isComplete)
        assertTrue(words.analyze("abacus-zoom-felt-tip").isComplete)
    }

    @Test
    fun misspeltWordIsReported() {
        val a = words.analyze("appel-zoom-abacus")
        assertEquals("appel", a.invalid)
        assertFalse(a.isComplete)
    }

    @Test
    fun aWordBeingTypedIsAFragmentNotAnError() {
        val a = words.analyze("abacus-zo")
        assertNull(a.invalid)
        assertEquals("zo", a.fragment)
        assertFalse(a.isComplete)
        // The start of a hyphenated word, across its own "-".
        assertEquals("t-sh", words.analyze("abacus-t-sh").fragment)
        assertNull(words.analyze("abacus-t-sh").invalid)
    }

    @Test
    fun twoOrFourWordsAreNotComplete() {
        assertFalse(words.analyze("abacus-zoom").isComplete)
        val four = words.analyze("abacus-zoom-abdomen-abacus")
        assertTrue(four.tooMany)
        assertFalse(four.isComplete)
    }

    @Test
    fun suggestionsCompleteTheTrailingFragment() {
        val s = words.suggestions("abacus-abdo")
        assertTrue(s.isNotEmpty())
        assertTrue(s.all { it.startsWith("abdo") })
        assertTrue(s.contains("abdomen"))
        assertTrue(words.suggestions("abacus-t-sh").contains("t-shirt"))
        assertTrue("no suggestions without a fragment", words.suggestions("abacus-").isEmpty())
        assertTrue("none once three words are in", words.suggestions("abacus-zoom-abdomen").isEmpty())
    }

    @Test
    fun completeReplacesTheFragmentAndAddsTheSeparator() {
        assertEquals("abacus-abdomen-", words.complete("Abacus abdo", "abdomen"))
        assertEquals("abacus-zoom-t-shirt", words.complete("abacus-zoom-t-s", "t-shirt"))
        assertEquals("unknown word leaves text alone", "abacus-ab", words.complete("abacus-ab", "notaword"))
    }

    @Test
    fun autoSeparateAddsDashAfterAWholeWordOnly() {
        assertEquals("abacus-", words.autoSeparate("abacus"))
        assertEquals("abacus-zoom-", words.autoSeparate("abacus-zoom"))
        assertEquals("not after the last word", "abacus-zoom-abdomen", words.autoSeparate("abacus-zoom-abdomen"))
        assertEquals("not mid-word", "abac", words.autoSeparate("abac"))
        // "t" is not a list word: t-shirt is still being typed.
        assertEquals("t", words.autoSeparate("t"))
        assertEquals("space becomes the separator", "abacus-", words.autoSeparate("abacus "))
    }

    @Test
    fun separatorLessWordsParseAndComplete() {
        val a = words.analyze("abacuszoomabdomen")
        assertEquals(listOf("abacus", "zoom", "abdomen"), a.words)
        assertNull(a.invalid)
        assertTrue(a.isComplete)
        assertTrue(words.analyze(" AbacusZoomABDOMEN").isComplete)
    }

    @Test
    fun separatorLessFragmentGetsSuggestions() {
        val a = words.analyze("abacuszoomabdo")
        assertNull(a.invalid)
        assertEquals("abdo", a.fragment)
        assertTrue(words.suggestions("abacuszoomabdo").contains("abdomen"))
    }

    @Test
    fun mixedSeparatorsParse() {
        val a = words.analyze("abacus zoom-abdomen")
        assertEquals(listOf("abacus", "zoom", "abdomen"), a.words)
        assertTrue(a.isComplete)
    }

    @Test
    fun hyphenatedWordWithoutItsInnerHyphenStaysAnError() {
        assertEquals("tshirt", words.analyze("tshirt-zoom-abdomen").invalid)
        assertFalse(words.analyze("tshirtzoomabdomen").isComplete)
    }

    @Test
    fun canonicalJoinsAnySeparatorShapeWithDashes() {
        assertEquals("abacus-zoom-abdomen", words.canonical("abacuszoomabdomen"))
        assertEquals("abacus-zoom-t-shirt", words.canonical("Abacus ZoomT-SHIRT"))
        assertEquals("abacus-zoom-abdomen", words.canonical("abacus zoom-abdomen"))
        // Incomplete or non-parsing text falls back to the plain shaper.
        assertEquals("abacus-zoom", words.canonical("abacus zoom"))
        assertEquals("appelzoomabdomen", words.canonical("appelzoomabdomen"))
    }

    companion object {
        private lateinit var words: PairingWords

        private fun asset(): File {
            // Unit tests run from the module directory under Gradle.
            val candidates = listOf(
                File("src/main/assets/${PairingWords.ASSET_PATH}"),
                File("app/src/main/assets/${PairingWords.ASSET_PATH}")
            )
            return candidates.firstOrNull { it.exists() }
                ?: error("wordlist asset not found from ${File(".").absolutePath}")
        }

        @JvmStatic
        @BeforeClass
        fun load() {
            words = PairingWords(PairingWords.parseList(asset().readLines().asSequence()))
        }
    }
}
