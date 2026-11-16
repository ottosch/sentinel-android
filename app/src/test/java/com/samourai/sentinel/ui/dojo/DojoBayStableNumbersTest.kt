package com.samourai.sentinel.ui.dojo

import com.samourai.sentinel.ui.dojo.DojoBaySignatures.NodeCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * stable() compares numbers by value without expanding them into decimal
 * digits. An 11-byte 1e100000000 used to become ~100M characters, and
 * 1e999999999 ran out of memory; both are now refused at once. Valid numbers
 * compare as JavaScript numbers do, for parity with crypto.ts.
 */
class DojoBayStableNumbersTest {

    private val alice = DojoBayTestVectors.ThrowawayPayNym(21)

    /** A listing whose signed and listed pairings both carry "n": [signedNumber] / [listedNumber]. */
    private fun listingWithNumber(signedNumber: String, listedNumber: String = signedNumber): CheckedDojo {
        fun withN(n: String) = DojoBayTestVectors.TEST_PAIRING_JSON.replace("\"type\":", "\"n\":$n,\"type\":")
        val directory = """{"nodes":[{"id":"a","status":"active","payload":${withN(listedNumber)},""" +
            """"signed":${com.google.gson.JsonPrimitive(alice.listing(withN(signedNumber)))}}]}"""
        val node = CommunityDojoRepository.parseDirectory(directory).single()
        return CheckedDojo(node, DojoBaySignatures.checkNode(node))
    }

    private fun timed(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    @Test
    fun `1e100000000 and 1e999999999 are refused quickly`() {
        for (n in listOf("1e100000000", "1e999999999", "-1e999999999", "1E+999999999", "1e-999999999")) {
            val ms = timed {
                val tree = DojoBaySignatures.parseStrictJson("{\"n\":$n}")!!
                assertNull(n, DojoBaySignatures.stable(tree))
                assertEquals(n, NodeCheck.Unverified(DojoBaySignatures.Failure.NOT_A_PAIRING), listingWithNumber(n).check)
            }
            assertTrue("$n took $ms ms", ms < 2_000)
        }
    }

    @Test
    fun `a huge number only in the listed copy is a mismatch, also quickly`() {
        val ms = timed {
            assertEquals(
                NodeCheck.Unverified(DojoBaySignatures.Failure.PAYLOAD_MISMATCH),
                listingWithNumber("1", listedNumber = "1e100000000").check,
            )
        }
        assertTrue(ms < 2_000)
    }

    @Test
    fun `numbers outside a double or longer than 32 characters are refused`() {
        assertNull(DojoBaySignatures.numberKey("1e400"))
        assertNull(DojoBaySignatures.numberKey("-1e400"))
        assertNull(DojoBaySignatures.numberKey("1e-1000"))
        assertNull(DojoBaySignatures.numberKey("1e0001000"))
        assertEquals("0", DojoBaySignatures.numberKey("1e-400"))
        assertEquals("1.0E300", DojoBaySignatures.numberKey("1e300"))
        assertEquals("1.0", DojoBaySignatures.numberKey("1e000"))
        assertNull(DojoBaySignatures.numberKey("1" + "0".repeat(32)))
        assertEquals("1.0E31", DojoBaySignatures.numberKey("1" + "0".repeat(31)))
        assertEquals(32, DojoBaySignatures.MAX_NUMBER_CHARS)
    }

    @Test
    fun `valid numbers compare by value, as JavaScript numbers`() {
        // JSON.stringify(JSON.parse(x)) is the same string for each group.
        val same = listOf(
            listOf("1", "1.0", "1e0", "10E-1", "0.1e1", "1.000"),
            listOf("0", "-0", "0.0", "0e5", "-0.0"),
            listOf("0.1", "0.10", "1e-1"),
            listOf("1e21", "1000000000000000000000"),
            // 2^53 + 1 is not a double: it is 2^53 in JavaScript too.
            listOf("9007199254740993", "9007199254740992"),
        )
        for (group in same) {
            val keys = group.map { DojoBaySignatures.numberKey(it) }
            assertTrue(group.toString(), keys.all { it != null && it == keys.first() })
        }
        assertNotEquals(DojoBaySignatures.numberKey("1"), DojoBaySignatures.numberKey("1.0000001"))
        assertNotEquals(DojoBaySignatures.numberKey("-1"), DojoBaySignatures.numberKey("1"))
    }

    @Test
    fun `numbers, strings and booleans stay distinct, keys sorted`() {
        fun s(json: String) = DojoBaySignatures.stable(DojoBaySignatures.parseStrictJson(json))
        assertNotEquals(s("""{"a":1}"""), s("""{"a":"1"}"""))
        assertNotEquals(s("""{"a":true}"""), s("""{"a":"true"}"""))
        assertEquals(s("""{"b":1,"a":[1.0,null]}"""), s("""{"a":[1,null],"b":1e0}"""))
        assertEquals("""{"a":[n:1.0,null],"b":n:1.0}""", s("""{"b":1,"a":[1.0,null]}"""))
    }

    @Test
    fun `a listing with the same small number signed and listed still verifies`() {
        assertTrue(listingWithNumber("1.0", listedNumber = "1").check is NodeCheck.Verified)
        assertTrue(listingWithNumber("5").check is NodeCheck.Verified)
    }
}
