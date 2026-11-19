package com.samourai.sentinel.ui.collectionDetails.send

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.samourai.sentinel.R
import com.samourai.wallet.psbt.PSBT
import com.samourai.wallet.psbt.PSBTEntry
import org.bitcoinj.core.NetworkParameters
import org.bouncycastle.util.encoders.Hex
import java.io.IOException
import java.io.OutputStream

/**
 * #566: write one already-reviewed PSBT to a file.
 *
 * The bytes are checked before anything is opened. A null, empty, or
 * unparseable payload never creates a file. The write itself is a single
 * [OutputStream.use] of those exact bytes. Any failure, including
 * [Error] and the app's bare [Throwable]s, comes back as a result. When
 * the document already exists (the save picker creates an empty one) or
 * the write opened one, a failure deletes it and reports whether that
 * delete actually removed the file.
 */
internal object PsbtFileExport {

    enum class Refusal {
        MISSING,
        EMPTY,
        UNPARSEABLE,
    }

    sealed class Outcome {
        data object Saved : Outcome()
        data class Refused(val reason: Refusal, val documentRemoved: Boolean) : Outcome()
        data class Failed(val error: Throwable, val documentRemoved: Boolean) : Outcome()
    }

    /**
     * @return why [bytes] must not be written, or null when they are a
     * parseable PSBT whose every input carries a witness or non-witness
     * UTXO. A PSBT with bare inputs (no UTXO record) is refused.
     */
    fun refusal(bytes: ByteArray?, params: NetworkParameters?): Refusal? {
        if (bytes == null) return Refusal.MISSING
        if (bytes.isEmpty()) return Refusal.EMPTY
        if (params == null) return Refusal.UNPARSEABLE
        return try {
            val parsed = PSBT.fromBytes(bytes, params)
            val tx = parsed.transaction
            if (!parsed.isParseOK || tx == null || tx.inputs.isEmpty()) {
                Refusal.UNPARSEABLE
            } else if (!everyInputHasUtxo(parsed)) {
                Refusal.UNPARSEABLE
            } else {
                null
            }
        } catch (t: Throwable) {
            Refusal.UNPARSEABLE
        }
    }

    /**
     * The Save .txt door stores the reviewed hex, not the binary. Blank
     * text (the review was cleared, or the process died) is [Refusal.MISSING].
     */
    fun textRefusal(text: String?, params: NetworkParameters?): Refusal? {
        if (text.isNullOrBlank()) return Refusal.MISSING
        val decoded = try {
            Hex.decode(text.trim())
        } catch (t: Throwable) {
            return Refusal.UNPARSEABLE
        }
        return refusal(decoded, params)
    }

    /**
     * @param documentAlreadyExists the save picker has already created
     * the document, so a refusal or a failed write deletes it. A share
     * into the cache passes false: nothing is created until [open].
     * @param delete returns true when the document is gone afterwards.
     */
    fun write(
        bytes: ByteArray?,
        params: NetworkParameters?,
        documentAlreadyExists: Boolean,
        open: () -> OutputStream?,
        delete: () -> Boolean,
    ): Outcome {
        val payload = bytes?.copyOf()
        val reason = refusal(payload, params)
        if (reason != null) {
            return Outcome.Refused(reason, removeIfNeeded(documentAlreadyExists, delete))
        }
        val safe = payload ?: return Outcome.Refused(
            Refusal.MISSING,
            removeIfNeeded(documentAlreadyExists, delete),
        )
        return try {
            val stream = open()
            if (stream == null) {
                Outcome.Failed(IOException("No output stream"), removeIfNeeded(true, delete))
            } else {
                stream.use { it.write(safe) }
                Outcome.Saved
            }
        } catch (t: Throwable) {
            Outcome.Failed(t, removeIfNeeded(true, delete))
        }
    }

    /** Writes the reviewed hex. Same delete reporting as [write]. */
    fun writeText(
        text: String?,
        params: NetworkParameters?,
        documentAlreadyExists: Boolean,
        open: () -> OutputStream?,
        delete: () -> Boolean,
    ): Outcome {
        val reason = textRefusal(text, params)
        if (reason != null || text.isNullOrBlank()) {
            return Outcome.Refused(
                reason ?: Refusal.MISSING,
                removeIfNeeded(documentAlreadyExists, delete),
            )
        }
        val payload = text.toByteArray(Charsets.UTF_8)
        return try {
            val stream = open()
            if (stream == null) {
                Outcome.Failed(IOException("No output stream"), removeIfNeeded(true, delete))
            } else {
                stream.use { it.write(payload) }
                Outcome.Saved
            }
        } catch (t: Throwable) {
            Outcome.Failed(t, removeIfNeeded(true, delete))
        }
    }

    /**
     * Removes a document the save picker created.
     *
     * A SAF document URI is served by a [android.provider.DocumentsProvider],
     * whose [android.provider.DocumentsProvider.delete] is final and throws.
     * [DocumentsContract.deleteDocument] is the call that actually removes it.
     * Anything that is not a document URI falls back to [android.content.ContentResolver.delete].
     *
     * @return true when the document is gone.
     */
    fun deleteExportedDocument(context: Context, uri: Uri): Boolean {
        val resolver = context.contentResolver
        return try {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                DocumentsContract.deleteDocument(resolver, uri)
            } else {
                resolver.delete(uri, null, null) > 0
            }
        } catch (t: Throwable) {
            false
        }
    }

    /** The sentence for an export that did not save. */
    fun messageRes(outcome: Outcome, sharing: Boolean): Int {
        val leftBehind = when (outcome) {
            is Outcome.Saved -> false
            is Outcome.Refused -> !outcome.documentRemoved
            is Outcome.Failed -> !outcome.documentRemoved
        }
        if (leftBehind) return R.string.send_export_incomplete
        return when (outcome) {
            is Outcome.Saved ->
                if (sharing) R.string.send_share_failed else R.string.send_export_failed
            is Outcome.Refused -> when (outcome.reason) {
                Refusal.MISSING -> R.string.send_export_again
                Refusal.EMPTY,
                Refusal.UNPARSEABLE -> R.string.send_psbt_unusable
            }
            is Outcome.Failed ->
                if (sharing) R.string.send_share_failed else R.string.send_export_failed
        }
    }

    /**
     * BIP-174 input maps are separated by a zero-length key. Each map
     * must include a non-empty witness UTXO or non-witness UTXO. The
     * composer writes one or both on every input it builds; a map with
     * neither is not a PSBT this wallet will export.
     */
    private fun everyInputHasUtxo(parsed: PSBT): Boolean {
        val expected = parsed.transaction?.inputs?.size ?: return false
        if (expected == 0) return false
        val maps = ArrayList<ArrayList<PSBTEntry>>()
        var current = ArrayList<PSBTEntry>()
        for (entry in parsed.psbtInputs.orEmpty()) {
            if (entry.key == null) {
                maps.add(current)
                current = ArrayList()
            } else {
                current.add(entry)
            }
        }
        if (current.isNotEmpty()) maps.add(current)
        if (maps.size != expected) return false
        val witness = PSBT.PSBT_IN_WITNESS_UTXO.toInt() and 0xff
        val nonWitness = PSBT.PSBT_IN_NON_WITNESS_UTXO.toInt() and 0xff
        return maps.all { map ->
            map.any { entry ->
                val type = entry.key?.firstOrNull()?.toInt()?.and(0xff) ?: return@any false
                val data = entry.data
                data != null && data.isNotEmpty() && (type == witness || type == nonWitness)
            }
        }
    }

    /** @return true when nothing was left behind. */
    private fun removeIfNeeded(needed: Boolean, delete: () -> Boolean): Boolean {
        if (!needed) return true
        return try {
            delete()
        } catch (t: Throwable) {
            false
        }
    }
}
