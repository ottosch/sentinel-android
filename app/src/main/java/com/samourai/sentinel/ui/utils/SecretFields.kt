package com.samourai.sentinel.ui.utils

import android.content.ClipboardManager
import android.content.Context
import android.widget.EditText
import com.google.android.material.textfield.TextInputLayout
import com.samourai.sentinel.util.PairingWords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * #282: shared pieces of the password fields on the pairing sheet and on
 * Settings -> Import backup. Neither ever logs the field's contents.
 */

/**
 * Makes the layout's start icon paste the clipboard into [edit], replacing
 * what is there. Users usually copy the password from their wallet app.
 * Surrounding whitespace is trimmed; anything else is left for the screen's
 * own checks to judge.
 */
fun TextInputLayout.enablePasteAction(edit: EditText) {
    setStartIconOnClickListener {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = clipboard?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?.trim()
        if (text.isNullOrEmpty()) return@setStartIconOnClickListener
        edit.setText(text)
        edit.setSelection(edit.text?.length ?: 0)
    }
}

/** The bundled EFF large wordlist, read once and kept for the process. */
object PairingWordsAsset {

    @Volatile
    private var cached: PairingWords? = null

    /** Null if the asset can't be read; callers must then not block entry. */
    suspend fun load(context: Context): PairingWords? {
        cached?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                context.applicationContext.assets.open(PairingWords.ASSET_PATH)
                    .bufferedReader()
                    .useLines { PairingWords(PairingWords.parseList(it)) }
                    .also { cached = it }
            } catch (e: Exception) {
                Timber.e(e, "Pairing wordlist unavailable")
                null
            }
        }
    }
}
