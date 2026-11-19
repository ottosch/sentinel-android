package com.samourai.sentinel.ui.dojo

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.samourai.sentinel.api.okHttp.await
import com.samourai.sentinel.util.OkHttpLogRedaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * The signer's PayNym avatar and +name for the pre-pair dialog, looked up
 * from the SIGNED code on PayNym's onion ([PayNymLinks.ONION_HOST]), over
 * the app's Tor client only. This mirrors what Dojo Bay does for its own
 * cards (scripts/update.mjs fetchAvatar, server/paynym.mjs resolvePayNym at
 * a3125f5), but Sentinel makes the calls itself, so nothing from the
 * directory's own avatar or paynym fields is used.
 *
 * - Avatar: GET /<urlencoded code>/avatar on port 80. At most one redirect,
 *   to the same host only; 200 only; PNG magic bytes; at most
 *   [MAX_AVATAR_BYTES]; [TIMEOUT_SECONDS].
 * - Name: POST /api/v1/nym {"nym":"<code>"}. The name is shown only when the
 *   answer lists the signed code among the nym's codes.
 * - No clearnet fallback; no Tor, no request. Any failure is "nothing".
 * - Successes are cached per code for [CACHE_MAX_AGE_MS].
 */
class PayNymLookup(
    private val cache: PayNymCache,
    /** Tor's SOCKS proxy, or null when Tor isn't running: then nothing is fetched. */
    private val torProxy: () -> Proxy?,
    /** A transport on a fresh Tor circuit for [Proxy]; one per lookup. */
    private val transportFor: (Proxy) -> PayNymTransport = { OkHttpPayNymTransport.overTor(it) },
) {

    data class Result(val avatarPng: ByteArray?, val name: String?) {
        override fun equals(other: Any?): Boolean =
            other is Result && name == other.name && avatarPng.contentEqualsNullable(other.avatarPng)

        override fun hashCode(): Int = (name?.hashCode() ?: 0) * 31 + (avatarPng?.contentHashCode() ?: 0)

        private fun ByteArray?.contentEqualsNullable(o: ByteArray?) =
            if (this == null) o == null else o != null && contentEquals(o)
    }

    /** Looks up [signedCode]; called when the pre-pair dialog opens. */
    suspend fun load(signedCode: String): Result {
        if (!DojoBaySignatures.isPaymentCodeShape(signedCode)) return Result(null, null)
        val cachedAvatar = cache.avatar(signedCode)
        val cachedName = cache.name(signedCode)
        if (cachedAvatar != null && cachedName != null) return Result(cachedAvatar, cachedName)
        val proxy = torProxy() ?: return Result(cachedAvatar, cachedName)
        val transport = transportFor(proxy)
        return try {
            val fetcher = PayNymFetcher(transport)
            coroutineScope {
                val avatar = async { cachedAvatar ?: fetcher.avatar(signedCode)?.also { cache.putAvatar(signedCode, it) } }
                val name = async { cachedName ?: fetcher.name(signedCode)?.also { cache.putName(signedCode, it) } }
                Result(avatar.await(), name.await())
            }
        } finally {
            transport.close()
        }
    }

    /**
     * Just the +name for [signedCode], for "View on PayNym": the cached one,
     * else asked over Tor. Null when it isn't known and can't be asked.
     */
    suspend fun name(signedCode: String): String? {
        if (!DojoBaySignatures.isPaymentCodeShape(signedCode)) return null
        cache.name(signedCode)?.let { return it }
        val proxy = torProxy() ?: return null
        val transport = transportFor(proxy)
        return try {
            PayNymFetcher(transport).name(signedCode)?.also { cache.putName(signedCode, it) }
        } finally {
            transport.close()
        }
    }

    companion object {
        const val MAX_AVATAR_BYTES = 2 * 1024 * 1024
        // The /api/v1/nym answer carries the nym's full follower and following
        // lists: +max's was 65,763 bytes in Nov 2026, just over the old 64 KiB.
        const val MAX_NYM_BYTES = 1024 * 1024
        const val TIMEOUT_SECONDS = 25L
        const val CACHE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}

/** One HTTP exchange with PayNym's onion: status, Location, body (capped). Null on any transport failure. */
data class PayNymReply(val status: Int, val location: String?, val body: ByteArray?)

/** Requests to [PayNymLinks.ONION_HOST] only; [path] is a path on that host. */
interface PayNymTransport {
    suspend fun get(path: String, maxBytes: Int): PayNymReply?
    suspend fun post(path: String, json: String, maxBytes: Int): PayNymReply?
    fun close() {}
}

/**
 * The rules, apart from the transport: redirects, status, PNG magic, size,
 * and the name's shape and code match.
 */
class PayNymFetcher(
    private val transport: PayNymTransport,
    /**
     * Where the /api/v1/nym answer (up to [PayNymLookup.MAX_NYM_BYTES]) is
     * decoded and parsed: never the caller's thread, which is the main
     * thread for the pre-pair dialog and View on PayNym.
     */
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    suspend fun avatar(code: String): ByteArray? {
        if (!DojoBaySignatures.isPaymentCodeShape(code)) return null
        var path = PayNymLinks.avatarPath(code)
        for (hop in 0..1) {
            val reply = transport.get(path, PayNymLookup.MAX_AVATAR_BYTES) ?: return null
            if (reply.status in REDIRECTS) {
                if (hop == 1) return null
                path = sameHostPath(path, reply.location) ?: return null
                continue
            }
            if (reply.status != 200) return null
            val body = reply.body ?: return null
            if (body.size > PayNymLookup.MAX_AVATAR_BYTES || !isPng(body)) return null
            return body
        }
        return null
    }

    suspend fun name(code: String): String? {
        if (!DojoBaySignatures.isPaymentCodeShape(code)) return null
        val body = JsonObject().apply { add("nym", JsonPrimitive(code)) }.toString()
        val reply = transport.post(PayNymLinks.NYM_PATH, body, PayNymLookup.MAX_NYM_BYTES) ?: return null
        if (reply.status != 200) return null
        val bytes = reply.body?.takeIf { it.size <= PayNymLookup.MAX_NYM_BYTES } ?: return null
        return withContext(parseDispatcher) {
            val text = DojoBayJson.decodeBody(bytes) ?: return@withContext null
            val doc = DojoBayJson.parseTree(text) as? JsonObject ?: return@withContext null
            nymFor(doc, code)
        }
    }

    companion object {
        private val REDIRECTS = setOf(301, 302, 307, 308)
        private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val NYM_NAME = Regex("^\\+?[A-Za-z0-9._-]{1,64}$")
        private val BASE: HttpUrl = PayNymLinks.BASE_URL.toHttpUrlOrNull()!!

        fun isPng(bytes: ByteArray): Boolean =
            bytes.size > PNG_MAGIC.size && PNG_MAGIC.indices.all { bytes[it] == PNG_MAGIC[it] }

        /** A redirect's target as a path on PayNym's onion (http, port 80), or null for anywhere else. */
        fun sameHostPath(fromPath: String, location: String?): String? {
            val loc = location?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val from = BASE.resolve(fromPath) ?: return null
            val target = from.resolve(loc) ?: return null
            if (target.scheme != "http" || target.host != PayNymLinks.ONION_HOST || target.port != 80) return null
            return target.encodedPath + (target.encodedQuery?.let { "?$it" } ?: "")
        }

        /**
         * The +name in a /api/v1/nym answer (nymName, as server/paynym.mjs
         * reads it), only when [code] is one of the nym's codes.
         */
        fun nymFor(doc: JsonObject, code: String): String? {
            val codes = doc.get("codes") as? JsonArray ?: return null
            val listed = codes.any { (it as? JsonObject)?.get("code")?.let { c -> c.isJsonPrimitive && c.asString == code } == true }
            if (!listed) return null
            val raw = listOf("nymName", "nym_name", "nym")
                .firstNotNullOfOrNull { key -> (doc.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.asString }
                ?: return null
            if (!NYM_NAME.matches(raw)) return null
            return if (raw.startsWith("+")) raw else "+$raw"
        }
    }
}

/** [PayNymTransport] over OkHttp, through Tor only ([TorIsolatedSocks]), no redirects followed. */
class OkHttpPayNymTransport(
    private val client: OkHttpClient,
    private val base: HttpUrl = PayNymLinks.BASE_URL.toHttpUrlOrNull()!!,
) : PayNymTransport {

    override suspend fun get(path: String, maxBytes: Int): PayNymReply? {
        val target = url(path) ?: return null
        return exchange(Request.Builder().url(target).get().build(), maxBytes)
    }

    override suspend fun post(path: String, json: String, maxBytes: Int): PayNymReply? {
        val target = url(path) ?: return null
        val body = json.toRequestBody("application/json".toMediaType())
        return exchange(Request.Builder().url(target).post(body).build(), maxBytes)
    }

    private fun url(path: String): HttpUrl? = base.resolve(path)?.takeIf { it.host == base.host && it.port == base.port }

    private suspend fun exchange(request: Request, maxBytes: Int): PayNymReply? = withContext(Dispatchers.IO) {
        try {
            val response = client.newCall(request).await(maxBodyBytes = maxBytes)
            response.use { PayNymReply(it.code, it.header("Location"), it.body?.bytes()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never logged: the failure is simply "no avatar" or "no name".
            null
        }
    }

    override fun close() {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    companion object {
        fun overTor(proxy: Proxy): OkHttpPayNymTransport {
            OkHttpLogRedaction.install()
            val isolation = TorIsolatedSocks.fromProxy(proxy)
            val client = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .dns(isolation.dns)
                .socketFactory(isolation.socketFactory)
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(PayNymLookup.TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(PayNymLookup.TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(PayNymLookup.TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
            return OkHttpPayNymTransport(client)
        }
    }
}

/**
 * Successful lookups, per code, for [PayNymLookup.CACHE_MAX_AGE_MS]: the
 * avatar PNG and the +name, in the app's no-backup files (deleted with the
 * rest of the app data, and by Erase wallet). Failures are not cached.
 */
class PayNymCache(private val dir: File, private val now: () -> Long = System::currentTimeMillis) {

    private fun file(code: String, ext: String): File? =
        if (DojoBaySignatures.isPaymentCodeShape(code)) File(dir, "$code.$ext") else null

    private fun fresh(f: File?): File? =
        f?.takeIf { it.isFile && now() - it.lastModified() in 0 until PayNymLookup.CACHE_MAX_AGE_MS }

    fun avatar(code: String): ByteArray? = try {
        fresh(file(code, "png"))?.readBytes()?.takeIf {
            it.size <= PayNymLookup.MAX_AVATAR_BYTES && PayNymFetcher.isPng(it)
        }
    } catch (e: Exception) {
        null
    }

    fun name(code: String): String? = try {
        fresh(file(code, "nym"))?.readText()?.takeIf { it.length <= 65 && it.startsWith("+") }
    } catch (e: Exception) {
        null
    }

    fun putAvatar(code: String, png: ByteArray) = write(file(code, "png"), png)

    fun putName(code: String, name: String) = write(file(code, "nym"), name.toByteArray())

    private fun write(f: File?, bytes: ByteArray) {
        f ?: return
        try {
            dir.mkdirs()
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
            f.setLastModified(now())
        } catch (e: Exception) {
            // A failed save only costs a lookup next time.
        }
    }

    /** Deletes every cached avatar and name (Erase wallet). */
    fun clear() {
        dir.deleteRecursively()
    }

    companion object {
        const val DIR_NAME = "paynym_cache"

        fun forDirectory(noBackupFilesDir: File) = PayNymCache(File(noBackupFilesDir, DIR_NAME))
    }
}
