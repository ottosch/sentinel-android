package com.samourai.sentinel.ui.home

/**
 * Change detection for collectionSignature.
 *
 * Diffing two signatures allows us to find what collections have changed
 * and fetch only the delta instead of the whole wallet.
 */
object SyncRoundDiff {

    /**
     * Parses a signature into ordered (collectionId -> key set) entries.
     * Null if null/blank input.
     */
    fun parse(signature: String?): Map<String, Set<String>>? {
        if (signature.isNullOrBlank()) return null
        val result = LinkedHashMap<String, Set<String>>()
        signature.split('|').forEach { segment ->
            val id = segment.substringBefore(':')
            if (id.isBlank()) return null
            val keys = if (segment.contains(':')) {
                segment.substringAfter(':', "").split(',')
                    .filter { it.isNotBlank() }.toSet()
            } else emptySet()
            result[id] = keys
        }
        return result
    }

    /**
     * Collection ids whose content changed between the last stamped
     * signature and the current one.
     *
     * Deletions are never reported: the diff walks only the
     * current ids. A removed id has nothing to fetch (fetchFromServer
     * is read-only; the delete flow removes the tx rows itself). An empty
     * result therefore means a delete/reorder-only round - the caller
     * (launchSyncRound) skips it and leaves the sync state standing.
     */
    fun changedCollectionIds(
        lastSignature: String?,
        currentSignature: String
    ): Set<String>? {
        val last = parse(lastSignature) ?: return null
        val current = parse(currentSignature)
            ?: return null // unparseable current
        val changed = mutableSetOf<String>()
        current.forEach { (id, keys) ->
            when (val prior = last[id]) {
                null -> changed.add(id)                       // added
                else -> if (prior != keys) changed.add(id)    // keys edited
            }
        }
        return changed
    }
}
