package com.samourai.sentinel.ui.dojo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.ui.dojo.DojoBayPairedCheck.Row
import com.samourai.sentinel.ui.dojo.DojoBayPairedCheck.Status
import com.samourai.sentinel.ui.dojo.DojoBayTestVectors.ThrowawayPayNym
import com.samourai.sentinel.ui.utils.PrefsUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The paired Dojo's trusted signer: saved on PAIR from a listing, nothing on
 * DON'T PAIR, a listing under another code never counts as the paired Dojo,
 * a changed signer is SignerChanged, and manual pairings are unaffected.
 * Throwaway test-only codes; the onions are placeholders.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DojoBaySignerPinTest {

    private lateinit var prefs: PrefsUtil
    private val owner = ThrowawayPayNym(41)
    private val other = ThrowawayPayNym(42)

    private val urlA = "http://" + "a".repeat(56) + ".onion/test/v2"
    private val urlB = "http://" + "b".repeat(56) + ".onion/test/v2"

    private fun listing(signer: ThrowawayPayNym, url: String, name: String): CheckedDojo {
        val json = DojoBayTestVectors.TEST_PAIRING_JSON.replace(urlA, url)
        val payload = DojoBayTestVectors.testPayload().let { it.copy(pairing = it.pairing!!.copy(url = url)) }
        val node = CommunityDojoNode(
            id = name + signer.code.takeLast(4), network = "testnet", name = name, status = "active",
            paymentCode = signer.code, payload = payload, signed = signer.listing(json),
        )
        return CheckedDojo(node, DojoBaySignatures.checkNode(node))
    }

    @Before
    fun setUp() {
        prefs = PrefsUtil(ApplicationProvider.getApplicationContext<Context>())
        prefs.clearAll()
    }

    /** What commitPairing does with the held code. */
    private fun commit(pending: PendingSignerPin, url: String, name: String?) {
        prefs.apiEndPointTor = url
        prefs.dojoDisplayUrl = url
        prefs.dojoDisplayName = name
        DojoBaySignerPin.save(prefs, pending.takeForCommit(url, name))
    }

    @Test
    fun `the test listings verify`() {
        assertTrue(listing(owner, urlA, "Dojo").verified != null)
        assertTrue(listing(other, urlB, "Dojo").verified != null)
    }

    @Test
    fun `the signer's full code is saved on pair`() {
        val pending = PendingSignerPin()
        pending.select(owner.code)
        commit(pending, urlA, "Dojo")
        assertEquals(owner.code, prefs.dojoTrustedSignerCode)
        assertEquals(urlA, prefs.dojoTrustedSignerUrl)
        assertEquals(DojoBaySignerPin(urlA, owner.code, "Dojo"), DojoBaySignerPin.read(prefs))
        assertEquals(116, DojoBaySignerPin.read(prefs)!!.code.length)
    }

    @Test
    fun `nothing is saved on don't pair`() {
        val pending = PendingSignerPin()
        pending.select(owner.code)
        pending.discard()
        commit(PendingSignerPin(), urlA, null)
        assertNull(prefs.dojoTrustedSignerCode)
        assertNull(prefs.dojoTrustedSignerUrl)
        // And a declined pick leaves an earlier pin as it was.
        val first = PendingSignerPin().apply { select(owner.code) }
        commit(first, urlA, "Dojo")
        val declined = PendingSignerPin().apply { select(other.code) }
        declined.discard()
        assertEquals(owner.code, DojoBaySignerPin.read(prefs)!!.code)
    }

    @Test
    fun `the pin survives a restart`() {
        commit(PendingSignerPin().apply { select(owner.code) }, urlA, "Dojo")
        val restarted = PrefsUtil(ApplicationProvider.getApplicationContext<Context>())
        assertEquals(DojoBaySignerPin(urlA, owner.code, "Dojo"), DojoBaySignerPin.read(restarted))
    }

    @Test
    fun `only a listing signed by the trusted code is the paired Dojo`() {
        val pin = DojoBaySignerPin(urlA, owner.code, "Dojo")
        val real = listing(owner, urlA, "Dojo")
        val sameOnion = listing(other, urlA, "Dojo")
        val sameName = listing(other, urlB, "Dojo")
        assertEquals(Row.PAIRED, DojoBayPairedCheck.row(real, pin))
        assertEquals(Row.NOT_PAIRED, DojoBayPairedCheck.row(sameOnion, pin))
        assertEquals(Row.NOT_PAIRED, DojoBayPairedCheck.row(sameName, pin))
        // The owner's own listing for another onion is not the paired Dojo either.
        assertEquals(Row.NOT_PAIRED, DojoBayPairedCheck.row(listing(owner, urlB, "Dojo"), pin))
        // With the real one listed, the copies are just other listings.
        assertEquals(Status.Present, DojoBayPairedCheck.status(listOf(sameOnion, real, sameName), pin))
    }

    @Test
    fun `an unverified copy of the paired Dojo is neither paired nor a signer change`() {
        val pin = DojoBaySignerPin(urlA, owner.code, "Dojo")
        val copy = listing(owner, urlA, "Dojo").node.copy(signed = null)
        val checked = CheckedDojo(copy, DojoBaySignatures.checkNode(copy))
        assertEquals(Row.NOT_PAIRED, DojoBayPairedCheck.row(checked, pin))
        assertEquals(Status.NotListed, DojoBayPairedCheck.status(listOf(checked), pin))
    }

    @Test
    fun `the paired Dojo now signed by another code is SignerChanged and needs a re-pair`() {
        val pin = DojoBaySignerPin(urlA, owner.code, "Dojo")
        // Same onion, new signer.
        assertEquals(Status.SignerChanged(listOf(other.code)), DojoBayPairedCheck.status(listOf(listing(other, urlA, "Renamed")), pin))
        // Same name (any case), new signer and onion.
        assertEquals(Status.SignerChanged(listOf(other.code)), DojoBayPairedCheck.status(listOf(listing(other, urlB, " dojo ")), pin))
        // Nothing alike: not listed.
        assertEquals(Status.NotListed, DojoBayPairedCheck.status(listOf(listing(other, urlB, "Elsewhere")), pin))
        // The pairing is not touched by the check...
        commit(PendingSignerPin().apply { select(owner.code) }, urlA, "Dojo")
        DojoBayPairedCheck.status(listOf(listing(other, urlA, "Dojo")), DojoBaySignerPin.read(prefs))
        assertEquals(owner.code, prefs.dojoTrustedSignerCode)
        // ...only a new pairing through the pre-pair flow replaces the pin.
        commit(PendingSignerPin().apply { select(other.code) }, urlA, "Dojo")
        assertEquals(Status.Present, DojoBayPairedCheck.status(listOf(listing(other, urlA, "Dojo")), DojoBaySignerPin.read(prefs)))
    }

    @Test
    fun `the check reads the shown list`() {
        val pin = DojoBaySignerPin(urlA, owner.code, "Dojo")
        val fresh = CommunityDojoDisplay.Fresh(listOf(listing(other, urlA, "Dojo")), "PM8Top")
        assertEquals(Status.SignerChanged(listOf(other.code)), DojoBayPairedCheck.status(fresh, pin))
        val cached = CommunityDojoDisplay.Cached(listOf(listing(owner, urlA, "Dojo")), 1L, CommunityDojoRepository.Failure.TIMEOUT)
        assertEquals(Status.Present, DojoBayPairedCheck.status(cached, pin))
        assertEquals(Status.NoPin, DojoBayPairedCheck.status(fresh, null))
    }

    @Test
    fun `a manual pairing has no pin and clears an old one`() {
        commit(PendingSignerPin().apply { select(owner.code) }, urlA, "Dojo")
        // Scan or paste: the pending code is null.
        val manual = PendingSignerPin().apply { select(null) }
        commit(manual, urlB, null)
        assertNull(prefs.dojoTrustedSignerCode)
        assertNull(DojoBaySignerPin.read(prefs))
        assertEquals(Status.NoPin, DojoBayPairedCheck.status(listOf(listing(other, urlB, "x")), DojoBaySignerPin.read(prefs)))
    }

    @Test
    fun `a pairing made before pins existed has no pin and nothing breaks`() {
        prefs.apiEndPointTor = urlA
        prefs.dojoDisplayUrl = urlA
        prefs.dojoDisplayName = "Dojo"
        assertNull(DojoBaySignerPin.read(prefs))
        assertEquals(Status.NoPin, DojoBayPairedCheck.status(listOf(listing(other, urlA, "Dojo")), null))
        assertEquals(Row.NOT_PAIRED, DojoBayPairedCheck.row(listing(owner, urlA, "Dojo"), null))
    }

    @Test
    fun `a pin saved for another URL than the paired one is not in force`() {
        commit(PendingSignerPin().apply { select(owner.code) }, urlA, "Dojo")
        prefs.apiEndPointTor = urlB
        assertNull(DojoBaySignerPin.read(prefs))
        prefs.apiEndPointTor = null
        assertNull(DojoBaySignerPin.read(prefs))
    }

    @Test
    fun `signer changed - Review opens the changed listing, and trusting it moves the pin`() {
        commit(PendingSignerPin().apply { select(owner.code) }, urlA, "Dojo")
        val lookalikeByName = listing(other, urlB, "Dojo")
        val sameOnion = listing(other, urlA, "Renamed")
        val unrelated = listing(other, urlB, "Elsewhere")
        val shown = listOf(unrelated, lookalikeByName, sameOnion)
        val pin = DojoBaySignerPin.read(prefs)

        // The rows that get the note, and the one Review opens: same onion first.
        assertEquals(listOf(lookalikeByName, sameOnion), DojoBayPairedCheck.signerChangedRows(shown, pin))
        assertEquals(sameOnion, DojoBayPairedCheck.repairTarget(shown, pin))
        assertEquals(lookalikeByName, DojoBayPairedCheck.repairTarget(listOf(unrelated, lookalikeByName), pin))

        // Review -> the full pre-pair flow: the sheet hands the listing's
        // signed code over (onScanPayload), the user taps PAIR, it commits.
        val target = DojoBayPairedCheck.repairTarget(shown, pin)!!
        val pending = PendingSignerPin().apply { select(target.verified!!.paymentCode) }
        commit(pending, DojoBayPairedCheck.signedUrl(target)!!, target.node.name)
        assertEquals(other.code, prefs.dojoTrustedSignerCode)
        assertEquals(Status.Present, DojoBayPairedCheck.status(shown, DojoBaySignerPin.read(prefs)))
        assertEquals(emptyList<CheckedDojo>(), DojoBayPairedCheck.signerChangedRows(shown, DojoBaySignerPin.read(prefs)))
    }

    @Test
    fun `signer changed - DON'T PAIR in the re-pair flow keeps the old pin and the state`() {
        commit(PendingSignerPin().apply { select(owner.code) }, urlA, "Dojo")
        val shown = listOf(listing(other, urlA, "Dojo"))
        val pending = PendingSignerPin().apply { select(DojoBayPairedCheck.repairTarget(shown, DojoBaySignerPin.read(prefs))!!.verified!!.paymentCode) }
        pending.discard()
        assertEquals(owner.code, prefs.dojoTrustedSignerCode)
        assertTrue(DojoBayPairedCheck.status(shown, DojoBaySignerPin.read(prefs)) is Status.SignerChanged)
        // No pin, or the trusted listing still there: nothing to review.
        assertNull(DojoBayPairedCheck.repairTarget(shown, null))
        assertNull(DojoBayPairedCheck.repairTarget(shown + listing(owner, urlA, "Dojo"), DojoBaySignerPin.read(prefs)))
    }
}
