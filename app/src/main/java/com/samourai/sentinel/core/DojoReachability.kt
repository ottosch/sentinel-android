package com.samourai.sentinel.core

import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Last contact with the paired Dojo, for the whole process.
 *
 * Written from the Dojo call path. Any HTTP response means the host
 * answered: a 2xx without an error envelope is [DojoContact.Reachable],
 * and a non-2xx, an error envelope, or an auth failure is
 * [DojoContact.DojoError]. An [IOException] (including SOCKS "TTL
 * expired" and a timeout) is [DojoContact.Unreachable]. Pairing
 * changes and Tor stopping reset this to [DojoContact.Unknown].
 *
 * The transport label is a short kind. The exception message is not
 * stored: it can carry the request, and the request can carry the onion.
 */
internal sealed class DojoContact {
    data object Unknown : DojoContact()
    data class Reachable(val atMillis: Long) : DojoContact()
    data class DojoError(val atMillis: Long, val code: Int?, val kind: String) : DojoContact()
    data class Unreachable(val atMillis: Long, val transport: String) : DojoContact()
}

internal object DojoReachability {
    @Volatile
    var current: DojoContact = DojoContact.Unknown
        private set

    private val listeners = CopyOnWriteArrayList<(DojoContact) -> Unit>()

    fun subscribe(listener: (DojoContact) -> Unit) {
        listeners.add(listener)
        listener(current)
    }

    fun unsubscribe(listener: (DojoContact) -> Unit) {
        listeners.remove(listener)
    }

    fun reset() {
        publish(DojoContact.Unknown)
    }

    fun recordHttp(code: Int, errorEnvelope: Boolean, now: Long = System.currentTimeMillis()) {
        val authFailure = code == 401 || code == 403
        val contact = if (code in 200..299 && !errorEnvelope && !authFailure) {
            DojoContact.Reachable(now)
        } else {
            val kind = when {
                authFailure -> "auth"
                errorEnvelope -> "envelope"
                else -> "http"
            }
            DojoContact.DojoError(now, code, kind)
        }
        publish(contact)
    }

    fun recordTransport(error: IOException, now: Long = System.currentTimeMillis()) {
        if (isCanceledTransport(error)) return
        publish(DojoContact.Unreachable(now, transportKind(error)))
    }

    private fun publish(next: DojoContact) {
        current = next
        listeners.forEach { listener -> listener(next) }
    }
}

/**
 * True when the first bytes of a Dojo body are an error envelope.
 * Only the head is inspected, so a later field cannot flip a real wallet.
 */
internal fun dojoErrorEnvelope(bodyPrefix: String): Boolean {
    val head = bodyPrefix.trimStart().take(80)
    if (!head.startsWith("{")) return false
    return head.contains("\"status\":\"error\"") || head.contains("\"status\": \"error\"")
}

internal fun transportKind(error: IOException): String = when {
    error is SocketTimeoutException -> "timeout"
    error is SocketException && isSocksFailure(error) -> "socks"
    else -> "io"
}

internal fun isCanceledTransport(error: IOException): Boolean =
    error.message == "Canceled"

private fun isSocksFailure(error: SocketException): Boolean {
    val message = error.message ?: return false
    return message.contains("SOCKS") || message.contains("TTL expired")
}
