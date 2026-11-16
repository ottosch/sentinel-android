package com.samourai.sentinel.ui.dojo

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.samourai.sentinel.api.okHttp.await
import com.samourai.sentinel.util.ImportTooLargeException
import com.samourai.sentinel.util.OkHttpLogRedaction
import com.samourai.sentinel.tor.SentinelTorManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Fetches the community Dojo directory (Dojo Bay) over the app's embedded Tor proxy.
 * Callers are responsible for making sure Tor is bootstrapped first
 * (see [SentinelTorManager]) - fetchDirectory() itself only reads the current proxy.
 */
object CommunityDojoRepository {

    /**
     * The directory is a few kilobytes today. A custom directory is
     * somebody else's server, so the read is capped rather than trusting
     * it not to stream until the app runs out of memory.
     */
    const val MAX_DIRECTORY_BYTES = 2 * 1024 * 1024
    const val MAX_OPERATOR_BYTES = 16 * 1024

    /**
     * The most listings one directory may have. Every listing's signature is
     * checked each time the list is shown; a directory with more is refused
     * as [Failure.BAD_RESPONSE]. The default directory lists 17.
     */
    const val MAX_LISTINGS = 256

    /** Dojo Bay probes every listed node before answering - see #135. */
    const val TIMEOUT_SECONDS = CommunityDojoFetchPolicy.NORMAL_TIMEOUT_SECONDS

    /**
     * Why a directory fetch failed. The message is fixed local text: no
     * server text, exception text, or address rides it into the UI or a
     * log line (same rule as #539 for Dojo errors).
     */
    enum class Failure(val message: String) {
        TOR_UNAVAILABLE("Tor is not connected"),
        TIMEOUT("The directory did not answer in time"),
        UNREACHABLE("The directory could not be reached"),
        HTTP_ERROR("The directory returned an error"),
        BAD_RESPONSE("The directory sent a list the app could not read"),
        TOO_LARGE("The directory sent more data than a directory should"),
        /** The list came back but data/operator.json answered 404. */
        NO_OPERATOR_STATEMENT("The directory has no signed operator statement"),
    }

    class FetchException(val failure: Failure, val httpCode: Int? = null) : IOException(failure.message)

    /**
     * One fetch: the check of the instance's own operator binding, and the
     * listings. [listFetched] is false when the operator check already made
     * a refusal certain, so the list was never requested ([nodes] empty).
     */
    data class Directory(
        val nodes: List<CommunityDojoNode>,
        val operator: DojoBaySignatures.OperatorCheck,
        val listFetched: Boolean = true,
    )

    /**
     * [expectedCode] is the operator code this address is pinned to
     * ([DojoBayTrust.expectedCode]). The caller refuses an address without
     * one before calling (no request at all).
     */
    suspend fun fetchDirectory(
        baseUrl: String = DojoBayConstants.BASE_URL,
        expectedCode: String? = null,
        maxWaitMs: Long = 120_000L,
        timeoutSeconds: Long = TIMEOUT_SECONDS,
    ): Directory = withContext(Dispatchers.IO) {
        // Tor's STATE observer can report ON slightly before its LISTENERS
        // observer delivers the SOCKS proxy. On a cold bootstrap that window
        // surfaced as an instant "Tor is not connected" error on fresh
        // installs. Poll briefly for the proxy before giving up.
        val proxy = awaitProxy(maxWaitMs)
            ?: throw FetchException(Failure.TOR_UNAVAILABLE)
        val client = buildClient(proxy, timeoutSeconds)
        try {
            fetchBothWithin(client, baseUrl, expectedCode, timeoutSeconds * 1000)
        } finally {
            release(client)
        }
    }

    /**
     * Each attempt has its own client (its own Tor circuit, see [buildClient]),
     * so nothing should outlive the attempt: close its idle keep-alive
     * connection (and with it the Tor stream) and let its dispatcher threads
     * end, instead of keeping them for OkHttp's 5-minute keep-alive.
     */
    internal fun release(client: OkHttpClient) {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    /**
     * [fetchBoth] with one limit for the whole attempt: the two requests run
     * one after the other, and each client call has its own timeout, so
     * without this an attempt could take up to twice the limit.
     */
    internal suspend fun fetchBothWithin(
        client: OkHttpClient,
        baseUrl: String,
        expectedCode: String?,
        timeoutMs: Long,
    ): Directory = withTimeoutOrNull(timeoutMs) { fetchBoth(client, baseUrl, expectedCode) }
        ?: throw FetchException(Failure.TIMEOUT)

    /**
     * True when [check] already decides a refusal, whatever the list says:
     * the binding failed, or it verifies for a code other than [expectedCode].
     */
    internal fun refusalCertain(check: DojoBaySignatures.OperatorCheck, expectedCode: String?): Boolean =
        when (check) {
            is DojoBaySignatures.OperatorCheck.Failed -> true
            is DojoBaySignatures.OperatorCheck.Verified -> expectedCode != null && check.paymentCode != expectedCode
        }

    /**
     * data/operator.json first, then the listings, from the same instance.
     * The list is not requested when the operator check already refuses the
     * instance ([refusalCertain]), or when operator.json could not be
     * fetched (a failed fetch either way: the saved list stays up). The one
     * exception is a 404: the list is still requested (its status only; the
     * body is not parsed), so an address that
     * isn't a Dojo Bay at all (its list 404s too) reports the list's own
     * error, and a Dojo Bay that answers its list but has no operator
     * statement gets [Failure.NO_OPERATOR_STATEMENT]. operator.json is a
     * small static file, so asking for it first costs one short request on
     * the circuit the list then reuses.
     */
    internal suspend fun fetchBoth(
        client: OkHttpClient,
        baseUrl: String,
        expectedCode: String? = null,
    ): Directory {
        val check = try {
            fetchOperatorWith(client, baseUrl)
        } catch (e: FetchException) {
            if (e.failure == Failure.HTTP_ERROR && e.httpCode == 404) {
                // Only the list's status is checked: its body is not parsed,
                // since nothing from an instance without an operator
                // statement is used. Throws the list's own failure if it
                // fails too.
                fetchStatusWith(client, DojoBaySource.directoryUrl(baseUrl))
                // Dojo Bay answered, it just has no operator statement: say
                // so rather than "unreachable". Still a failed fetch, so a
                // saved list from the trusted operator stays up (no age limit).
                throw FetchException(Failure.NO_OPERATOR_STATEMENT, 404)
            }
            throw e
        }
        if (refusalCertain(check, expectedCode)) return Directory(emptyList(), check, listFetched = false)
        val nodes = fetchWith(client, DojoBaySource.directoryUrl(baseUrl))
        return Directory(nodes, check)
    }

    /**
     * data/operator.json, checked against the address it came from. Only a
     * document the instance actually serves is checked: a non-2xx answer is
     * [Failure.HTTP_ERROR] and a blank or unreadable body (not one strict
     * JSON object) is [Failure.BAD_RESPONSE], failed fetches like the
     * directory's own, so a proxy hiccup or deploy window leaves the saved
     * list up instead of refusing the instance. Transport failures are
     * classified the same way.
     */
    internal suspend fun fetchOperatorWith(client: OkHttpClient, baseUrl: String): DojoBaySignatures.OperatorCheck {
        val body = try {
            val request = Request.Builder().url(DojoBaySource.operatorUrl(baseUrl)).build()
            val response = client.newCall(request).await(maxBodyBytes = MAX_OPERATOR_BYTES)
            response.use {
                if (!it.isSuccessful) throw FetchException(Failure.HTTP_ERROR, it.code)
                // Bytes, not string(): string() would drop a byte-order mark.
                it.body?.bytes()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw classify(e)
        }
        val text = body?.let { DojoBayJson.decodeBody(it) }
        if (text.isNullOrBlank()) throw FetchException(Failure.BAD_RESPONSE)
        val tree = DojoBaySignatures.parseStrictJson(text) as? JsonObject
            ?: throw FetchException(Failure.BAD_RESPONSE)
        val doc = try {
            Gson().fromJson(tree, DojoBaySignatures.OperatorDoc::class.java)
        } catch (e: Exception) {
            throw FetchException(Failure.BAD_RESPONSE)
        }
        return DojoBaySignatures.checkOperator(doc, baseUrl)
    }

    /**
     * Every directory request goes through [proxy] - Tor - whatever the
     * address. There is no direct fallback: the only sockets the client can
     * open are [TorIsolatedSocks]' sockets, which connect to Tor's SOCKS port
     * and nowhere else. Each client (one per attempt, automatic retry and
     * manual Retry included) gets fresh SOCKS credentials, so Tor builds new
     * circuits for it instead of reusing one that is stuck.
     */
    internal fun buildClient(
        proxy: Proxy,
        timeoutSeconds: Long = TIMEOUT_SECONDS,
        isolation: TorIsolatedSocks = TorIsolatedSocks.fromProxy(proxy),
    ): OkHttpClient {
        // #304: directory client, not ApiService.buildClient.
        OkHttpLogRedaction.install()
        val builder = OkHttpClient.Builder()
            // Tor is reached by isolation.socketFactory; OkHttp must not add a proxy of its own.
            .proxy(Proxy.NO_PROXY)
            .dns(isolation.dns)
            .socketFactory(isolation.socketFactory)
            // A custom directory must not bounce the request somewhere else.
            .followRedirects(false)
            .followSslRedirects(false)
        // Dojo Bay probes every listed node over Tor before answering, so the
        // request can take a while to build a response: 120 s per attempt
        // (CommunityDojoFetchPolicy).
        builder.connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
        return builder.build()
    }

    /** One request to [url] with [client]; every failure is a [FetchException]. */
    internal suspend fun fetchWith(client: OkHttpClient, url: String): List<CommunityDojoNode> {
        val response = try {
            // Built inside the try: a URL OkHttp rejects is a failed fetch.
            val request = Request.Builder().url(url).build()
            client.newCall(request).await(maxBodyBytes = MAX_DIRECTORY_BYTES)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw classify(e)
        }
        val body = try {
            // Bytes, not string(): string() would drop a byte-order mark.
            response.use { it.body?.bytes() }
        } catch (e: Exception) {
            throw classify(e)
        }
        if (!response.isSuccessful) throw FetchException(Failure.HTTP_ERROR, response.code)
        val text = body?.let { DojoBayJson.decodeBody(it) } ?: throw FetchException(Failure.BAD_RESPONSE)
        return parseDirectory(text)
    }

    /**
     * One request to [url] with [client], for its status only: the body is
     * not parsed. A non-2xx answer is [Failure.HTTP_ERROR]; every failure is
     * a [FetchException].
     */
    internal suspend fun fetchStatusWith(client: OkHttpClient, url: String) {
        val response = try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).await(maxBodyBytes = MAX_DIRECTORY_BYTES)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw classify(e)
        }
        response.use {
            if (!it.isSuccessful) throw FetchException(Failure.HTTP_ERROR, it.code)
        }
    }

    /**
     * Parses a directory body with the strict reader ([DojoBayJson]): blank,
     * not strict JSON, nested deeper than [DojoBayJson.NESTING_LIMIT], not a
     * directory object, or more than [MAX_LISTINGS] listings is
     * [Failure.BAD_RESPONSE]. The count is checked before any listing's
     * signature is.
     */
    fun parseDirectory(body: String?): List<CommunityDojoNode> {
        if (body.isNullOrBlank()) throw FetchException(Failure.BAD_RESPONSE)
        val directory = DojoBayJson.parse(body, CommunityDojoDirectory::class.java)
            ?: throw FetchException(Failure.BAD_RESPONSE)
        val nodes = directory.nodes.orEmpty()
        if (nodes.size > MAX_LISTINGS) throw FetchException(Failure.BAD_RESPONSE)
        return nodes.filterNotNull()
    }

    /**
     * Maps a transport exception to a [Failure]. Timeout-shaped failures
     * (OkHttp's callTimeout is an InterruptedIOException("timeout"), a slow
     * read is a SocketTimeoutException) are [Failure.TIMEOUT]; Tor refusing
     * the stream (unknown onion, service down, nothing listening behind it,
     * Tor's own SOCKS timeout "TTL expired") and every other connect failure
     * is [Failure.UNREACHABLE]. Both get the one automatic retry
     * ([CommunityDojoFetchPolicy.shouldRetry]).
     */
    internal fun classify(e: Throwable): FetchException = when {
        e is FetchException -> e
        e is ImportTooLargeException -> FetchException(Failure.TOO_LARGE)
        e is SocketTimeoutException -> FetchException(Failure.TIMEOUT)
        e is InterruptedIOException -> FetchException(Failure.TIMEOUT)
        e.message?.contains("timeout", ignoreCase = true) == true ||
            e.message?.contains("timed out", ignoreCase = true) == true -> FetchException(Failure.TIMEOUT)
        else -> FetchException(Failure.UNREACHABLE)
    }

    /**
     * Polls briefly for the Tor SOCKS proxy with a bounded wait. STATE can
     * report ON before LISTENERS delivers the proxy object. Returns null
     * if the proxy never appears within [maxWaitMs] - caller reports it.
     */
    private suspend fun awaitProxy(maxWaitMs: Long): Proxy? {
        var waited = 0L
        var proxy = SentinelTorManager.getProxy()
        while (proxy == null && waited < maxWaitMs) {
            delay(250)
            waited += 250
            proxy = SentinelTorManager.getProxy()
        }
        return proxy
    }
}
