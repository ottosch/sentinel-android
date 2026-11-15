package com.samourai.sentinel.data.repository

import java.util.concurrent.ConcurrentHashMap

/**
 * Why one collection's sync did not land.
 *
 * Transport: the Dojo host never answered (socket, SOCKS, timeout).
 * Dojo: the host answered with an error, a non-2xx, or an unusable body.
 * Local: a gate miss or bookkeeping, with no Dojo verdict for that collection.
 */
internal enum class SyncFailureKind {
    TRANSPORT,
    DOJO,
    LOCAL,
}

/**
 * Pubs whose last sync failed. Send hides their kept UTXOs until that
 * pub's next successful sync clears the mark. A pairing change clears
 * every mark: the failure belonged to the Dojo that was just removed.
 */
internal object PubSyncHealth {
    private val failed = ConcurrentHashMap<String, SyncFailureKind>()

    fun markFailed(pubKey: String, kind: SyncFailureKind) {
        if (pubKey.isEmpty()) return
        failed[pubKey] = kind
    }

    fun clear(pubKey: String) {
        if (pubKey.isEmpty()) return
        failed.remove(pubKey)
    }

    fun clearAll() {
        failed.clear()
    }

    fun isHidden(pubKey: String): Boolean = pubKey.isNotEmpty() && failed.containsKey(pubKey)

    fun kindOf(pubKey: String): SyncFailureKind? = failed[pubKey]
}

internal fun combineSyncFailures(kinds: List<SyncFailureKind>): SyncFailureKind = when {
    kinds.isEmpty() -> SyncFailureKind.LOCAL
    kinds.all { it == SyncFailureKind.TRANSPORT } -> SyncFailureKind.TRANSPORT
    kinds.all { it == SyncFailureKind.DOJO } -> SyncFailureKind.DOJO
    kinds.all { it == SyncFailureKind.LOCAL } -> SyncFailureKind.LOCAL
    kinds.any { it == SyncFailureKind.DOJO } -> SyncFailureKind.DOJO
    kinds.any { it == SyncFailureKind.TRANSPORT } -> SyncFailureKind.TRANSPORT
    else -> SyncFailureKind.LOCAL
}
