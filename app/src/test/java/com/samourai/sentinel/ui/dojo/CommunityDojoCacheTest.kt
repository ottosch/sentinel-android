package com.samourai.sentinel.ui.dojo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CommunityDojoCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val nodes = listOf(
        CommunityDojoNode(id = "t1", network = "testnet", name = "Test", status = "active"),
        CommunityDojoNode(id = "m1", network = "mainnet", name = "Main", status = "inactive"),
    )

    @Test
    fun `round trip keeps nodes and time`() {
        val cache = CommunityDojoCache.forDirectory(tmp.root)
        cache.write(DojoBayConstants.BASE_URL, nodes, 1_700_000_000_000L)
        val e = cache.read(DojoBayConstants.BASE_URL)!!
        assertEquals(nodes, e.nodes)
        assertEquals(1_700_000_000_000L, e.fetchedAtMs)
    }

    @Test
    fun `a list saved for another address is not returned`() {
        val cache = CommunityDojoCache.forDirectory(tmp.root)
        cache.write("http://other.onion/", nodes, 1L)
        assertNull(cache.read(DojoBayConstants.BASE_URL))
    }

    @Test
    fun `corrupt or missing file reads as no cache`() {
        val cache = CommunityDojoCache.forDirectory(tmp.root)
        assertNull(cache.read(DojoBayConstants.BASE_URL))
        File(tmp.root, CommunityDojoCache.FILE_NAME).writeText("{not json")
        assertNull(cache.read(DojoBayConstants.BASE_URL))
        File(tmp.root, CommunityDojoCache.FILE_NAME).writeText("{}")
        assertNull(cache.read(DojoBayConstants.BASE_URL))
    }

    @Test
    fun `clear removes the file`() {
        val cache = CommunityDojoCache.forDirectory(tmp.root)
        cache.write(DojoBayConstants.BASE_URL, nodes, 1L)
        cache.clear()
        assertFalse(File(tmp.root, CommunityDojoCache.FILE_NAME).exists())
        assertNull(cache.read(DojoBayConstants.BASE_URL))
    }

    @Test
    fun `an empty list never replaces the saved one`() {
        val cache = CommunityDojoCache.forDirectory(tmp.root)
        cache.write(DojoBayConstants.BASE_URL, emptyList(), 5L)
        assertNull(cache.read(DojoBayConstants.BASE_URL))
        cache.write(DojoBayConstants.BASE_URL, nodes, 7L)
        cache.write(DojoBayConstants.BASE_URL, emptyList(), 9L)
        val e = cache.read(DojoBayConstants.BASE_URL)!!
        assertEquals(nodes, e.nodes)
        assertEquals(7L, e.fetchedAtMs)
    }
}
