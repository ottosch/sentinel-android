package com.samourai.sentinel.ui.webview

import com.samourai.sentinel.api.TlsPolicy
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.util.OkHttpLogRedaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.NoRouteToHostException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reachability of one bound explorer. Tor only: the route is decided
 * before any client exists, and the client is built from the proxy
 * [SentinelTorManager.getProxy] returns at call time. A previous
 * session's port is not reused. The call is cancellable, so a timeout
 * or a newer Tor session closes the socket instead of leaving it
 * blocked on a dead SOCKS port.
 *
 * Per call: 45s overall, 30s connect, 20s read and write, no connection
 * retry. The coordinator wraps the whole probe in a 60s budget. Success
 * is any 2xx from the tip-height path or, if that path is not served,
 * the explorer root. A timeout or any other non-answer is unconfirmed.
 * Connection refused, SOCKS host unreachable, and a non-2xx on every
 * path are unreachable.
 *
 * #520: the coordinator launches from a main-dispatched scope, so the
 * resume after the OkHttp callback lands on the main thread; [fetch]
 * hops to IO before touching the response.
 */
object ExplorerHealthProbe {
    const val CALL_TIMEOUT_SEC = 45L
    const val CONNECT_TIMEOUT_SEC = 30L
    const val READ_TIMEOUT_SEC = 20L
    const val WRITE_TIMEOUT_SEC = 20L

    suspend fun probe(baseUrl: String, endpointAtSchedule: String): ProbeResult =
        runProbe(baseUrl, endpointAtSchedule)

    internal suspend fun runProbe(
        baseUrl: String,
        endpointAtSchedule: String,
        torReady: () -> Boolean = { SentinelTorManager.isTorReady() },
        currentProxy: () -> Proxy? = { SentinelTorManager.getProxy() },
        currentEndpoint: () -> String? = { SentinelTorManager.socksEndpoint() },
        openClient: (Proxy) -> OkHttpClient = { client(it) },
        fetchUrl: suspend (OkHttpClient, String) -> UrlFetch = { http, url -> fetch(http, url) },
    ): ProbeResult {
        val proxy = currentProxy()
        if (explorerProbeRoute(torReady(), proxy != null) != ExplorerProbeRoute.TOR) {
            return ProbeResult.NotReady
        }
        val liveProxy = proxy ?: return ProbeResult.NotReady
        val endpoint = currentEndpoint()
        if (endpoint == null || endpoint != endpointAtSchedule) {
            return ProbeResult.NotReady
        }
        ExplorerProbeLog.start(baseUrl, endpoint)
        val urls = ExplorerBinding.healthRequestUrls(baseUrl)
        if (urls.isEmpty()) return ProbeResult.unreachable("empty")
        val http = openClient(liveProxy)
        try {
            val seen = mutableListOf<UrlFetch>()
            for (url in urls) {
                if (!currentCoroutineContext().isActive) throw CancellationException()
                if (currentEndpoint() != endpoint || !torReady()) return ProbeResult.NotReady
                val started = System.nanoTime()
                val fetched = fetchUrl(http, url)
                ExplorerProbeLog.url(url, fetched.label, elapsedMs(started))
                seen += fetched
                if (fetched.successful) return probeResult(seen)
            }
            return probeResult(seen)
        } finally {
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }

    fun client(proxy: Proxy): OkHttpClient {
        OkHttpLogRedaction.install()
        val builder = OkHttpClient.Builder()
            .proxy(proxy)
            .retryOnConnectionFailure(false)
        TlsPolicy.install(builder)
        builder.connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        builder.readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        builder.writeTimeout(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
        builder.callTimeout(CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
        return builder.build()
    }

    internal suspend fun fetch(client: OkHttpClient, url: String): UrlFetch {
        val call = try {
            client.newCall(Request.Builder().url(url).build())
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            return failureFetch(e)
        }
        return try {
            // #520: the probe coroutine is launched from a main-dispatched
            // scope (the Network screen's lifecycleScope), so the resume
            // after the OkHttp callback lands on the main thread. use{}
            // closes the response there, and closing a body that is not
            // buffered yet drains it from the socket - Android throws
            // NetworkOnMainThreadException and the catch below would
            // launder it into a failed fetch. Read and close on IO.
            withContext(Dispatchers.IO) {
                call.executeCancellable().use { response ->
                    UrlFetch(successful = response.isSuccessful, label = response.code.toString())
                }
            }
        } catch (c: CancellationException) {
            call.cancel()
            throw c
        } catch (e: Exception) {
            failureFetch(e)
        }
    }

    private fun elapsedMs(startedNs: Long): Long =
        ((System.nanoTime() - startedNs).coerceAtLeast(0L)) / 1_000_000L
}

/**
 * One finished attempt. [detail] is an HTTP status or a token from
 * [probeFailureLabel], never a message, a URL, or a query string.
 */
data class ProbeResult(
    val kind: Kind,
    val detail: String,
) {
    enum class Kind {
        REACHABLE,
        UNREACHABLE,
        UNCONFIRMED,
        NOT_READY,
    }

    companion object {
        val Reachable = ProbeResult(Kind.REACHABLE, "200")
        val Unreachable = ProbeResult(Kind.UNREACHABLE, "")
        val Unconfirmed = ProbeResult(Kind.UNCONFIRMED, "")
        val NotReady = ProbeResult(Kind.NOT_READY, "")

        fun reachable(detail: String) = ProbeResult(Kind.REACHABLE, detail)
        fun unreachable(detail: String) = ProbeResult(Kind.UNREACHABLE, detail)
        fun unconfirmed(detail: String) = ProbeResult(Kind.UNCONFIRMED, detail)
    }
}

internal enum class FetchVerdict {
    REACHABLE,
    /** Connection refused, SOCKS host unreachable, or a non-2xx status. */
    DEFINITIVE,
    /** Timeout, no response, or any other failure that is not proof. */
    NO_RESPONSE,
}

internal data class UrlFetch(
    val successful: Boolean,
    val label: String,
    val verdict: FetchVerdict = if (successful) FetchVerdict.REACHABLE else FetchVerdict.DEFINITIVE,
)

internal fun probeResult(seen: List<UrlFetch>): ProbeResult {
    val detail = seen.lastOrNull { it.successful }?.label
        ?: seen.lastOrNull()?.label
        ?: ""
    return when (classifyFetches(seen.map { it.verdict })) {
        ProbeResult.Kind.REACHABLE -> ProbeResult.reachable(detail)
        ProbeResult.Kind.UNREACHABLE -> ProbeResult.unreachable(detail)
        ProbeResult.Kind.UNCONFIRMED -> ProbeResult.unconfirmed(detail)
        ProbeResult.Kind.NOT_READY -> ProbeResult.NotReady
    }
}

/**
 * Any 2xx wins. Every completed attempt has to be a definitive
 * failure before the row goes red. One timeout keeps it amber,
 * including when another path already returned a non-2xx.
 */
internal fun classifyFetches(verdicts: List<FetchVerdict>): ProbeResult.Kind {
    if (verdicts.any { it == FetchVerdict.REACHABLE }) return ProbeResult.Kind.REACHABLE
    if (verdicts.isNotEmpty() && verdicts.all { it == FetchVerdict.DEFINITIVE }) {
        return ProbeResult.Kind.UNREACHABLE
    }
    return ProbeResult.Kind.UNCONFIRMED
}

/**
 * Red only for a refused connection or a host the proxy reports
 * unreachable. OkHttp wraps a connect timeout as "Failed to connect"
 * with no refusal under it; that stays amber.
 */
internal fun failureVerdict(error: Throwable): FetchVerdict {
    val chain = generateSequence(error) { it.cause }.take(8)
    if (chain.any { isDefinitiveTransport(it) }) return FetchVerdict.DEFINITIVE
    return FetchVerdict.NO_RESPONSE
}

internal fun isDefinitiveTransport(error: Throwable): Boolean {
    if (error is NoRouteToHostException) return true
    val msg = error.message?.lowercase().orEmpty()
    return msg.contains("connection refused") || msg.contains("host unreachable")
}

/** Fixed token from [probeFailureLabel]. Never the message or the runtime class name. */
private fun failureFetch(error: Throwable): UrlFetch = UrlFetch(
    successful = false,
    label = probeFailureLabel(error),
    verdict = failureVerdict(error),
)

internal suspend fun Call.executeCancellable(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, _, _ ->
                response.close()
            }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (cont.isCancelled) return
            cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation {
        cancel()
    }
}
