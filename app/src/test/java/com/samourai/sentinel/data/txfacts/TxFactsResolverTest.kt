package com.samourai.sentinel.data.txfacts

import org.junit.Assert.assertEquals
import org.junit.Test

class TxFactsResolverTest {

    private val selfOnion = "http://synthetic-self-explorer.onion"
    private val txid = "AB".repeat(16)

    @Test
    fun `absent or blank explorer is public`() {
        assertEquals(TxFactsResolver.Destination.PUBLIC, TxFactsResolver.classify(null))
        assertEquals(TxFactsResolver.Destination.PUBLIC, TxFactsResolver.classify(""))
        assertEquals(TxFactsResolver.Destination.PUBLIC, TxFactsResolver.classify("   "))
    }

    @Test
    fun `only an onion host is the dojo explorer - sr23`() {
        // SR-23: a pairing payload that publishes a clearnet URL
        // publishes a third party. The old classify() saw any
        // non-mempool host as SELF, so a community Dojo could bind a
        // clearnet explorer as the paired explorer with no advisory.
        assertEquals(
            TxFactsResolver.Destination.PUBLIC,
            TxFactsResolver.classify("https://mempool.space"),
        )
        assertEquals(
            TxFactsResolver.Destination.PUBLIC,
            TxFactsResolver.classify("https://mempool.space/testnet"),
        )
        assertEquals(
            TxFactsResolver.Destination.PUBLIC,
            TxFactsResolver.classify("https://my-explorer.example"),
        )
        assertEquals(
            TxFactsResolver.Destination.PUBLIC,
            TxFactsResolver.classify("https://my-explorer.example/tx"),
        )
        assertEquals(
            TxFactsResolver.Destination.SELF,
            TxFactsResolver.classify("http://" + TxFactsResolver.MEMPOOL_ONION_HOST),
        )
        assertEquals(
            TxFactsResolver.Destination.SELF,
            TxFactsResolver.classify(selfOnion),
        )
    }

    @Test
    fun `userinfo and path tricks are not onion hosts`() {
        // The authority's host decides, never the userinfo or a
        // path segment. `x.onion@evil.example` and a clearnet host
        // with `.onion` in the path are third parties.
        assertEquals(
            TxFactsResolver.Destination.PUBLIC,
            TxFactsResolver.classify("http://x.onion@evil.example"),
        )
        assertEquals(
            TxFactsResolver.Destination.PUBLIC,
            TxFactsResolver.classify("https://evil.example/onion.onion/tx"),
        )
        assertEquals(
            TxFactsResolver.Destination.PUBLIC,
            TxFactsResolver.classify("https://evil.example/onion.onion"),
        )
        // Percent-decoding and IDN mapping run before the .onion test.
        assertEquals(
            TxFactsResolver.Destination.SELF,
            TxFactsResolver.classify("http://x.oni%6fn"),
        )
        assertEquals(
            TxFactsResolver.Destination.SELF,
            TxFactsResolver.classify("http://\uFF58.\uFF4F\uFF4E\uFF49\uFF4F\uFF4E"),
        )
        assertEquals(
            TxFactsResolver.Destination.SELF,
            TxFactsResolver.classify("https://x.onion\u3002/tx"),
        )
        assertEquals(
            TxFactsResolver.Destination.PUBLIC,
            TxFactsResolver.classify("https://evil.example/x.oni%6fn"),
        )
    }

    @Test
    fun `declared sibling onion is self`() {
        assertEquals(TxFactsResolver.Destination.SELF, TxFactsResolver.classify(selfOnion))
    }

    @Test
    fun `garbage declared url is public not crash`() {
        // Red on the first gate run of this branch: classify() had no
        // usable-host leg, so "::::" fell into SELF. The fix pins it
        // PUBLIC - the documented safe default.
        assertEquals(TxFactsResolver.Destination.PUBLIC, TxFactsResolver.classify("::::"))
    }

    @Test
    fun `schemeless declared url is public not self`() {
        // java.net.URI parses a schemeless string as a path - host is
        // null, so it can never be a usable SELF destination.
        assertEquals(
            TxFactsResolver.Destination.PUBLIC,
            TxFactsResolver.classify("synthetic-self-explorer.onion"),
        )
    }

    @Test
    fun `self facts url joins api tx against declared base`() {
        val r = TxFactsResolver.factsUrl(txid, selfOnion + "/", testnet = false)
        assertEquals(TxFactsResolver.Destination.SELF, r.destination)
        assertEquals("$selfOnion/api/tx/$txid", r.url)
    }

    @Test
    fun `public facts url uses clearnet base - the onion serves the page not the api`() {
        assertEquals(
            "https://mempool.space/api/tx/$txid",
            TxFactsResolver.factsUrl(txid, null, testnet = false).url,
        )
        assertEquals(
            "https://mempool.space/testnet/api/tx/$txid",
            TxFactsResolver.factsUrl(txid, null, testnet = true).url,
        )
    }
}
