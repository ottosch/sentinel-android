package com.samourai.sentinel.ui.dojo

import com.samourai.sentinel.api.okHttp.await
import com.samourai.sentinel.util.ImportTooLargeException
import com.samourai.sentinel.util.OkHttpLogRedaction
import com.samourai.sentinel.helpers.fromJSON
import com.samourai.sentinel.tor.SentinelTorManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
    }

    class FetchException(val failure: Failure, val httpCode: Int? = null) : IOException(failure.message)

    suspend fun fetchDirectory(
        baseUrl: String = DojoBayConstants.BASE_URL,
        maxWaitMs: Long = 120_000L,
        timeoutSeconds: Long = TIMEOUT_SECONDS,
    ): List<CommunityDojoNode> = withContext(Dispatchers.IO) {
        // Tor's STATE observer can report ON slightly before its LISTENERS
        // observer delivers the SOCKS proxy. On a cold bootstrap that window
        // surfaced as an instant "Tor is not connected" error on fresh
        // installs. Poll briefly for the proxy before giving up.
        val proxy = awaitProxy(maxWaitMs)
            ?: throw FetchException(Failure.TOR_UNAVAILABLE)
        val client = buildClient(proxy, timeoutSeconds)
        try {
            fetchWith(client, DojoBaySource.directoryUrl(baseUrl))
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
        // request can take a while to build a response: 120 s, or longer for
        // the first fetch after a Tor start (CommunityDojoFetchPolicy).
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
            response.body?.string()
        } catch (e: Exception) {
            throw classify(e)
        }
        if (!response.isSuccessful) throw FetchException(Failure.HTTP_ERROR, response.code)
        return parseDirectory(body)
    }

    /** Parses a directory body. Blank, non-JSON, or JSON that is not a directory object is [Failure.BAD_RESPONSE]. */
    fun parseDirectory(body: String?): List<CommunityDojoNode> {
        if (body.isNullOrBlank()) throw FetchException(Failure.BAD_RESPONSE)
        val directory = fromJSON<CommunityDojoDirectory>(body)
            ?: throw FetchException(Failure.BAD_RESPONSE)
        return directory.nodes.orEmpty().filterNotNull()
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
