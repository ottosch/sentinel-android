package com.samourai.sentinel.ui.dojo

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.samourai.sentinel.R
import com.samourai.sentinel.ui.utils.PrefsUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Dojo Bay address editor, shared by the Community Dojos sheet and the
 * Network screen. The address is checked with [DojoBaySource]; only v3
 * onion addresses are accepted. Reset to default clears it. Any change
 * drops the saved list, which belongs to the old address.
 */
object DojoBayAddressDialog {

    fun show(
        context: Context,
        prefsUtil: PrefsUtil,
        noBackupFilesDir: File,
        scope: CoroutineScope,
        onChanged: () -> Unit,
    ) {
        val current = DojoBaySource.effectiveBaseUrl(prefsUtil.dojoBayUrl)
        val noSuggestions = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        val address = EditText(context).apply {
            inputType = noSuggestions or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            setText(current)
            setSelection(text.length)
            hint = context.getString(R.string.dojobay_address_hint)
        }
        val pad = (20 * context.resources.displayMetrics.density).toInt()
        fun label(@StringRes text: Int) = TextView(context).apply {
            setText(text)
            setPadding(0, pad / 2, 0, 0)
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(label(R.string.dojobay_address_label))
            addView(address)
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dojobay_address_title)
            .setMessage(R.string.dojobay_address_message)
            .setView(container)
            .setPositiveButton(R.string.dojobay_address_save, null)
            .setNeutralButton(R.string.dojobay_address_reset, null)
            .setNegativeButton(R.string.dojobay_address_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                address.error = null
                when (val v = DojoBaySource.validate(address.text?.toString())) {
                    is DojoBaySource.Validation.Invalid -> {
                        address.error = context.getString(reasonText(v.reason))
                        address.requestFocus()
                    }
                    is DojoBaySource.Validation.Valid -> {
                        save(context, prefsUtil, noBackupFilesDir, scope, v.baseUrl, onChanged)
                        dialog.dismiss()
                    }
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val changed = !prefsUtil.dojoBayUrl.isNullOrBlank()
                prefsUtil.dojoBayUrl = null
                Toast.makeText(context, R.string.dojobay_address_reset_done, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                if (changed) clearCacheThen(scope, noBackupFilesDir, onChanged)
            }
        }
        dialog.show()
    }

    private fun save(
        context: Context,
        prefsUtil: PrefsUtil,
        noBackupFilesDir: File,
        scope: CoroutineScope,
        baseUrl: String,
        onChanged: () -> Unit,
    ) {
        val before = DojoBaySource.effectiveBaseUrl(prefsUtil.dojoBayUrl)
        // The default is stored as "no custom value", so a later change of
        // the built-in default still reaches users who never customized it.
        prefsUtil.dojoBayUrl = if (DojoBaySource.isDefault(baseUrl)) null else baseUrl
        Toast.makeText(context, R.string.dojobay_address_saved, Toast.LENGTH_SHORT).show()
        if (before != baseUrl) {
            clearCacheThen(scope, noBackupFilesDir, onChanged)
        }
    }

    /**
     * Deletes the saved list off the main thread, then reports the change on
     * it, in the caller's lifecycle [scope] so nothing runs after the host
     * is gone.
     */
    private fun clearCacheThen(scope: CoroutineScope, noBackupFilesDir: File, onChanged: () -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    CommunityDojoCache.forDirectory(noBackupFilesDir).clear()
                } catch (e: Exception) {
                    // The saved list is also keyed to its address.
                }
            }
            onChanged()
        }
    }

    @StringRes
    fun reasonText(reason: DojoBaySource.Reason): Int = when (reason) {
        DojoBaySource.Reason.EMPTY -> R.string.dojobay_address_invalid_empty
        DojoBaySource.Reason.MALFORMED -> R.string.dojobay_address_invalid_malformed
        DojoBaySource.Reason.NOT_ONION -> R.string.dojobay_address_invalid_not_onion
        DojoBaySource.Reason.BAD_ONION -> R.string.dojobay_address_invalid_onion
        DojoBaySource.Reason.BAD_PORT -> R.string.dojobay_address_invalid_port
        DojoBaySource.Reason.NOT_HTTP -> R.string.dojobay_address_invalid_not_http
        DojoBaySource.Reason.HAS_EXTRAS -> R.string.dojobay_address_invalid_extras
    }
}
