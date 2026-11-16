package com.samourai.sentinel.ui.dojo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingDisplaySnapshotTest {

    private val picked = DojoDisplaySnapshot("http://a.onion/test/v2", "Picked", "🇸🇬", "SG", "testnet")
    private val saved = DojoDisplaySnapshot("http://b.onion/v2", "Saved", null, null, "mainnet")

    @Test
    fun `the dialog shows the picked listing, not what is saved`() {
        val p = PendingDisplaySnapshot()
        p.select(picked)
        assertEquals(picked, p.forDialog { saved })
    }

    @Test
    fun `DON'T PAIR saves nothing`() {
        val p = PendingDisplaySnapshot()
        p.select(picked)
        p.discard()
        assertNull(p.takeForCommit())
        assertEquals(saved, p.forDialog { saved })
    }

    @Test
    fun `a committed pairing saves the picked snapshot once`() {
        val p = PendingDisplaySnapshot()
        p.select(picked)
        assertEquals(picked, p.takeForCommit())
        assertNull(p.takeForCommit())
    }

    @Test
    fun `a scan or paste after picking a listing carries no snapshot`() {
        val p = PendingDisplaySnapshot()
        p.select(picked)
        p.select(null)
        assertNull(p.takeForCommit())
        assertEquals(saved, p.forDialog { saved })
    }
}
