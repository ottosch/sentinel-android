package com.samourai.sentinel.ui.dojo

import com.google.gson.annotations.SerializedName
import com.samourai.sentinel.helpers.fromJSON
import com.samourai.sentinel.helpers.toJSON
import java.io.File

/**
 * The last Community Dojos directory the app fetched successfully, kept on
 * disk so the list (and each Dojo's pairing details) is still there when
 * Dojo Bay can't be reached.
 *
 * - One entry, for one directory address. Changing the address drops it, so
 *   a list is never shown under a directory it didn't come from.
 * - The whole directory is kept (every network). The sheet filters by the
 *   current network at display time, so a network switch needs no refetch
 *   and shows only that network's Dojos.
 * - Stored in the app's no-backup files directory, and deleted by Erase
 *   wallet along with the rest of the app data.
 * - Only directory data is stored: the same public entries Dojo Bay
 *   publishes, with their signed blocks, so every listing is re-checked
 *   when the saved list is shown. Nothing about the user's own pairing or
 *   wallet.
 * - Written only after the directory's operator binding verified and
 *   matched the pin; the operator code is saved with it.
 */
class CommunityDojoCache(private val file: File) {

    data class Entry(
        @SerializedName("source")
        val source: String,
        @SerializedName("fetched_at_ms")
        val fetchedAtMs: Long,
        @SerializedName("nodes")
        val nodes: List<CommunityDojoNode>,
        /** The verified operator code of the directory this came from. */
        @SerializedName("operator_code")
        val operatorCode: String? = null,
    )

    /** The cached entry for [source], or null if none or it was saved for another address. */
    fun read(source: String): Entry? {
        val entry = readAny() ?: return null
        return entry.takeIf { it.source.equals(source, ignoreCase = true) }
    }

    /**
     * Saves [nodes] for [source]. An empty list is never saved: the previous
     * entry stays, so one empty publish of the directory can't erase the
     * offline fallback.
     */
    fun write(source: String, nodes: List<CommunityDojoNode>, fetchedAtMs: Long, operatorCode: String) {
        if (nodes.isEmpty()) return
        val payload = Entry(source, fetchedAtMs, nodes, operatorCode).toJSON() ?: return
        file.parentFile?.mkdirs()
        // Write-then-rename so a crash mid-write never leaves a half file
        // that would read as "no cache".
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(payload)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    fun clear() {
        file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
    }

    private fun readAny(): Entry? {
        if (!file.isFile) return null
        val text = try {
            file.readText()
        } catch (e: Exception) {
            return null
        }
        val entry = fromJSON<Entry>(text) ?: return null
        // Gson leaves non-null Kotlin fields null when they are missing.
        @Suppress("SENSELESS_COMPARISON")
        if (entry.source == null || entry.nodes == null) return null
        return entry
    }

    companion object {
        const val FILE_NAME = "dojobay_directory_cache.json"

        fun forDirectory(noBackupFilesDir: File) = CommunityDojoCache(File(noBackupFilesDir, FILE_NAME))
    }
}
