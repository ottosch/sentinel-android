package com.samourai.sentinel.ui.collectionDetails.send

import com.samourai.wallet.psbt.PSBT
import org.bitcoinj.core.NetworkParameters
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
 * the write opened one, a failure deletes it.
 */
internal object PsbtFileExport {

    enum class Refusal {
        MISSING,
        EMPTY,
        UNPARSEABLE,
    }

    sealed class Outcome {
        data object Saved : Outcome()
        data class Refused(val reason: Refusal) : Outcome()
        data class Failed(val error: Throwable) : Outcome()
    }

    /**
     * @return why [bytes] must not be written, or null when they are a
     * parseable PSBT that spends at least one input.
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
            } else {
                null
            }
        } catch (t: Throwable) {
            Refusal.UNPARSEABLE
        }
    }

    /**
     * @param documentAlreadyExists the save picker has already created
     * the document, so a refusal or a failed write deletes it. A share
     * into the cache passes false: nothing is created until [open].
     */
    fun write(
        bytes: ByteArray?,
        params: NetworkParameters?,
        documentAlreadyExists: Boolean,
        open: () -> OutputStream?,
        delete: () -> Unit,
    ): Outcome {
        val payload = bytes?.copyOf()
        val reason = refusal(payload, params)
        if (reason != null) {
            if (documentAlreadyExists) deleteQuietly(delete)
            return Outcome.Refused(reason)
        }
        val safe = payload ?: return Outcome.Refused(Refusal.MISSING).also {
            if (documentAlreadyExists) deleteQuietly(delete)
        }
        return try {
            val stream = open()
            if (stream == null) {
                deleteQuietly(delete)
                Outcome.Failed(IOException("No output stream"))
            } else {
                stream.use { it.write(safe) }
                Outcome.Saved
            }
        } catch (t: Throwable) {
            deleteQuietly(delete)
            Outcome.Failed(t)
        }
    }

    private fun deleteQuietly(delete: () -> Unit) {
        try {
            delete()
        } catch (t: Throwable) {
            // The export already failed or was refused. Cleanup must
            // not turn that into a crash, or replace the result.
        }
    }
}
