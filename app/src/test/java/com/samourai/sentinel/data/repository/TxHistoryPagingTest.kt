package com.samourai.sentinel.data.repository

import com.samourai.sentinel.api.ApiService.ApiNotConfigured
import com.samourai.sentinel.api.dojoTxsQuery
import com.samourai.sentinel.data.Address
import com.samourai.sentinel.data.Tx
import com.samourai.sentinel.data.TxResponse
import com.samourai.sentinel.helpers.fromJSON
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * #197. /wallet is Dojo's newest page. These cover the walk over
 * /txs: when it must not run, when it collects the rest, and when a
 * bad page leaves the rows already in hand. Hashes are synthetic.
 * Amounts are omitted; the page shape is what is under test.
 */
class TxHistoryPagingTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a page that already covers n_tx makes no further request`() {
        val cases = listOf(
            0 to 0,
            1 to 1,
            49 to 49,
            50 to 50,
            50 to 40,
        )
        for ((size, nTx) in cases) {
            var calls = 0
            val first = List(size) { tx("w-$it") }
            val result = runBlocking {
                fetchRemainingTxHistory(first, nTx) { _, _ ->
                    calls += 1
                    error("no extra page")
                }
            }
            assertEquals("size=$size n_tx=$nTx", 0, calls)
            assertSame(first, result.txs)
            assertEquals(1, result.pagesFetched)
            assertFalse(result.pagingFailed)
            assertFalse(result.hitPageCap)
        }
    }

    @Test
    fun `a missing n_tx makes no further request`() {
        var calls = 0
        val first = List(50) { tx("w-$it") }
        val result = runBlocking {
            fetchRemainingTxHistory(first, null) { _, _ ->
                calls += 1
                error("no extra page")
            }
        }
        assertEquals(0, calls)
        assertSame(first, result.txs)
    }

    @Test
    fun `one tx past the wallet page is fetched with that page's count`() = runBlocking {
        val first = List(50) { tx("w-%02d".format(it)) }
        val calls = mutableListOf<Pair<Int, Int>>()
        val result = fetchRemainingTxHistory(first, 51) { page, count ->
            calls += page to count
            pageJson(nTx = 51, page = page, count = count, hashes = listOf("older-1"))
        }
        assertEquals(listOf(1 to 50), calls)
        assertEquals(51, result.txs.size)
        assertEquals("w-00", result.txs.first().hash)
        assertEquals("older-1", result.txs.last().hash)
        assertEquals(2, result.pagesFetched)
        assertFalse(result.pagingFailed)
        assertFalse(result.hitPageCap)
    }

    @Test
    fun `later pages are collected until n_tx and duplicates are skipped`() = runBlocking {
        val calls = mutableListOf<Pair<Int, Int>>()
        val result = fetchRemainingTxHistory(listOf(tx("a"), tx("b")), 5) { page, count ->
            calls += page to count
            val hashes = when (page) {
                1 -> listOf("b", "c")
                2 -> listOf("d")
                else -> error("page $page")
            }
            pageJson(nTx = 5, page = page, count = count, hashes = hashes)
        }
        assertEquals(listOf(1 to 2, 2 to 2), calls)
        assertEquals(listOf("a", "b", "c", "d"), result.txs.map { it.hash })
        assertFalse(result.pagingFailed)
    }

    @Test
    fun `a later page that raises n_tx does not extend the walk`() = runBlocking {
        val calls = mutableListOf<Int>()
        val result = fetchRemainingTxHistory(listOf(tx("a"), tx("b")), 3) { page, count ->
            calls += page
            val hashes = if (page == 1) listOf("c", "d") else listOf("e")
            // Page 1 reports a higher total than the wallet page did.
            pageJson(nTx = 5 + page, page = page, count = count, hashes = hashes)
        }
        assertEquals(listOf(1), calls)
        assertEquals(listOf("a", "b", "c", "d"), result.txs.map { it.hash })
        assertEquals(2, result.pagesFetched)
        assertFalse(result.pagingFailed)
        assertFalse(result.hitPageCap)
        assertFalse(result.stoppedAtKnownHistory)
    }

    @Test
    fun `a later page that lowers n_tx does not shorten the walk`() = runBlocking {
        val calls = mutableListOf<Int>()
        val result = fetchRemainingTxHistory(listOf(tx("a"), tx("b")), 5) { page, count ->
            calls += page
            val hashes = if (page == 1) listOf("c", "d") else listOf("e")
            pageJson(nTx = 2, page = page, count = count, hashes = hashes)
        }
        assertEquals(listOf(1, 2), calls)
        assertEquals(listOf("a", "b", "c", "d", "e"), result.txs.map { it.hash })
        assertFalse(result.hitPageCap)
    }

    @Test
    fun `a repeated page stops the walk`() = runBlocking {
        val calls = mutableListOf<Int>()
        val result = fetchRemainingTxHistory(listOf(tx("a"), tx("b")), 8) { page, count ->
            calls += page
            pageJson(nTx = 8, page = page, count = count, hashes = listOf("b"))
        }
        assertEquals(listOf(1), calls)
        assertEquals(listOf("a", "b"), result.txs.map { it.hash })
        assertEquals(2, result.pagesFetched)
        assertFalse(result.pagingFailed)
    }

    @Test
    fun `an empty page stops the walk`() = runBlocking {
        val calls = mutableListOf<Int>()
        val result = fetchRemainingTxHistory(listOf(tx("a")), 6) { page, count ->
            calls += page
            pageJson(nTx = 6, page = page, count = count, hashes = emptyList())
        }
        assertEquals(listOf(1), calls)
        assertEquals(listOf("a"), result.txs.map { it.hash })
        assertFalse(result.pagingFailed)
    }

    @Test
    fun `an empty wallet page starts at txs page 0`() = runBlocking {
        val calls = mutableListOf<Pair<Int, Int>>()
        val result = fetchRemainingTxHistory(emptyList(), 1) { page, count ->
            calls += page to count
            pageJson(nTx = 1, page = page, count = count, hashes = listOf("only"))
        }
        assertEquals(listOf(0 to DOJO_DEFAULT_TX_PAGE), calls)
        assertEquals(listOf("only"), result.txs.map { it.hash })
        assertEquals(2, result.pagesFetched)
        assertFalse(result.pagingFailed)
    }

    @Test
    fun `a failed later page keeps the wallet page and the pages already read`() = runBlocking {
        val calls = mutableListOf<Int>()
        val result = fetchRemainingTxHistory(listOf(tx("a")), 5) { page, count ->
            calls += page
            if (page == 1) {
                pageJson(nTx = 5, page = page, count = count, hashes = listOf("b"))
            } else {
                throw IOException("timeout")
            }
        }
        assertEquals(listOf(1, 2), calls)
        assertEquals(listOf("a", "b"), result.txs.map { it.hash })
        assertTrue(result.pagingFailed)
        assertEquals(2, result.pagesFetched)
        assertFalse(result.hitPageCap)
    }

    @Test
    fun `a null page body keeps the wallet page`() = runBlocking {
        val result = fetchRemainingTxHistory(listOf(tx("a"), tx("b")), 4) { _, _ -> null }
        assertEquals(listOf("a", "b"), result.txs.map { it.hash })
        assertTrue(result.pagingFailed)
        assertEquals(1, result.pagesFetched)
    }

    @Test
    fun `an error object keeps the wallet page`() = runBlocking {
        val result = fetchRemainingTxHistory(listOf(tx("a"), tx("b")), 4) { _, _ ->
            """{"status":"error","error":"denied"}"""
        }
        assertEquals(listOf("a", "b"), result.txs.map { it.hash })
        assertTrue(result.pagingFailed)
        assertEquals(1, result.pagesFetched)
    }

    @Test
    fun `api not configured during paging keeps the wallet page`() = runBlocking {
        val result = fetchRemainingTxHistory(listOf(tx("a")), 3) { _, _ ->
            throw ApiNotConfigured()
        }
        assertEquals(listOf("a"), result.txs.map { it.hash })
        assertTrue(result.pagingFailed)
    }

    @Test
    fun `cancellation leaves the page loop`() {
        try {
            runBlocking {
                fetchRemainingTxHistory(listOf(tx("a")), 3) { _, _ ->
                    throw CancellationException("stop")
                }
            }
            fail("expected cancellation")
        } catch (e: CancellationException) {
            assertEquals("stop", e.message)
        }
    }

    @Test
    fun `the page cap stops a server that never runs out of rows`() = runBlocking {
        var calls = 0
        val result = fetchRemainingTxHistory(listOf(tx("w")), 10_000) { page, count ->
            calls += 1
            assertEquals(calls, page)
            assertEquals(1, count)
            pageJson(nTx = 10_000, page = page, count = count, hashes = listOf("x$calls"))
        }
        assertEquals(TX_HISTORY_MAX_PAGES - 1, calls)
        assertEquals(TX_HISTORY_MAX_PAGES, result.txs.size)
        assertEquals("w", result.txs.first().hash)
        assertEquals("x${TX_HISTORY_MAX_PAGES - 1}", result.txs.last().hash)
        assertTrue(result.hitPageCap)
        assertFalse(result.pagingFailed)
    }

    @Test
    fun `the row cap is the page budget times the default page`() {
        assertEquals(10_000, TX_HISTORY_MAX_ROWS)
        assertEquals(TX_HISTORY_MAX_PAGES * DOJO_DEFAULT_TX_PAGE, TX_HISTORY_MAX_ROWS)
    }

    @Test
    fun `a hostile server that raises n_tx with unique hashes stops at the row cap`() =
        runBlocking {
            val first = List(100) { tx("w$it") }
            var calls = 0
            var reported = 1_000_000
            val result = fetchRemainingTxHistory(first, reported) { page, count ->
                calls += 1
                assertEquals(calls, page)
                assertEquals(100, count)
                reported += 5_000
                val hashes = List(count) { "p${page}_$it" }
                pageJson(nTx = reported, page = page, count = count, hashes = hashes)
            }
            assertEquals(99, calls)
            assertEquals(TX_HISTORY_MAX_ROWS, result.txs.size)
            assertEquals("w0", result.txs.first().hash)
            assertTrue(result.hitPageCap)
            assertFalse(result.pagingFailed)
            assertFalse(result.stoppedAtKnownHistory)
            assertTrue(
                txHistoryLogLine(
                    pubIndex = 0,
                    nTx = 1_000_000,
                    fetched = result.txs.size,
                    pagesFetched = result.pagesFetched,
                    pagingFailed = result.pagingFailed,
                    hitPageCap = result.hitPageCap,
                ).endsWith(" paging=capped"),
            )
        }

    @Test
    fun `one fat page cannot carry a pub past the row cap`() = runBlocking {
        val first = List(6_000) { tx("w$it") }
        var calls = 0
        val result = fetchRemainingTxHistory(first, 100_000) { page, count ->
            calls += 1
            assertEquals(6_000, count)
            pageJson(
                nTx = 100_000 + page,
                page = page,
                count = count,
                hashes = List(6_000) { "x$page$it" },
            )
        }
        assertEquals(1, calls)
        assertEquals(TX_HISTORY_MAX_ROWS, result.txs.size)
        assertTrue(result.hitPageCap)
        assertFalse(result.pagingFailed)
    }

    @Test
    fun `a wallet page that is already stored makes no further request`() = runBlocking {
        val first = listOf(tx("aa"), tx("bb"))
        var calls = 0
        val result = fetchRemainingTxHistory(
            first,
            500,
            setOf("aa-collection-1", "BB-collection"),
        ) { _, _ ->
            calls += 1
            error("no extra page")
        }
        assertEquals(0, calls)
        assertSame(first, result.txs)
        assertTrue(result.stoppedAtKnownHistory)
        assertFalse(result.hitPageCap)
        assertEquals(1, result.pagesFetched)
    }

    @Test
    fun `paging stops when a page adds no txid that is not already stored`() = runBlocking {
        val calls = mutableListOf<Int>()
        val known = setOf("aa", "bb", "cc")
        val result = fetchRemainingTxHistory(
            listOf(tx("aa"), tx("dd")),
            20,
            known,
        ) { page, count ->
            calls += page
            val hashes = when (page) {
                1 -> listOf("bb", "cc")
                else -> listOf("ee")
            }
            pageJson(nTx = 20, page = page, count = count, hashes = hashes)
        }
        assertEquals(listOf(1), calls)
        assertEquals(listOf("aa", "dd", "bb", "cc"), result.txs.map { it.hash })
        assertTrue(result.stoppedAtKnownHistory)
        assertFalse(result.pagingFailed)
    }

    @Test
    fun `a repeated page keeps the stored tail when this pub already has rows`() = runBlocking {
        val calls = mutableListOf<Int>()
        val result = fetchRemainingTxHistory(
            listOf(tx("aa"), tx("zz")),
            12,
            setOf("aa", "bb"),
        ) { page, count ->
            calls += page
            pageJson(nTx = 12, page = page, count = count, hashes = listOf("zz"))
        }
        assertEquals(listOf(1), calls)
        assertEquals(listOf("aa", "zz"), result.txs.map { it.hash })
        assertTrue(result.stoppedAtKnownHistory)
        val fetch = result
        assertEquals(
            listOf("aa", "zz", "bb"),
            txsForReconcile(result.txs, listOf(tx("aa", 80_000L), tx("bb", 80_001L)), fetch).map { it.hash },
        )
    }

    @Test
    fun `nothing stored still walks until n_tx`() = runBlocking {
        val calls = mutableListOf<Int>()
        val result = fetchRemainingTxHistory(
            listOf(tx("aa"), tx("bb")),
            4,
            emptySet(),
        ) { page, count ->
            calls += page
            pageJson(nTx = 4, page = page, count = count, hashes = listOf("cc", "dd"))
        }
        assertEquals(listOf(1), calls)
        assertEquals(listOf("aa", "bb", "cc", "dd"), result.txs.map { it.hash })
        assertFalse(result.stoppedAtKnownHistory)
        assertFalse(result.hitPageCap)
    }

    @Test
    fun `the first sync after enabling still walks when only the newest page is stored`() {
        val stored = (0 until 50).map { "w$it" }.toSet()
        val page = stored.map { tx(it) }
        assertTrue(knownTxidsForPaging(stored, page, 4_000).isEmpty())
        assertTrue(knownTxidsForPaging(emptySet(), page, 4_000).isEmpty())
    }

    @Test
    fun `a short previous walk does not count as caught up`() {
        val stored = (0 until 200).map { "w$it" }.toSet()
        val page = (0 until 50).map { tx("w$it") }
        assertTrue(knownTxidsForPaging(stored, page, 4_000).isEmpty())
    }

    @Test
    fun `caught up history stops on the stored overlap`() {
        val stored = (0 until 400).map { "w$it" }.toSet()
        val page = (0 until 50).map { tx("w$it") }
        assertEquals(stored, knownTxidsForPaging(stored, page, 400))
        val withOneNew = listOf(tx("new")) + page.drop(1)
        assertEquals(stored, knownTxidsForPaging(stored, withOneNew, 401))
    }

    @Test
    fun `the row cap counts as caught up so later syncs do not rewalk it`() {
        val stored = (0 until TX_HISTORY_MAX_ROWS).map { "w$it" }.toSet()
        val page = (0 until 50).map { tx("w$it") }
        assertEquals(stored, knownTxidsForPaging(stored, page, 80_000))
    }

    @Test
    fun `an incremental stop keeps stored txs the sync did not refetch`() {
        val fetched = listOf(tx("new1"), tx("recent"))
        val stored = listOf(tx("recent", 90_000L), tx("old1", 80_000L), tx("old2", 70_000L))
        val fetch = TxHistoryFetch(
            txs = fetched,
            pagesFetched = 1,
            pagingFailed = false,
            hitPageCap = false,
            stoppedAtKnownHistory = true,
        )
        assertTrue(shouldPreserveUnfetched(fetch))
        assertEquals(
            listOf("new1", "recent", "old1", "old2"),
            txsForReconcile(fetched, stored, fetch).map { it.hash },
        )
    }

    @Test
    fun `a refetched tx is not kept twice when the stored hash has a suffix`() {
        val fetched = listOf(tx("abcd-col"))
        val stored = listOf(tx("ABCD-col-1", 80_000L), tx("eeee-col", 70_000L))
        val fetch = TxHistoryFetch(
            txs = fetched,
            pagesFetched = 2,
            pagingFailed = true,
            hitPageCap = false,
        )
        assertEquals(
            listOf("abcd-col", "eeee-col"),
            txsForReconcile(fetched, stored, fetch).map { it.hash },
        )
    }

    @Test
    fun `an incremental stop drops a stored unconfirmed tx and keeps a confirmed one`() {
        val fetched = listOf(tx("recent"))
        val stored = listOf(
            tx("confirmed-old", 80_000L),
            tx("replaced"),
            tx("dropped", 0L),
        )
        val fetch = TxHistoryFetch(
            txs = fetched,
            pagesFetched = 1,
            pagingFailed = false,
            hitPageCap = false,
            stoppedAtKnownHistory = true,
        )
        assertEquals(
            listOf("recent", "confirmed-old"),
            txsForReconcile(fetched, stored, fetch).map { it.hash },
        )
    }

    @Test
    fun `a complete empty round does not keep stored txs`() {
        val fetch = TxHistoryFetch(
            txs = emptyList(),
            pagesFetched = 1,
            pagingFailed = false,
            hitPageCap = false,
        )
        assertFalse(shouldPreserveUnfetched(fetch))
        assertTrue(txsForReconcile(emptyList(), listOf(tx("old")), fetch).isEmpty())
    }

    @Test
    fun `toggle off keeps the older confirmed tail and drops unconfirmed misses`() = runBlocking {
        val fetched = listOf(tx("recent").also { it.confirmations = 3 })
        val stored = listOf(
            tx("recent", 90_000L),
            tx("confirmed-old", 80_000L).also { it.confirmations = 1 },
            tx("replaced"),
            tx("dropped", 0L),
        )
        var counts = 0
        var rowReads = 0
        val merged = txsForToggleOff(
            fetched = fetched,
            walletPageSize = fetched.size,
            nTx = 80,
            latestBlockHeight = 90_000L,
            readCount = { counts++; 0 },
            readRows = { rowReads++; stored },
        )
        assertEquals(0, counts)
        assertEquals(1, rowReads)
        assertEquals(listOf("recent", "confirmed-old"), merged.map { it.hash })
        assertEquals(3L, merged.first { it.hash == "recent" }.confirmations)
        assertEquals(10_001L, merged.first { it.hash == "confirmed-old" }.confirmations)
    }

    @Test
    fun `toggle off keeps a confirmed tail when stored rows extend past a full page`() = runBlocking {
        val fetched = listOf(tx("recent"))
        var counts = 0
        var rowReads = 0
        val merged = txsForToggleOff(
            fetched = fetched,
            walletPageSize = 1,
            nTx = 1,
            latestBlockHeight = 90_000L,
            readCount = { counts++; 3 },
            readRows = {
                rowReads++
                listOf(tx("recent", 90_000L), tx("old", 80_000L), tx("mempool"))
            },
        )
        assertEquals(1, counts)
        assertEquals(1, rowReads)
        assertEquals(listOf("recent", "old"), merged.map { it.hash })
        assertEquals(10_001L, merged.first { it.hash == "old" }.confirmations)
    }

    @Test
    fun `a page that covers n_tx does not read rows when nothing is stored past it`() = runBlocking {
        val fetched = listOf(tx("aa"), tx("bb"))
        var rowReads = 0
        val merged = txsForToggleOff(
            fetched = fetched,
            walletPageSize = fetched.size,
            nTx = fetched.size,
            latestBlockHeight = 10L,
            readCount = { fetched.size },
            readRows = { rowReads++; listOf(tx("gone", 5L)) },
        )
        assertEquals(0, rowReads)
        assertEquals(listOf("aa", "bb"), merged.map { it.hash })
    }

    @Test
    fun `an empty wallet still wipes`() = runBlocking {
        var counts = 0
        var rowReads = 0
        val merged = txsForToggleOff(
            fetched = emptyList(),
            walletPageSize = 0,
            nTx = 0,
            latestBlockHeight = 90_000L,
            readCount = { counts++; 40 },
            readRows = { rowReads++; listOf(tx("old", 80_000L), tx("mempool")) },
        )
        assertEquals(0, counts)
        assertEquals(0, rowReads)
        assertTrue(merged.isEmpty())
        assertEquals(ToggleOffStoredRead.None, toggleOffStoredRead(0, 0, 40))
        val fetch = TxHistoryFetch(
            txs = emptyList(),
            pagesFetched = 1,
            pagingFailed = false,
            hitPageCap = false,
        )
        assertFalse(shouldPreserveUnfetched(fetch))
        assertTrue(txsForReconcile(emptyList(), listOf(tx("old", 80_000L)), fetch).isEmpty())
    }

    @Test
    fun `stored rows are read only when the page is short or the pub has more`() {
        assertEquals(ToggleOffStoredRead.Rows, toggleOffStoredRead(80, 50, null))
        assertEquals(ToggleOffStoredRead.Count, toggleOffStoredRead(50, 50, null))
        assertEquals(ToggleOffStoredRead.None, toggleOffStoredRead(50, 50, 50))
        assertEquals(ToggleOffStoredRead.Rows, toggleOffStoredRead(50, 50, 51))
        assertEquals(ToggleOffStoredRead.Count, toggleOffStoredRead(null, 50, null))
        assertEquals(ToggleOffStoredRead.Rows, toggleOffStoredRead(null, 50, 51))
        assertEquals(ToggleOffStoredRead.None, toggleOffStoredRead(null, 50, 10))
        assertEquals(ToggleOffStoredRead.None, toggleOffStoredRead(0, 0, null))
    }

    @Test
    fun `a finished walk drops stored txs the server no longer returns`() {
        val fetched = listOf(tx("aa"), tx("bb"))
        val fetch = TxHistoryFetch(
            txs = fetched,
            pagesFetched = 2,
            pagingFailed = false,
            hitPageCap = false,
        )
        assertFalse(shouldPreserveUnfetched(fetch))
        assertEquals(
            listOf("aa", "bb"),
            txsForReconcile(fetched, listOf(tx("aa"), tx("gone")), fetch).map { it.hash },
        )
    }

    @Test
    fun `dojo txs json parses without a top level block height`() {
        val parsed = fromJSON<TxResponse>(pageJson(51, 1, 50, listOf("older-1")))
        assertEquals(51, parsed!!.n_tx)
        assertEquals(1, parsed.page)
        assertEquals(50, parsed.n_tx_page)
        assertEquals(listOf("older-1"), parsed.txs!!.map { it.hash })
        assertNull(parsed.block_height)
    }

    @Test
    fun `dojo json pages are read over http`() = runBlocking {
        server.enqueue(MockResponse().setBody(pageJson(3, 1, 1, listOf("p1"))))
        server.enqueue(MockResponse().setBody(pageJson(3, 2, 1, listOf("p2"))))
        val client = httpClient()
        try {
            val seen = mutableListOf<String>()
            val result = fetchRemainingTxHistory(listOf(tx("p0")), 3) { page, count ->
                val path = dojoTxsQuery("acct", page, count)
                seen += path
                val response = client.newCall(
                    Request.Builder().url(serverBase() + path).build()
                ).execute()
                response.use { if (it.isSuccessful) it.body?.string() else null }
            }
            assertEquals(
                listOf(
                    "/txs?active=acct&page=1&count=1",
                    "/txs?active=acct&page=2&count=1",
                ),
                seen,
            )
            assertEquals(seen, listOf(server.takeRequest().path, server.takeRequest().path))
            assertEquals(2, server.requestCount)
            assertEquals(listOf("p0", "p1", "p2"), result.txs.map { it.hash })
            assertFalse(result.pagingFailed)
            assertEquals(3, result.pagesFetched)
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `an http error keeps the wallet page`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("nope"))
        val client = httpClient()
        try {
            val result = fetchRemainingTxHistory(listOf(tx("p0")), 4) { page, count ->
                val response = client.newCall(
                    Request.Builder().url(serverBase() + dojoTxsQuery("acct", page, count)).build()
                ).execute()
                response.use { if (it.isSuccessful) it.body?.string() else null }
            }
            assertEquals("/txs?active=acct&page=1&count=1", server.takeRequest().path)
            assertEquals(1, server.requestCount)
            assertEquals(listOf("p0"), result.txs.map { it.hash })
            assertTrue(result.pagingFailed)
            assertEquals(1, result.pagesFetched)
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `n_tx comes from the active pub`() {
        val rows = listOf(
            Address(address = "other", nTx = 99),
            Address(address = "acct", nTx = 12),
        )
        assertEquals(12, nTxForActivePub(rows, "acct"))
        assertEquals(7, nTxForActivePub(listOf(Address(address = "echoed", nTx = 7)), "acct"))
        assertNull(nTxForActivePub(rows, "missing"))
        assertNull(nTxForActivePub(emptyList(), "acct"))
        assertNull(nTxForActivePub(null, "acct"))
        assertNull(nTxForActivePub(listOf(Address(address = "acct", nTx = null)), "acct"))
    }

    @Test
    fun `the sync log line is n_tx fetched and pages`() {
        assertEquals(
            "fetchFromServer: pub=2 n_tx=73 fetched=73 pages=2",
            txHistoryLogLine(2, 73, 73, 2, pagingFailed = false, hitPageCap = false),
        )
        assertEquals(
            "fetchFromServer: pub=0 n_tx=none fetched=4 pages=1",
            txHistoryLogLine(0, null, 4, 1, pagingFailed = false, hitPageCap = false),
        )
        assertEquals(
            "fetchFromServer: pub=1 n_tx=80 fetched=50 pages=1 paging=failed",
            txHistoryLogLine(1, 80, 50, 1, pagingFailed = true, hitPageCap = false),
        )
        assertEquals(
            "fetchFromServer: pub=1 n_tx=10000 fetched=200 pages=200 paging=capped",
            txHistoryLogLine(1, 10_000, 200, 200, pagingFailed = false, hitPageCap = true),
        )
    }

    @Test
    fun `the txs query is active page and count`() {
        assertEquals("/txs?active=acct&page=0&count=50", dojoTxsQuery("acct", 0, 50))
        assertEquals("/txs?active=acct&page=2&count=50", dojoTxsQuery("acct", 2, 50))
    }

    @Test
    fun `fetchFromServer feeds paged txs through the existing mangle path`() {
        val repo = source(
            "src/main/java/com/samourai/sentinel/data/repository/TransactionsRepository.kt",
            "app/src/main/java/com/samourai/sentinel/data/repository/TransactionsRepository.kt",
        )
        val api = source(
            "src/main/java/com/samourai/sentinel/api/ApiService.kt",
            "app/src/main/java/com/samourai/sentinel/api/ApiService.kt",
        )
        val start = repo.indexOf("suspend fun fetchFromServer(collectionId: String)")
        val end = repo.indexOf("private fun keepTransactionWithVariousPubkeys")
        assertTrue(start >= 0 && end > start)
        val fn = repo.substring(start, end)
        assertTrue(fn.contains("val autoLabels = prefsUtil.whirlpoolAutoLabels == true"))
        // #425 sibling pin: the entropy capture must NOT be gated on
        // the auto-labels toggle - the 87d0958a coupling stranded
        // toggle-off installs with zero rows. Keyed on the kill
        // switch only.
        val gateAt = fn.indexOf("val entropyTxids =")
        assertTrue("entropyTxids declaration not found in fetchFromServer", gateAt >= 0)
        val gate = fn.substring(gateAt, gateAt + 200)
        assertTrue(
            "entropy gate must test ENTROPY_FEATURE_ENABLED",
            gate.contains("if (ENTROPY_FEATURE_ENABLED)"),
        )
        assertFalse(
            "entropy gate must not test the auto-labels toggle (#425)",
            gate.contains("autoLabels"),
        )
        assertTrue(fn.contains("if (!autoLabels)"))
        assertTrue(fn.contains("fetchRemainingTxHistory("))
        assertTrue(fn.contains("getHashesForPub("))
        assertTrue(fn.contains("shouldPreserveUnfetched(history)"))
        assertTrue(fn.contains("txsForReconcile("))
        assertTrue(fn.contains("txsForToggleOff("))
        assertTrue(fn.contains("countForPub("))
        assertTrue(fn.contains("refreshKeptConfirmations("))
        assertTrue(fn.contains("apiService.getTxs("))
        assertTrue(fn.contains("txHistoryLogLine("))
        assertTrue(fn.contains("history.txs.map"))
        assertFalse(fn.contains("response.txs.map"))
        val offAt = fn.indexOf("if (!autoLabels)")
        val elseAt = fn.indexOf("} else {", offAt)
        assertTrue(offAt >= 0 && elseAt > offAt)
        val offBranch = fn.substring(offAt, elseAt)
        assertFalse(offBranch.contains("getTxs("))
        assertFalse(offBranch.contains("getHashesForPub("))
        assertFalse(offBranch.contains("fetchRemainingTxHistory("))
        assertFalse(offBranch.contains("getAllForPub("))
        assertFalse(offBranch.contains("countForPub("))
        assertFalse(offBranch.contains("txsForToggleOff("))
        val toggleAt = fn.indexOf("txsForToggleOff(")
        val saveAt = fn.indexOf("newTransactions.addAll(forPub)")
        assertTrue(toggleAt > elseAt && saveAt > toggleAt)
        val toggleCall = fn.substring(toggleAt, saveAt)
        assertFalse(toggleCall.contains("getTxs("))
        assertFalse(toggleCall.contains("fetchRemainingTxHistory("))
        val paging = source(
            "src/main/java/com/samourai/sentinel/data/repository/TxHistoryPaging.kt",
            "app/src/main/java/com/samourai/sentinel/data/repository/TxHistoryPaging.kt",
        )
        val toggleFnAt = paging.indexOf("internal suspend fun txsForToggleOff")
        val logAt = paging.indexOf("internal fun txHistoryLogLine")
        assertTrue(toggleFnAt >= 0 && logAt > toggleFnAt)
        val toggleFn = paging.substring(toggleFnAt, logAt)
        assertTrue(toggleFn.contains("mergeUnfetchedTxs("))
        assertTrue(toggleFn.contains("refreshKeptConfirmations("))
        assertFalse(toggleFn.contains("getTxs("))
        assertFalse(toggleFn.contains("fetchRemainingTxHistory("))
        val mapAt = fn.indexOf("history.txs.map")
        assertTrue(mapAt >= 0 && saveAt > mapAt)
        assertTrue(api.contains("suspend fun getTxs("))
        assertTrue(api.contains("dojoTxsQuery(pubKey, page, count)"))
        val utxoAt = repo.indexOf("suspend fun fetchUTXOS")
        assertTrue(utxoAt > end)
        val utxo = repo.substring(utxoAt)
        assertFalse(utxo.contains("getTxs("))
        assertFalse(utxo.contains("fetchRemainingTxHistory"))
    }

    private fun tx(hash: String, blockHeight: Long? = null) = Tx(
        hash = hash,
        time = 1L,
        version = 1,
        locktime = 0,
        result = null,
        inputs = emptyList(),
        out = emptyList(),
        block_height = blockHeight,
    )

    /** Dojo GET /txs body. No block_height, no amounts. */
    private fun pageJson(nTx: Int, page: Int, count: Int, hashes: List<String>): String {
        val txs = hashes.joinToString(",") { hash ->
            """{"hash":"$hash","time":1,"version":1,"locktime":0,"inputs":[],"out":[]}"""
        }
        return """{"n_tx":$nTx,"page":$page,"n_tx_page":$count,"txs":[$txs]}"""
    }

    private fun serverBase(): String = server.url("/").toString().removeSuffix("/")

    private fun httpClient(): OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .callTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    private fun source(vararg relative: String): String {
        val file = relative.map { File(it) }.firstOrNull { it.isFile }
            ?: error("source not found from ${File(".").absolutePath}: ${relative.toList()}")
        return file.readText()
    }
}
