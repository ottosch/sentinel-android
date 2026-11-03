package com.samourai.sentinel.ui.utils

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.widget.EditText
import com.google.android.material.textfield.TextInputLayout
import com.samourai.sentinel.R
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

/**
 * #282: an eye icon at the end of the field that shows and hides [edit].
 *
 * Material's built-in password_toggle only swaps the transformation method,
 * so the keyboard keeps being told "password" while the text is on screen.
 * This switches the input type as well: TYPE_TEXT_VARIATION_PASSWORD while
 * hidden, TYPE_TEXT_VARIATION_VISIBLE_PASSWORD while shown, both with
 * TYPE_TEXT_FLAG_NO_SUGGESTIONS. setInputType restarts the IME, so the
 * keyboard picks up the change. Text, cursor and selection are kept, and
 * the field always starts hidden.
 *
 * The layout must declare app:endIconMode="custom".
 */
fun TextInputLayout.enableRevealToggle(edit: EditText) {
    var shown = false
    fun apply() {
        val start = edit.selectionStart
        val end = edit.selectionEnd
        val variation = if (shown) {
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        edit.inputType = InputType.TYPE_CLASS_TEXT or variation or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        if (!shown) {
            // setInputType applies this for the password variation already;
            // set it explicitly so hidden never depends on that detail.
            edit.transformationMethod = PasswordTransformationMethod.getInstance()
        }
        // setInputType resets the typeface for password variations.
        edit.typeface = Typeface.MONOSPACE
        val length = edit.text?.length ?: 0
        if (start >= 0 && end >= 0) {
            edit.setSelection(start.coerceIn(0, length), end.coerceIn(0, length))
        }
        setEndIconDrawable(if (shown) R.drawable.ic_visibility_off_24 else R.drawable.ic_visibility_24)
        endIconContentDescription =
            context.getString(if (shown) R.string.password_hide else R.string.password_show)
    }
    apply()
    setEndIconOnClickListener {
        shown = !shown
        apply()
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
