package com.samourai.sentinel.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.ui.utils.PREFS_OMITTED_FROM_BACKUP
import com.samourai.sentinel.ui.utils.Preferences
import com.samourai.sentinel.ui.utils.PrefsUtil
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.reflect.KProperty

/**
 * The paired Dojo's own version, read from its X-Dojo-Version header: one
 * stored value, replaced when the Dojo reports a different one, untouched
 * when the answer is the same, failed or has no usable header, and dropped
 * when it comes from before a pairing change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DojoReportedVersionTest {

    private lateinit var prefs: PrefsUtil
    private var writes = 0
    private val countWrites = object : Preferences.SharedPrefsListener {
        override fun onSharedPrefChanged(property: KProperty<*>) {
            if (property.name == DojoReportedVersion.KEY) writes++
        }
    }

    @Before
    fun setUp() {
        prefs = PrefsUtil(ApplicationProvider.getApplicationContext<Context>())
        prefs.clearAll()
        DojoReportedVersion.clear(prefs)
        prefs.addListener(countWrites)
        writes = 0
    }

    @After
    fun tearDown() {
        prefs.removeListener(countWrites)
        DojoReportedVersion.clear(prefs)
    }

    private fun observe(header: String?, successful: Boolean = true): Boolean =
        DojoReportedVersion.observe(prefs, DojoReportedVersion.pairingGeneration(), successful, header)

    @Test
    fun `parse keeps a version-shaped header exactly as sent`() {
        for (good in listOf(
            "1.29.0", "v1.29.0", "V1.30.0+build.5", "1.29.0-rc1", "1", "a", "abc", "1.2.3+-.",
            "1" + ".0".repeat(15) + "a", // 32 characters
            "x".repeat(32),
        )) {
            assertEquals(good, good, DojoReportedVersion.parse(good))
        }
        assertEquals(32, ("1" + ".0".repeat(15) + "a").length)
        // Shape-valid but host-like: shown as text, and it goes nowhere else
        // (DojoVersionDisplayOnlyTest).
        val hostLike = "a".repeat(16) + ".onion"
        assertEquals(hostLike, DojoReportedVersion.parse(hostLike))
    }

    @Test
    fun `parse refuses anything outside the shape, with nothing trimmed or rewritten`() {
        val bad = listOf(
            null, "",
            "x".repeat(33), "1" + ".0".repeat(16), // 33 characters
            "1.29 0", " 1.29.0", "1.29.0 ", " 1.29.0 ", "\t1.29.0", "1.29.0\t",
            "1.29/0", "1.29/../0", "/", "1.29.0;", "1.29.0,1.30.0", "<b>1.29</b>",
            "1.29.0\n", "1.29.0\r\nX-Evil: 1", "\n1.29.0", "1.29.0\u0000",
            "\u0661.29.0", "１.２９.０", "1.29.\u0966", "1.29.0é", "1.29.0ß", "\u0391", "1.29\u202E0",
            "http://" + "a".repeat(56) + ".onion/v2", "127.0.0.1:80",
            """{"pairing":{"url":"http://x"}}""", "x".repeat(10 * 1024),
        )
        for (raw in bad) assertNull(raw.toString().take(40), DojoReportedVersion.parse(raw))
    }

    @Test
    fun `headerOf takes the one value and refuses none or several`() {
        val h = DojoReportedVersion.HEADER
        assertEquals("1.29.0", DojoReportedVersion.headerOf(okhttp3.Headers.headersOf(h, "1.29.0")))
        assertEquals("1.29.0", DojoReportedVersion.headerOf(okhttp3.Headers.headersOf(h.lowercase(), "1.29.0")))
        assertNull(DojoReportedVersion.headerOf(okhttp3.Headers.headersOf()))
        assertNull(DojoReportedVersion.headerOf(okhttp3.Headers.headersOf(h, "1.29.0", h, "1.30.0")))
        assertNull(DojoReportedVersion.headerOf(okhttp3.Headers.headersOf(h, "1.29.0", h, "1.29.0")))
    }

    @Test
    fun `next writes only a different good version from a good answer`() {
        assertEquals("1.29.0", DojoReportedVersion.next(null, true, "1.29.0"))
        assertEquals("1.30.0", DojoReportedVersion.next("1.29.0", true, "1.30.0"))
        assertNull(DojoReportedVersion.next("1.29.0", true, "1.29.0"))
        assertEquals("v1.29.0", DojoReportedVersion.next("1.29.0", true, "v1.29.0"))
        assertNull(DojoReportedVersion.next("1.29.0", true, " 1.30.0"))
        assertNull(DojoReportedVersion.next("1.29.0", true, "x".repeat(33)))
        assertNull(DojoReportedVersion.next("1.29.0", false, "1.30.0"))
        assertNull(DojoReportedVersion.next("1.29.0", true, null))
        assertNull(DojoReportedVersion.next("1.29.0", true, "<b>1.30</b>"))
    }

    @Test
    fun `every answer is read, with no daily wait`() {
        assertTrue(observe("1.29.0"))
        assertTrue(observe("1.30.0"))
        assertTrue(observe("1.29.1"))
        assertEquals("1.29.1", prefs.dojoReportedVersion)
        assertEquals(3, writes)
    }

    @Test
    fun `the same version writes nothing`() {
        observe("1.29.0")
        writes = 0
        assertFalse(observe("1.29.0"))
        assertEquals(0, writes)
        assertEquals("1.29.0", prefs.dojoReportedVersion)
    }

    @Test
    fun `a failed call, no header or a bad header keeps the stored value`() {
        observe("1.29.0")
        writes = 0
        assertFalse(observe("1.30.0", successful = false))
        assertFalse(observe(null))
        assertFalse(observe(""))
        assertFalse(observe("not a version"))
        assertFalse(observe("1.30.0\u0000"))
        assertFalse(observe(" 1.30.0"))
        assertFalse(observe("1.30.0\r\nX-Evil: 1"))
        assertFalse(observe("x".repeat(33)))
        assertFalse(observe("x".repeat(10 * 1024)))
        assertEquals("1.29.0", prefs.dojoReportedVersion)
        assertEquals(0, writes)
    }

    @Test
    fun `an answer sent before a pairing change is dropped`() {
        val sent = DojoReportedVersion.pairingGeneration()
        DojoReportedVersion.clear(prefs)
        assertFalse(DojoReportedVersion.observe(prefs, sent, true, "1.0.0"))
        assertNull(prefs.dojoReportedVersion)

        val sentBeforeLogin = DojoReportedVersion.pairingGeneration()
        DojoReportedVersion.setFromPairingLogin(prefs, "1.29.0")
        assertFalse(DojoReportedVersion.observe(prefs, sentBeforeLogin, true, "1.0.0"))
        assertEquals("1.29.0", prefs.dojoReportedVersion)
    }

    @Test
    fun `the pairing login always sets the value, and no header leaves it empty`() {
        observe("1.0.0")
        DojoReportedVersion.setFromPairingLogin(prefs, "1.29.0")
        assertEquals("1.29.0", prefs.dojoReportedVersion)
        DojoReportedVersion.setFromPairingLogin(prefs, "x".repeat(33))
        assertNull(prefs.dojoReportedVersion)
        DojoReportedVersion.setFromPairingLogin(prefs, null)
        assertNull(prefs.dojoReportedVersion)
        DojoReportedVersion.setFromPairingLogin(prefs, "garbage!")
        assertNull(prefs.dojoReportedVersion)
    }

    @Test
    fun `clear removes the value`() {
        observe("1.29.0")
        val before = DojoReportedVersion.pairingGeneration()
        DojoReportedVersion.clear(prefs)
        assertNull(prefs.dojoReportedVersion)
        assertTrue(DojoReportedVersion.pairingGeneration() != before)
    }

    @Test
    fun `display prefers the Dojo's own version, then the pairing code's`() {
        assertEquals("1.29.0", DojoReportedVersion.display("1.29.0", "1.19.1"))
        assertEquals("1.19.1", DojoReportedVersion.display(null, "1.19.1"))
        assertEquals("1.19.1", DojoReportedVersion.display("", "1.19.1"))
        assertNull(DojoReportedVersion.display(null, " "))
        assertNull(DojoReportedVersion.display(null, null))
    }

    @Test
    fun `the stored version is one key and stays out of backups`() {
        assertTrue(DojoReportedVersion.KEY in PREFS_OMITTED_FROM_BACKUP)
        observe("1.29.0")
        val exported = prefs.export()
        assertFalse(exported.has(DojoReportedVersion.KEY))
        // A backup carrying the value does not restore it.
        DojoReportedVersion.clear(prefs)
        prefs.import(JSONObject().put(DojoReportedVersion.KEY, "9.9.9"))
        assertNull(prefs.dojoReportedVersion)
        // No other prefs property is named for the version.
        val names = PrefsUtil::class.members.map { it.name }.filter { it.startsWith("dojoReportedVersion") }
        assertEquals(listOf(DojoReportedVersion.KEY), names)
    }
}
