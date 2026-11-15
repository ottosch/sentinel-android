package com.samourai.sentinel.ui.dojo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DojoBaySourceTest {

    private val onion = "dojobayeryasshgghz537de5ckgd5hhi4z5sdeil3roeh65fwhdnu2yd.onion"

    private fun valid(input: String) = DojoBaySource.validate(input) as DojoBaySource.Validation.Valid
    private fun reason(input: String?) = (DojoBaySource.validate(input) as DojoBaySource.Validation.Invalid).reason

    @Test
    fun `default address validates to itself`() {
        val v = valid(DojoBayConstants.BASE_URL)
        assertEquals(DojoBayConstants.BASE_URL, v.baseUrl)
        assertTrue(DojoBaySource.isDefault(v.baseUrl))
    }

    @Test
    fun `bare onion host gets http and a trailing slash`() {
        assertEquals("http://$onion/", valid(onion).baseUrl)
        assertEquals("http://$onion/", valid("HTTP://${onion.uppercase()}").baseUrl)
    }

    @Test
    fun `subpath and port are kept`() {
        assertEquals("http://$onion:8080/bay/", valid("http://$onion:8080/bay").baseUrl)
    }

    @Test
    fun `clearnet is refused, http or https`() {
        assertEquals(DojoBaySource.Reason.NOT_ONION, reason("http://dojobay.example"))
        assertEquals(DojoBaySource.Reason.NOT_ONION, reason("https://dojobay.example/"))
        assertEquals(DojoBaySource.Reason.NOT_ONION, reason("dojobay.example"))
        assertEquals(DojoBaySource.Reason.NOT_ONION, reason("http://10.0.0.5:8080/"))
        assertEquals(DojoBaySource.Reason.NOT_ONION, reason("https://$onion.example.com/"))
    }

    @Test
    fun `an onion must use http over Tor`() {
        assertEquals(DojoBaySource.Reason.NOT_HTTP, reason("https://$onion/"))
        assertEquals(DojoBaySource.Reason.NOT_HTTP, reason("ftp://$onion/"))
        assertEquals(DojoBaySource.Reason.NOT_HTTP, reason("socks5://$onion/"))
    }

    @Test
    fun `malformed and unsafe inputs are refused`() {
        assertEquals(DojoBaySource.Reason.EMPTY, reason(null))
        assertEquals(DojoBaySource.Reason.EMPTY, reason("   "))
        assertEquals(DojoBaySource.Reason.BAD_ONION, reason("http://short.onion/"))
        assertEquals(DojoBaySource.Reason.BAD_ONION, reason("http://" + "1".repeat(56) + ".onion/"))
        assertEquals(DojoBaySource.Reason.HAS_EXTRAS, reason("http://user@$onion/"))
        assertEquals(DojoBaySource.Reason.HAS_EXTRAS, reason("http://$onion/?x=1"))
        assertEquals(DojoBaySource.Reason.HAS_EXTRAS, reason("http://$onion/#frag"))
        assertEquals(DojoBaySource.Reason.MALFORMED, reason("http://$onion/data/dojos.json"))
        assertEquals(DojoBaySource.Reason.MALFORMED, reason("http:// spaced .onion/"))
    }

    @Test
    fun `a stored value that no longer validates falls back to the default`() {
        assertEquals(DojoBayConstants.BASE_URL, DojoBaySource.effectiveBaseUrl(null))
        assertEquals(DojoBayConstants.BASE_URL, DojoBaySource.effectiveBaseUrl("http://plain.example/"))
        assertEquals(DojoBayConstants.BASE_URL, DojoBaySource.effectiveBaseUrl("https://bay.example"))
        assertEquals("http://$onion/bay/", DojoBaySource.effectiveBaseUrl("$onion/bay"))
    }

    @Test
    fun `directory url sits under the root`() {
        assertEquals("http://$onion/data/dojos.json", DojoBaySource.directoryUrl(DojoBayConstants.BASE_URL))
        assertEquals("http://$onion/bay/data/dojos.json", DojoBaySource.directoryUrl("http://$onion/bay/"))
    }

    @Test
    fun `label never shows the whole onion`() {
        val label = DojoBaySource.label(DojoBayConstants.BASE_URL)
        assertEquals("dojobay…u2yd.onion", label)
        assertFalse(label.contains(onion))
    }

    @Test
    fun `onion label must be a real v3 address - version byte and checksum`() {
        assertTrue(DojoBaySource.isV3OnionLabel(onion.removeSuffix(".onion")))
        assertTrue(DojoBaySource.isV3OnionLabel("yfua6lm3x5rjc6v76gltnahdaf5mcgpn3z5p3pognswi4cpmmwanueqd"))
        // One-character typos and made-up labels of the right shape.
        assertEquals(DojoBaySource.Reason.BAD_ONION, reason("http://dojobayeryasshgghz537de5ckgd5hhi4z5sdeil3roeh65fwhdnu2ya.onion/"))
        assertEquals(DojoBaySource.Reason.BAD_ONION, reason("http://eojobayeryasshgghz537de5ckgd5hhi4z5sdeil3roeh65fwhdnu2yd.onion/"))
        assertEquals(DojoBaySource.Reason.BAD_ONION, reason("http://" + "a".repeat(56) + ".onion/"))
        assertEquals(DojoBaySource.Reason.BAD_ONION, reason("http://" + "a".repeat(16) + ".onion/"))
        // A subdomain in front of a valid label is fine.
        assertEquals("http://www.$onion/", valid("http://www.$onion/").baseUrl)
    }

    @Test
    fun `port must be 1 to 65535 and the default port is dropped`() {
        assertEquals(DojoBaySource.Reason.BAD_PORT, reason("http://$onion:0/"))
        assertEquals(DojoBaySource.Reason.BAD_PORT, reason("http://$onion:65536/"))
        assertEquals(DojoBaySource.Reason.BAD_PORT, reason("http://$onion:99999/"))
        assertEquals("http://$onion:1/", valid("http://$onion:1/").baseUrl)
        assertEquals("http://$onion:65535/", valid("http://$onion:65535/").baseUrl)
        assertEquals("http://$onion/", valid("http://$onion:80/").baseUrl)
        assertTrue(DojoBaySource.isDefault(valid("http://$onion:80/").baseUrl))
    }
}
