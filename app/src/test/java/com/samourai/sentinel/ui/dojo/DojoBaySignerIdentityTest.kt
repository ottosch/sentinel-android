package com.samourai.sentinel.ui.dojo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signer as the app writes it out: the grouped full code, the collapsed
 * row form, the PayNym link built from the signed code, and the same-name /
 * different-signer check. Codes here are constructed strings of the right
 * shape; nothing is decoded or signed.
 */
class DojoBaySignerIdentityTest {

    private val qa = "PM8TJUJHkfdjkb5g5XaJuvnC63rUz7LXHnVqsnCd17dhDRupn9gW3QrgJJdNNGxsR4LSe4kFBMwarLbJVRQur9ZQfY4rtTHWWgdjLeE1svskeNoCEoum"

    /** A code of the right shape: [head] + filler + [tail], 116 characters. */
    private fun code(head: String, middle: Char, tail: String): String {
        val fill = 116 - head.length - tail.length
        return head + middle.toString().repeat(fill) + tail
    }

    private fun checked(name: String, code: String, id: String = name + code.takeLast(3)) = CheckedDojo(
        CommunityDojoNode(id = id, name = name, status = "active"),
        DojoBaySignatures.NodeCheck.Verified(code, "{}", versionDrift = false),
    )

    private fun unverified(name: String) = CheckedDojo(
        CommunityDojoNode(id = name, name = name, status = "active"),
        DojoBaySignatures.NodeCheck.Unverified(DojoBaySignatures.Failure.BAD_SIGNATURE),
    )

    @Test
    fun `displayCode groups in fours, eight to a line, as in the mockup`() {
        val shown = SignerCodeFormat.displayCode(qa)
        val lines = shown.lines()
        assertEquals(
            listOf(
                "PM8T JUJH kfdj kb5g 5XaJ uvnC 63rU z7LX",
                "HnVq snCd 17dh DRup n9gW 3Qrg JJdN NGxs",
                "R4LS e4kF BMwa rLbJ VRQu r9ZQ fY4r tTHW",
                "Wgdj LeE1 svsk eNoC Eoum",
            ),
            lines,
        )
        assertTrue(lines.all { line -> line.split(' ').all { it.length == 4 } })
    }

    @Test
    fun `displayCode and the collapsed form round-trip to the code`() {
        assertEquals(qa, SignerCodeFormat.compact(SignerCodeFormat.displayCode(qa)))
        val collapsed = SignerCodeFormat.collapsedCode(qa)
        assertEquals("PM8TJUJH…eNoCEoum", collapsed)
        val (head, tail) = collapsed.split("…")
        assertTrue(qa.startsWith(head) && qa.endsWith(tail))
        assertEquals(8, head.length)
        assertEquals(8, tail.length)
        // Short strings are left as they are.
        assertEquals("PM8Tabc", SignerCodeFormat.collapsedCode("PM8Tabc"))
    }

    @Test
    fun `two codes alike in the first 8 and last 6 are flagged, and the full codes tell them apart`() {
        val a = code("PM8TJUJH", 'a', "eNoCEoum")
        val b = code("PM8TJUJH", 'b', "YzoCEoum")
        // Same first 8 and last 6 (the old short form).
        assertEquals(a.take(8), b.take(8))
        assertEquals(a.takeLast(6), b.takeLast(6))
        assertEquals(DojoBaySignatures.shortCode(a), DojoBaySignatures.shortCode(b))
        assertTrue(DojoBaySignatures.isPaymentCodeShape(a) && DojoBaySignatures.isPaymentCodeShape(b))

        val items = listOf(checked("QA valid (signed)", a), checked("qa valid (signed) ", b), checked("Other", qa))
        val names = SignerNameCollisions.names(items)
        assertEquals(setOf("qa valid (signed)"), names)
        assertTrue(SignerNameCollisions.collides(items[0], names))
        assertTrue(SignerNameCollisions.collides(items[1], names))
        assertFalse(SignerNameCollisions.collides(items[2], names))
        // The 8 + 8 form already differs here; the grouped full code always does.
        assertNotEquals(SignerCodeFormat.collapsedCode(a), SignerCodeFormat.collapsedCode(b))
        assertNotEquals(SignerCodeFormat.displayCode(a), SignerCodeFormat.displayCode(b))
    }

    @Test
    fun `codes that differ only in the middle collapse alike, so the dialog shows the full code`() {
        val a = code("PM8TJUJH", 'a', "eNoCEoum")
        val b = code("PM8TJUJH", 'b', "eNoCEoum")
        assertEquals(SignerCodeFormat.collapsedCode(a), SignerCodeFormat.collapsedCode(b))
        val names = SignerNameCollisions.names(listOf(checked("Same", a), checked("Same", b)))
        assertEquals(setOf("same"), names)
        val signerA = prePairSigner(a, DojoDisplaySnapshot("u", "Same", null, null, nameCollision = true), "u")!!
        val signerB = prePairSigner(b, DojoDisplaySnapshot("u", "Same", null, null, nameCollision = true), "u")!!
        assertNotEquals(signerA.displayCode, signerB.displayCode)
        assertTrue(signerA.nameCollision && signerB.nameCollision)
    }

    @Test
    fun `one signer listing twice under one name, unverified rows and blank names are not collisions`() {
        val items = listOf(
            checked("Twin", qa, id = "1"), checked("Twin", qa, id = "2"),
            unverified("Twin"), checked(" ", code("PM8Tzz", 'c', "zz")), checked("", code("PM8Tyy", 'd', "yy")),
        )
        assertTrue(SignerNameCollisions.names(items).isEmpty())
        assertTrue(SignerNameCollisions.names(listOf(checked("Twin", qa), unverified("Twin"))).isEmpty())
    }

    @Test
    fun `the PayNym link is the +name on the PayNym onion, never the code`() {
        // PayNym serves profiles at /+name (as Dojo Bay's cards link them);
        // /<payment code> is its "PayNym not found" page.
        assertEquals("http://paynym25chftmsywv4v2r67agbrr62lcxagsf4tymbzpeeucucy2ivad.onion/+91xTx93x3", PayNymLinks.profileUrl("+91xTx93x3"))
        assertEquals("http://paynym25chftmsywv4v2r67agbrr62lcxagsf4tymbzpeeucucy2ivad.onion/+condescendingcup55", PayNymLinks.profileUrl("+condescendingcup55"))
        assertEquals("/$qa/avatar", PayNymLinks.avatarPath(qa))
        assertEquals("paynym25chftmsywv4v2r67agbrr62lcxagsf4tymbzpeeucucy2ivad.onion", PayNymLinks.ONION_HOST)
        // A code, or anything not shaped like a +name, gives no link.
        assertNull(PayNymLinks.profileUrl(qa))
        assertNull(PayNymLinks.profileUrl(null))
        assertNull(PayNymLinks.profileUrl(""))
        assertNull(PayNymLinks.profileUrl("+"))
        assertNull(PayNymLinks.profileUrl("examplenym"))
        assertNull(PayNymLinks.profileUrl("+example/../x"))
        assertNull(PayNymLinks.profileUrl("+example?x=1"))
        assertNull(PayNymLinks.profileUrl("+example#f"))
        assertNull(PayNymLinks.profileUrl("+exa mple"))
        assertNull(PayNymLinks.profileUrl("+example\n"))
        assertNull(PayNymLinks.profileUrl("+" + "a".repeat(65)))
    }

    @Test
    fun `the dialog's signer comes from the picked listing's code only`() {
        val snap = DojoDisplaySnapshot("u", "QA", "🇸🇬", null)
        val signer = prePairSigner(qa, snap, "u")!!
        assertEquals(qa, signer.code)
        assertEquals(SignerCodeFormat.displayCode(qa), signer.displayCode)
        assertFalse(signer.nameCollision)
        // A scan or paste has no code: no section.
        assertNull(prePairSigner(null, snap, "u"))
        assertNull(prePairSigner("not a code", snap, "u"))
        // The collision flag rides the url-match guard.
        assertFalse(prePairSigner(qa, snap.copy(nameCollision = true), "other")!!.nameCollision)
        assertTrue(prePairSigner(qa, snap.copy(nameCollision = true), "u")!!.nameCollision)
    }
}
