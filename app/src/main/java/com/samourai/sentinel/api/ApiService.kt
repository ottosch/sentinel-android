@file:Suppress("BlockingMethodInNonBlockingContext")

package com.samourai.sentinel.api

import com.samourai.sentinel.BuildConfig
import com.samourai.sentinel.api.okHttp.await
import com.samourai.sentinel.core.DojoReachability
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.core.dojoErrorEnvelope
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.helpers.fromJSON
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.util.ImportLimits
import com.samourai.sentinel.util.MaskedHttpLogger
import com.samourai.sentinel.util.OkHttpLogRedaction
import com.samourai.sentinel.util.apiScope
import com.samourai.wallet.api.backend.beans.UnspentOutput
import com.samourai.wallet.api.backend.beans.WalletResponse
import com.samourai.wallet.util.XPUB
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import java.net.URI
import java.util.Arrays
import java.util.concurrent.TimeUnit


/**
 * sentinel-android
 *
 * @author Sarath
 */

open class ApiService {

    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java);
    private val dojoUtility: DojoUtility by inject(DojoUtility::class.java);
    private var ACCESS_TOKEN: String? = null
    private val ACCESS_TOKEN_REFRESH = 300L

    /**
     * One in-flight [refreshDojoAuth] for this service. A pull-to-refresh
     * fans out several Dojo calls; each 401 used to run its own refresh
     * and then its own login (#470).
     */
    private val refreshLock = Any()
    private var refreshInFlight: CompletableDeferred<Boolean>? = null

    /**
     * The client most recently built for this service. A request must not
     * read it back: the next build replaces it, and the replacement can
     * carry a different authenticator (#470).
     */
    @Volatile
    lateinit var client: OkHttpClient

    /**
     * Runs after a request has captured its client and before
     * [OkHttpClient.newCall]. The Dojo login interleaving test pauses
     * here. Null in production.
     */
    @Volatile
    internal var beforeNewCall: ((OkHttpClient, Request) -> Unit)? = null
    private val  JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val HARDENED = 2147483648

    /** See [rescanPubKey] - a cold import on a busy Dojo can run for minutes. */
    private val RESCAN_TIMEOUT_MINUTES = 10L


    init {
        try {
            buildClient()
        } catch (_: Exception) {
        } catch (e: ApiNotConfigured) {
            Timber.e(e)
        }
    }


    private fun buildClient(
        excludeApiKey: Boolean = false,
        excludeAuthenticator: Boolean = false,
    ): OkHttpClient {
        val built = buildClient(
            excludeApiKey,
            getAPIUrl(),
            this,
            prefsUtil.authorization,
            excludeAuthenticator
        )
        client = built
        return built
    }

    /**
     * [captured] is the client this request just built. The hook runs
     * before [OkHttpClient.newCall] so a concurrent build can replace
     * [client] in between; this call still uses [captured].
     */
    private suspend fun callOn(
        captured: OkHttpClient,
        request: Request,
        maxBodyBytes: Int? = null,
    ): Response {
        beforeNewCall?.invoke(captured, request)
        val dojoCall = isDojoCall(request)
        return try {
            val response = captured.newCall(request).await(maxBodyBytes = maxBodyBytes)
            if (dojoCall) noteDojoResponse(response)
            response
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            if (dojoCall) DojoReachability.recordTransport(e)
            throw e
        }
    }

    /**
     * Exchange calls share [callOn]. Only a request aimed at the
     * configured Dojo host updates reachability.
     */
    private fun isDojoCall(request: Request): Boolean {
        val configured = runCatching { getAPIUrl() }.getOrNull() ?: return false
        return TokenAuthenticator.isDojoTarget(configured, request.url)
    }

    private fun noteDojoResponse(response: Response) {
        val envelope = try {
            dojoErrorEnvelope(response.peekBody(DOJO_STATUS_PEEK_BYTES).string())
        } catch (_: Exception) {
            false
        }
        DojoReachability.recordHttp(response.code, envelope)
    }


    fun authenticateDojo(): Job {
        // #85: this launch used to wrap any auth failure in a raw
        // Throwable(e.message) and rethrow it - an unhandled IO-coroutine
        // failure that killed the process (erase-all-data stops Tor while
        // the endpoints are still set: "Malformed reply from SOCKS
        // server"). The failure still reaches callers through the
        // returned Job - WebSocketHandler's invokeOnCompletion drives its
        // bounded reconnect backoff from it - so the CEH below only exists
        // to keep the exception away from the process-killing default
        // handler. Removing the catch-and-wrap also un-corrupts
        // cancellation: a CancellationException now completes the Job as
        // cancellation instead of a synthetic failure.
        val failureHandler = CoroutineExceptionHandler { _, t ->
            Timber.e(t, "Dojo re-auth failed")
        }
        return apiScope.launch(failureHandler) {
            if (dojoUtility.getApiKey() != null) {
                // No catch-and-wrap: let the original exception (type and
                // stack intact) complete the Job so observers can
                // classify it; the CEH above keeps it off the process.
                val response = authenticateDojo(dojoUtility.getApiKey()!!)
                if (response.isSuccessful) {
                    val string = response.body?.string()
                    string?.let { dojoUtility.setAuthToken(it) }
                }
            }
        }
    }

    /**
     * Synchronously obtains a fresh Dojo access token. Returns true on success.
     *
     * Dojo access tokens are valid for only 15 minutes, so this is the routine
     * path for a long-running session, not an edge case.
     *
     * Tries `/auth/refresh` with the stored refresh token first, then falls back
     * to `/auth/login` with the API key. Both are attempted because the refresh
     * token has its own (longer) expiry, after which only the API key works.
     *
     * Deliberately blocking: [TokenAuthenticator] runs on an OkHttp thread and
     * must have a valid token before it can return the retried request.
     *
     * Callers that arrive while a refresh is already running wait for it.
     * They then retry with the token that attempt stored, or fail with it,
     * instead of each posting another /auth/refresh and /auth/login.
     */
    suspend fun refreshDojoAuth(): Boolean {
        val claim = claimRefreshFlight()
        if (!claim.leader) {
            return claim.flight.await()
        }
        return try {
            val result = withContext(Dispatchers.IO) { performDojoRefresh() }
            claim.flight.complete(result)
            result
        } catch (t: Throwable) {
            claim.flight.completeExceptionally(t)
            throw t
        } finally {
            synchronized(refreshLock) {
                if (refreshInFlight === claim.flight) {
                    refreshInFlight = null
                }
            }
        }
    }

    private fun claimRefreshFlight(): RefreshClaim = synchronized(refreshLock) {
        val existing = refreshInFlight
        if (existing != null) {
            RefreshClaim(leader = false, flight = existing)
        } else {
            val created = CompletableDeferred<Boolean>()
            refreshInFlight = created
            RefreshClaim(leader = true, flight = created)
        }
    }

    private data class RefreshClaim(
        val leader: Boolean,
        val flight: CompletableDeferred<Boolean>,
    )

    private suspend fun performDojoRefresh(): Boolean {
        // 1. Preferred: exchange the refresh token.
        val refreshToken = prefsUtil.refreshToken
        if (!refreshToken.isNullOrEmpty()) {
            try {
                val response = refreshDojoToken(refreshToken)
                val body = response.body?.string()
                if (response.isSuccessful && body != null && body.contains("authorizations")) {
                    dojoUtility.setAuthToken(body)
                    Timber.i("Dojo token refreshed via /auth/refresh")
                    return true
                }
                Timber.w("Dojo /auth/refresh rejected (code ${response.code}); trying API key")
            } catch (e: Exception) {
                Timber.w(e, "Dojo /auth/refresh failed; trying API key")
            }
        }

        // 2. Fallback: full login with the API key.
        val apiKey = dojoUtility.getApiKey()
        if (apiKey.isNullOrEmpty()) {
            Timber.e("Cannot re-authenticate with Dojo: no refresh token and no API key")
            return false
        }

        try {
            val response = authenticateDojo(apiKey)
            val body = response.body?.string()
            if (response.isSuccessful && body != null && body.contains("authorizations")) {
                dojoUtility.setAuthToken(body)
                Timber.i("Dojo token refreshed via /auth/login")
                return true
            }
            Timber.e("Dojo /auth/login rejected (code ${response.code})")
            return false
        } catch (e: Exception) {
            Timber.e(e, "Dojo /auth/login failed")
            return false
        }
    }

    /**
     * POST /auth/refresh - exchanges a refresh token for a new access token.
     *
     * excludeAuthenticator avoids recursing into [TokenAuthenticator] if this
     * call itself returns 401. [authenticateDojo] does the same for
     * /auth/login (#470). That login is the fallback in [refreshDojoAuth],
     * so an authenticator on it calls this method again.
     */
    private suspend fun refreshDojoToken(refreshToken: String): Response {
        val http = buildClient(excludeApiKey = true, excludeAuthenticator = true)
        val formBody = FormBody.Builder()
            .add("rt", refreshToken)
            .build()
        val request = Request.Builder()
            .post(formBody)
            .url("${getAPIUrl()}/auth/refresh")
            .build()
        return callOn(http, request)
    }


    suspend fun checkImportStatus(pubKey: String) = withContext(Dispatchers.IO) {
        val http = buildClient(excludeAuthenticator = true)
        val request = Request.Builder()
            .url("${getAPIUrl()}/xpub/${pubKey}/import/status")
            .build()
        val response = callOn(http, request)
        val status = response.body?.string()
        if (!status.isNullOrEmpty()) {
            val json = JSONObject(status)
            if (json["status"] == "ok") {
                return@withContext true
            } else {
                try {
                    return@withContext json.getJSONObject("data").getBoolean("import_in_progress")
                } catch (e: Exception) {
                    return@withContext false
                }
            }
        } else {
            return@withContext false
        }
    }

    suspend fun importXpub(pubKey: String, segwit: String): Response {
        val xpub = XPUB(pubKey)
        xpub.decode()
        var segwitValue = segwit

        if (segwit == "44" || segwit == "bip44")
            segwitValue = ""

        if ((xpub.child + HARDENED).toString().equals("2147483646"))
            segwitValue = "bip84"

        val http = buildClient(excludeAuthenticator = true)
        http.newBuilder()
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)

        val formBody = FormBody.Builder()
            .add("xpub", pubKey)
            .add("segwit", segwitValue)
            .add("type", "restore")
            .build()
        val request = Request.Builder()
            .url("${getAPIUrl()}/xpub")
            .post(formBody)
            .build()

        return callOn(http, request)
    }

    /**
     * Asks the connected Dojo to (re)import [pubKey] and scan the chain for it.
     *
     * Dojo's `POST /xpub` with `type=restore` runs restoreHdAccount(), which
     * calls importHDAccount() whether or not the node already tracks the key -
     * so this both registers a public key the Dojo has never seen and rescans
     * one it already knows. That is the fix for a third-party Dojo returning an
     * empty balance because it was never tracking the key in the first place.
     *
     * The route is behind the ordinary pairing API key (not Dojo's admin
     * profile, which gates /support/xpub/:xpub/rescan), so this works when
     * paired to someone else's node.
     *
     * The response only comes back once the Dojo has finished scanning, hence
     * the dedicated timeout - the ordinary client allows 60s, which a cold import
     * on a busy node routinely exceeds.
     */
    suspend fun rescanPubKey(pubKey: String, addressType: AddressTypes?): Response =
        withContext(Dispatchers.IO) {
            val http = buildClient(excludeAuthenticator = true)
            val rescanClient = http.newBuilder()
                .connectTimeout(120, TimeUnit.SECONDS)
                .readTimeout(RESCAN_TIMEOUT_MINUTES, TimeUnit.MINUTES)
                .callTimeout(RESCAN_TIMEOUT_MINUTES, TimeUnit.MINUTES)
                .build()

            val formBody = FormBody.Builder()
                .add("xpub", pubKey)
                .add("type", "restore")
                .apply {
                    // A loose address is tracked without a derivation scheme;
                    // sending one would have Dojo treat it as an HD account.
                    if (addressType != null && addressType != AddressTypes.ADDRESS) {
                        add("segwit", dojoSegwitParam(pubKey, addressType))
                    }
                }
                .build()

            val request = Request.Builder()
                .url("${getAPIUrl()}/xpub")
                .post(formBody)
                .build()

            callOn(rescanClient, request)
        }

    /**
     * The `segwit` form value Dojo expects for [addressType].
     *
     * This has to agree with what [importXpub] sent when the key was first
     * added: Dojo's derivationOverrideCheck() deletes and re-imports the
     * account when the scheme differs from the one it already tracks, and
     * rejects a locked account outright, so a mismatch here quietly turns a
     * rescan into a re-derivation under a different scheme.
     *
     * Dojo lowercases the value and treats anything that is not "bip49" or
     * "bip84" as BIP44, which is why BIP44 is sent as an empty string.
     */
    private fun dojoSegwitParam(pubKey: String, addressType: AddressTypes): String {
        val xpub = XPUB(pubKey)
        xpub.decode()
        // Post-mix accounts are BIP84 whatever the locally recorded type says -
        // the same special case importXpub() applies when adding the key.
        if ((xpub.child + HARDENED).toString() == "2147483646")
            return "bip84"
        return when (addressType) {
            AddressTypes.BIP84 -> "bip84"
            AddressTypes.BIP49 -> "bip49"
            else -> ""
        }
    }

    suspend fun fetchAddressForSweep(address: String): MutableList<UnspentOutput> {
        val response = getWallet(address)
        val items: WalletResponse = fromJSON<WalletResponse>(response.body!!.string())!!
        return Arrays.asList(*items.unspent_outputs)
    }

    suspend fun importAddress(pubKey: String): Response {

        val http = buildClient(excludeAuthenticator = true)
        http.newBuilder()
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)

        val formBody = FormBody.Builder()
            .add("xpub", pubKey)
            .add("type", "restore")
            .build()
        val request = Request.Builder()
            .url("${getAPIUrl()}/xpub")
            .post(formBody)
            .build()

        return callOn(http, request)
    }


    /**
     * Previous transaction as a JSON envelope whose `data` field is hex.
     *
     * Both previous-transaction readers use this method and therefore
     * the same transport:
     * - sweep, [com.samourai.sentinel.ui.tools.sweep.fetchPrevTxHexViaApi]
     * - compose, `TransactionComposer.verifyPrevTx`
     *
     * #503: [Call.await] buffers the body before either caller can look
     * at it, so the ceiling has to be applied on this read. The ceiling
     * is [ImportLimits.PREV_TX_RESPONSE_MAX_BYTES], the transaction text
     * envelope from #479 / #501. A standard transaction's hex plus the
     * JSON wrapper fits; a larger body is refused while the socket is
     * still being read.
     *
     * Timeouts and the Tor proxy stay on the client from [buildClient].
     * A 120-second `newBuilder()` here was discarded, so it
     * never applied. Using that builder would replace the Tor call
     * ceiling and the Tor socket timeouts. The body cap on this read
     * is unchanged.
     */
    suspend fun getTxHex(utxoHash: String): Response {
        val http = buildClient(excludeAuthenticator = true)
        val request = Request.Builder()
            .get()
            .url("${getAPIUrl()}/tx/$utxoHash/HEX")
            .build()
        return callOn(http, request, maxBodyBytes = ImportLimits.PREV_TX_RESPONSE_MAX_BYTES)
    }


    suspend fun authenticateDojo(apiKey: String): Response {
        // #470: TokenAuthenticator's recovery is refreshDojoAuth, and the
        // fallback there is this login. An authenticator on the login
        // client turns a rejected /auth/login into another login. Each
        // nested call is a new OkHttp chain, so the prior-response retry
        // cap never fires and each level blocks a dispatcher thread.
        // excludeApiKey: the access token on this call is the one that
        // just failed. Sending it as `at` makes the login carry a stale
        // credential. /auth/refresh already passes both flags.
        // The client is the local from this build. Reading [client] again
        // at newCall lets a wallet request install TokenAuthenticator in
        // between, and a 401 then waits on the refresh flight this login
        // is itself running.
        val http = buildClient(excludeApiKey = true, excludeAuthenticator = true)
        val formBody = FormBody.Builder()
            .add("apikey", apiKey)
            .build()
        val request = Request.Builder()
            .post(formBody)
            .url("${getAPIUrl()}/auth/login")
            .build()
        return callOn(http, request)
    }


    suspend fun getTx(txId: String): Response {
        val http = buildClient()
        val request = Request.Builder()
            .url("${getAPIUrl()}/tx/${txId}?fees=1")
            .build()
        return callOn(http, request)
    }


    suspend fun getFees(): Response {
        val http = buildClient()
        val request = Request.Builder()
            .url("${getAPIUrl()}/fees")
            .build()
        return callOn(http, request)
    }


    suspend fun getWallet(pubKey: String): Response {
        val http = buildClient()
        val request = Request.Builder()
            .url("${getAPIUrl()}/wallet?active=${pubKey}")
            .build()
        return callOn(http, request)
    }

    /**
     * One page of account history. Same client as [getWallet]: Tor
     * gate, access token, and the call/read/connect timeouts.
     * Dojo's first page is index 0.
     */
    suspend fun getTxs(pubKey: String, page: Int, count: Int): Response {
        val http = buildClient()
        val request = Request.Builder()
            .url("${getAPIUrl()}${dojoTxsQuery(pubKey, page, count)}")
            .build()
        return callOn(http, request)
    }


    suspend fun request(request: Request, excludeApiKey: Boolean = true): Response {
        // #468: request() is the third-party surface - every caller is one
        // of the five exchange providers. Their 401s must never reach the
        // Dojo TokenAuthenticator: it would burn a real Dojo re-login and
        // retry the exchange URL with `at=<Dojo access token>` appended.
        val http = buildClient(excludeApiKey = excludeApiKey, excludeAuthenticator = true)
        return callOn(http, request)
    }


    public fun getAPIUrl(): String? {
        return if (SentinelState.isTorRequired()) {
            if (prefsUtil.apiEndPointTor == null) {
                throw  ApiNotConfigured()
            }
            prefsUtil.apiEndPointTor
        } else {
            if (SentinelTorManager.getTorState().state == EnumTorState.ON) {
                return prefsUtil.apiEndPointTor
            }
            if (prefsUtil.apiEndPoint == null) {
                throw  ApiNotConfigured()
            }
            prefsUtil.apiEndPoint
        }
    }


    fun setAccessToken(accessToken: String?) {
        this.ACCESS_TOKEN = accessToken
    }

    suspend fun broadcast(hex: String): String {
        val http = buildClient()
        val formBody: RequestBody = FormBody.Builder()
            .add("tx", hex.trim())
            .build()
        val request = Request.Builder()
            .url("${getAPIUrl()}/pushtx/")
            .post(formBody)
            .build()
        val response = callOn(http, request)
        val string = response.body?.string() ?: "{}"
        val json = JSONObject(string)
        return if (response.isSuccessful) {
            if (json.has("status") && json.getString("status").equals("ok")) {
                json.getString("data")
            } else {
                "TX_ID_NOT_FOUND"
            }
        } else {
            if (json.has("status") && json.getString("status").equals("error")) {
                throw  Exception(json.getJSONObject("error").getString("message"))
            } else {
                throw InvalidResponse()
            }
        }
    }

    class ApiNotConfigured : Throwable(message = "Api endpoint is not configured")
    class InvalidResponse : Throwable(message = "Invalid response")

    /**
     * #522: a Dojo response envelope whose status is "error" - the
     * shape a 4xx/5xx body carries. Unlike [ApiNotConfigured] and
     * [InvalidResponse] this extends Exception, not bare Throwable:
     * the sync rounds' per-collection walls are catch (e: Exception)
     * (#103), and a CancellationException laundered through here is
     * what used to abort a whole round on the first failing
     * collection.
     *
     * [MESSAGE] is the only text this error carries. The server's
     * error text is not a constructor argument: a release dump keeps
     * the throwable.
     */
    class DojoApiError(message: String) : Exception(message) {
        companion object {
            const val MESSAGE = "Dojo returned an error"
        }
    }

    companion object {

        /** Head of a Dojo body, enough to see `"status":"error"` and nothing else. */
        private const val DOJO_STATUS_PEEK_BYTES = 80L

        /** Tor SOCKS handshake. A dead onion fails here, once. */
        internal const val TOR_CONNECT_SECONDS = 45L

        /** After the circuit exists, a slow Dojo may still be writing. */
        internal const val TOR_READ_SECONDS = 180L
        internal const val TOR_WRITE_SECONDS = 180L

        /**
         * The current Dojo access token, read fresh from prefs.
         *
         * [buildClient] is static and has no injected PrefsUtil, so the token is
         * resolved through Koin at call time. Returns null if prefs aren't
         * available yet, in which case the caller falls back to the token passed
         * into [buildClient].
         */
        private fun currentAuthToken(): String? = try {
            val prefs: PrefsUtil by inject(PrefsUtil::class.java)
            prefs.authorization
        } catch (e: Exception) {
            null
        }

        /**
         * #229: true when the endpoint's parsed host ends in .onion.
         * Parsed via java.net.URI - never a substring match (the old WebUtil
         * gate treated "https://example.com/onion" as an onion). Unparseable
         * or missing URL fails closed (false).
         */
        private fun isOnionEndpoint(url: String?): Boolean {
            val host = url?.let { runCatching { URI(it).host }.getOrNull() }
            return host?.lowercase()?.endsWith(".onion") == true
        }

        fun buildClient(
            excludeApiKey: Boolean = false, url: String?,
            apiService: ApiService?,
            authToken: String?,
            excludeAuthenticator: Boolean = false,
        ): OkHttpClient {
            // #304: same global logger the pool uses. Installing at
            // client build covers a process that reached a Dojo call
            // without going through Application.onCreate.
            OkHttpLogRedaction.install()
            val builder = OkHttpClient.Builder()
            // #264/#96 (Arc B): the #258 silencer is retired. The wire
            // logger returns at Level.BASIC - request and response lines
            // only; headers and bodies never log (addresses, payload
            // JSON, raw tx hex) - through MaskedHttpLogger, which rides
            // Timber's masking seam and redacts the onion host, the
            // "at" token, and the xpub in wallet URLs itself.
            if (BuildConfig.DEBUG) {
                builder.addInterceptor(HttpLoggingInterceptor(MaskedHttpLogger()).setLevel(HttpLoggingInterceptor.Level.BASIC))
            }
            builder.callTimeout(60, TimeUnit.SECONDS)
            builder.readTimeout(90, TimeUnit.SECONDS)
            builder.connectTimeout(120, TimeUnit.SECONDS)
            if (url != null && apiService != null) {
                if (!excludeAuthenticator)
                    builder.authenticator(TokenAuthenticator(apiService))
            }

            // #229/#135: if reaching this endpoint requires Tor (Tor-required
            // mode, or the endpoint itself is an onion), refuse to build a
            // direct client while Tor is not fully ready. ON alone is not
            // enough - it is published at boot=5, before the SOCKS listener
            // exists (#135), and a proxy-less client both leaks (clearnet
            // direct connection) and fails anyway (system DNS on .onion).
            // Mirrors the WebSocketHandler.connect() gate. Fails closed
            // through the existing ApiNotConfigured degrade paths (#125).
            val endpointNeedsTor = SentinelState.isTorRequired() || isOnionEndpoint(url)
            if (endpointNeedsTor && !SentinelTorManager.isTorReady()) {
                Timber.w(
                    "buildClient: endpoint needs Tor but Tor is not ready " +
                        "(state=${SentinelTorManager.getTorState().state}) - failing closed"
                )
                throw ApiNotConfigured()
            }

            if (SentinelTorManager.getTorState().state == EnumTorState.ON) {
                // A dead Dojo onion fails in the SOCKS handshake (often
                // "TTL expired") or by sitting until the connect timeout.
                // OkHttp's default connection retry repeated that wait, so
                // one collection took about two minutes. Retry is off.
                // Connect is 45s: long enough for a slow circuit that is
                // going to complete, short enough that a hung handshake
                // surfaces inside a minute. callTimeout stays 0 and the
                // read stays 180s, so a circuit that does connect is not
                // cut off while a slow Dojo writes the wallet. A 90s
                // callTimeout used to abort those healthy reads.
                builder.retryOnConnectionFailure(false)
                builder.callTimeout(0, TimeUnit.MILLISECONDS)
                builder.readTimeout(TOR_READ_SECONDS, TimeUnit.SECONDS)
                builder.writeTimeout(TOR_WRITE_SECONDS, TimeUnit.SECONDS)
                builder.connectTimeout(TOR_CONNECT_SECONDS, TimeUnit.SECONDS)
                // #229: the SOCKS proxy is only live once bootstrap completes.
                // builder.proxy(null) would have meant a DIRECT connection - a
                // de-anonymization leak for a user who asked for Tor. Fail
                // closed instead; callers already degrade on ApiNotConfigured.
                val torProxy = SentinelTorManager.getProxy()
                    ?: throw ApiNotConfigured()
                builder.proxy(torProxy)
                // #229: host-scoped TLS. Clearnet HTTPS over Tor keeps FULL
                // normal verification (an exit node cannot forge a valid
                // certificate - that is the entire fix); relaxation now applies
                // only to .onion peers, decided per connection inside the
                // policy - not per client build as before.
                TlsPolicy.install(builder)
            }

            /**
             * Intercept current request and add the Dojo access token if needed.
             *
             * IMPORTANT: the token is resolved PER REQUEST via [currentAuthToken]
             * rather than captured from the [authToken] parameter.
             *
             * Dojo access tokens expire after 15 minutes. The previous version
             * baked the token into this closure when the client was built, so after
             * a refresh every request still carried the old expired token and all
             * balances silently stopped loading.
             */
            if (!excludeApiKey) {
                try {
                    builder.addInterceptor(Interceptor { chain ->
                        val original = chain.request()
                        // Re-read on every call so a refreshed token takes effect.
                        val token = currentAuthToken() ?: authToken
                        // #468: belt and braces - this client is only built Dojo-bound
                        // today, but if the at-interceptor ever lands in one aimed
                        // elsewhere, the token must not follow the request off the
                        // configured endpoint.
                        if (!token.isNullOrEmpty() && SentinelState.isDojoEnabled() &&
                            TokenAuthenticator.isDojoTarget(url, original.url)) {
                            val url = original.url.newBuilder()
                                // Drop any stale token before adding the fresh one,
                                // otherwise a retried request carries both.
                                .removeAllQueryParameters("at")
                                .addQueryParameter("at", token)
                                .build()
                            chain.proceed(original.newBuilder().url(url).build())
                        } else {
                            chain.proceed(original)
                        }
                    })
                } catch (_:Exception) {}
            }
            return builder.build()
        }
    }


}

/**
 * Dojo GET /txs query, appended to the API base. Page 0 is the newest
 * page. [count] is the SQL limit, and the offset is page * count.
 */
internal fun dojoTxsQuery(pubKey: String, page: Int, count: Int): String =
    "/txs?active=$pubKey&page=$page&count=$count"
