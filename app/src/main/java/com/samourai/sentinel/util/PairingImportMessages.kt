package com.samourai.sentinel.util

import android.content.Context
import com.samourai.sentinel.R

/** #399: the same sentences as [EnglishPairingImportCopy], from resources. */
internal fun pairingImportUserMessage(context: Context, outcome: PairingImportOutcome): String? {
    return pairingImportMessage(outcome, ResourcePairingImportCopy(context))
}

private class ResourcePairingImportCopy(private val context: Context) : PairingImportCopy {
    override val wrongPassword: String
        get() = context.getString(R.string.pairing_import_wrong_password)
    override val notJson: String
        get() = context.getString(R.string.pairing_import_not_json)
    override val unrecognised: String
        get() = context.getString(R.string.pairing_import_unrecognised)
    override val legacy: String
        get() = context.getString(R.string.pairing_import_legacy)
    override val noKeys: String
        get() = context.getString(R.string.pairing_import_no_keys)
    override fun newerAshigaru(version: String): String =
        context.getString(R.string.pairing_import_newer_ashigaru, version)
    override fun skippedImported(list: String): String =
        context.getString(R.string.pairing_import_skipped, list)
    override fun skippedOnly(list: String): String =
        context.getString(R.string.pairing_import_skipped_only, list)
    override fun slot(name: String, problem: String): String =
        context.getString(R.string.pairing_import_slot, name, problem)
    override fun extraEntry(index: Int): String =
        context.getString(R.string.pairing_import_extra_entry, index)
    override val missing: String
        get() = context.getString(R.string.pairing_import_slot_missing)
    override val invalidXpub: String
        get() = context.getString(R.string.pairing_import_slot_invalid)
    override val unrecognisedEntry: String
        get() = context.getString(R.string.pairing_import_slot_unrecognised)
}
