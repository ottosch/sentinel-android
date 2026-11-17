package com.samourai.sentinel.tor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #581: Tor's own log lines reach logcat on debug builds only. Release
 * builds must not subscribe to extra Tor events or log Tor's lines
 * (#564/#576). The gate is a compile-time BuildConfig.DEBUG check, so
 * this pins it in the source: every Tor log observer sits inside it.
 */
class TorDaemonLogGateTest {

    private fun source(): String {
        val path = "java/com/samourai/sentinel/tor/SentinelTorManager.kt"
        return listOf(File("src/main/$path"), File("app/src/main/$path")).first { it.isFile }.readText()
    }

    /** The body of the single `if (BuildConfig.DEBUG) {` block, matched by braces. */
    private fun debugBlock(src: String): IntRange {
        val open = "if (BuildConfig.DEBUG) {"
        assertEquals("exactly one debug gate", 1, Regex(Regex.escape(open)).findAll(src).count())
        val start = src.indexOf(open) + open.length
        var depth = 1
        var i = start
        while (depth > 0) {
            when (src[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        return start until i
    }

    @Test
    fun `Tor log observers are registered only inside the BuildConfig DEBUG gate`() {
        val src = source()
        val block = debugBlock(src)
        val uses = Regex("""TorEvent\.\w+|\brequired\(|TOR_LOG_TAG\)""").findAll(src)
            .filterNot { src.lineSequenceAt(it.range.first).trimStart().startsWith("import ") }
            .toList()
        assertTrue("the gate forwards NOTICE and WARN", uses.size >= 4)
        for (m in uses) {
            assertTrue("${m.value} at ${m.range.first} is outside the debug gate", m.range.first in block)
        }
    }

    @Test
    fun `only notice and warning lines are forwarded`() {
        val block = source().let { it.substring(debugBlock(it)) }
        val events = Regex("""TorEvent\.(\w+)""").findAll(block).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("NOTICE", "WARN"), events)
        assertTrue(block.contains("Timber.tag(TOR_LOG_TAG)"))
        assertEquals("TorDaemon", SentinelTorManager.TOR_LOG_TAG)
    }

    private fun String.lineSequenceAt(index: Int): String {
        val from = lastIndexOf('\n', index) + 1
        val to = indexOf('\n', index).let { if (it < 0) length else it }
        return substring(from, to)
    }
}
