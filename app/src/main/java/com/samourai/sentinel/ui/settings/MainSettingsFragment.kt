package com.samourai.sentinel.ui.settings;

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.preference.CheckBoxPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.samourai.sentinel.R
import com.samourai.sentinel.core.access.AccessFactory
import com.samourai.sentinel.core.access.PinVault
import com.samourai.sentinel.data.db.SentinelRoomDb
import com.samourai.sentinel.data.db.appWalletPayloads
import com.samourai.sentinel.data.repository.Bip329Exporter
import com.samourai.sentinel.data.repository.Bip329Importer
import com.samourai.sentinel.ui.fragments.ImportLabelsScanBottomSheet
import java.io.StringReader
import com.samourai.sentinel.data.repository.ImportResult
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.data.repository.ExchangeRateRepository
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.ui.SentinelActivity
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.dojo.PubKeyRescanner
import com.samourai.sentinel.ui.home.HomeActivity
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.utils.showFloatingSnackBar
import com.samourai.sentinel.ui.utils.startDocumentPickerForResult
import com.samourai.sentinel.ui.views.KdfProgressOverlay
import com.samourai.sentinel.ui.views.LockScreenDialog
import com.samourai.sentinel.ui.views.alertWithInput
import com.samourai.sentinel.ui.views.confirm
import com.samourai.sentinel.ui.webview.ExplorerRepository
import com.samourai.sentinel.util.CollectionsNotLoadedException
import com.samourai.sentinel.util.ExportImportUtil
import com.samourai.sentinel.util.UtxoMetaUtil
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MainSettingsFragment : PreferenceFragmentCompat() {

    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java);
    private val roomDb: SentinelRoomDb by inject(SentinelRoomDb::class.java);
    private val accessFactory: AccessFactory by inject(AccessFactory::class.java);
    private val collectionRepository: CollectionRepository by inject(CollectionRepository::class.java);
    private val exchangeRateRepository: ExchangeRateRepository by inject(ExchangeRateRepository::class.java);
    private val explorerRepository: ExplorerRepository by inject(ExplorerRepository::class.java);
    private val bip329Importer: Bip329Importer by inject(Bip329Importer::class.java);
    private val bip329Exporter: Bip329Exporter by inject(Bip329Exporter::class.java);
    private val dojoUtility: DojoUtility by inject(DojoUtility::class.java);
    private val settingsScope = CoroutineScope(context = Dispatchers.Main)
    private var exportedBackUp: String? = null

    /** #355: waiting room for the settings-side Argon2id runs. */
    private val kdfOverlay by lazy { KdfProgressOverlay(requireActivity()) }

    /**
     * #220: first pin of a set-pin flow. Memory of this fragment instance
     * only. A recreated fragment has none, and the confirm pad is closed.
     */
    private var pendingNewPin: String? = null

    /** #288: the destructive actions that ask for the PIN first. */
    private enum class GatedAction { EXPORT, ERASE }

    /**
     * #288: which action a successful PIN entry should run. Memory of this
     * fragment instance only, for the same reason as [pendingNewPin] (#220):
     * a recreated fragment must not inherit an authorisation it never saw
     * granted, so the pad is closed instead.
     */
    private var pendingGatedAction: GatedAction? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        parentFragmentManager.setFragmentResultListener(REQUEST_KEY_PIN_ENTER, this) { _, bundle ->
            parentFragmentManager.clearFragmentResult(REQUEST_KEY_PIN_ENTER)
            onPinEntered(bundle.getString(LockScreenDialog.RESULT_PIN).orEmpty())
        }
        parentFragmentManager.setFragmentResultListener(REQUEST_KEY_PIN_CONFIRM, this) { _, bundle ->
            parentFragmentManager.clearFragmentResult(REQUEST_KEY_PIN_CONFIRM)
            onPinConfirmed(bundle.getString(LockScreenDialog.RESULT_PIN).orEmpty())
        }
        parentFragmentManager.setFragmentResultListener(REQUEST_KEY_PIN_GATE, this) { _, bundle ->
            parentFragmentManager.clearFragmentResult(REQUEST_KEY_PIN_GATE)
            onGatePinEntered(bundle.getString(LockScreenDialog.RESULT_PIN).orEmpty())
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.main_preferences, rootKey)
    }

    /**
     * #296: the pad's presence is the only thing that decides whether the
     * screen behind it is exposed, and it comes and goes through the back
     * stack - Back, a successful entry, and recreation all land here, so one
     * listener covers every path instead of pairing a set with a restore at
     * each call site.
     */
    private val padBackStackListener = FragmentManager.OnBackStackChangedListener {
        syncPadAccessibility()
    }

    /**
     * #296: any pad over this screen hides the content behind it from
     * accessibility services.
     */
    private fun syncPadAccessibility() {
        val padShowing = parentFragmentManager.findFragmentByTag(TAG_PIN_ENTER) != null ||
            parentFragmentManager.findFragmentByTag(TAG_PIN_CONFIRM) != null ||
            // #288: the gate pad covers the screen the same way; it
            // post-dates #333's two-tag check.
            parentFragmentManager.findFragmentByTag(TAG_PIN_GATE) != null
        (activity as? SettingsActivity)?.setContentHiddenFromAccessibility(padShowing)
    }

    override fun onDestroyView() {
        parentFragmentManager.removeOnBackStackChangedListener(padBackStackListener)
        // Leaving the screen with the flag still set would keep Settings
        // hidden from accessibility for the rest of the activity's life.
        (activity as? SettingsActivity)?.setContentHiddenFromAccessibility(false)
        super.onDestroyView()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // #296: pad arrivals and departures all pass through the back
        // stack; one listener follows them.
        parentFragmentManager.addOnBackStackChangedListener(padBackStackListener)

        // #220: confirm step compared against pendingNewPin, which does not
        // survive recreation and must not be saved. Close that pad; the
        // enter pad underneath still posts to the listener above.
        view.post {
            if (!isAdded) return@post
            if (parentFragmentManager.findFragmentByTag(TAG_PIN_CONFIRM) != null && pendingNewPin == null) {
                parentFragmentManager.popBackStack()
            }
            // #288: likewise for the gate pad - without the action it was
            // opened for there is nothing to authorise.
            if (parentFragmentManager.findFragmentByTag(TAG_PIN_GATE) != null && pendingGatedAction == null) {
                parentFragmentManager.popBackStack()
            }
            // #296: a pad restored alongside this fragment never fires a
            // back-stack change, so settle the initial state here.
            syncPadAccessibility()
        }

        setExchangeSettings()
        setExplorerSettings()

        val pineEntryCheckBox = findPreference<CheckBoxPreference>("pinEnabled")
        pineEntryCheckBox?.let {
            it.setOnPreferenceChangeListener { _, newValue ->
                val enabling = newValue == true
                showPinDialog(
                    message = if (enabling) "Enter new pin code" else "Enter current pin code",
                    requestKey = REQUEST_KEY_PIN_ENTER,
                    purpose = if (enabling) LockScreenDialog.PURPOSE_SET else LockScreenDialog.PURPOSE_REMOVE,
                    tag = TAG_PIN_ENTER
                )
                false
            }
        }

        val hapticPin = findPreference<CheckBoxPreference>("haptic")
        hapticPin?.isChecked = prefsUtil.haptics ?: false
        hapticPin?.let { pref ->
            pref.setOnPreferenceClickListener {
                prefsUtil.haptics = pref.isChecked
                true
            }
        }

        val streetModePref = findPreference<CheckBoxPreference>("streetMode")
        streetModePref?.isChecked = prefsUtil.streetMode ?: false
        streetModePref?.let { pref ->
            pref.setOnPreferenceClickListener {
                prefsUtil.streetMode = pref.isChecked
                true
            }
        }

        // Home reads PrefsUtil, not the checkbox widget. Copy the new
        // value across, same as street mode.
        val fiatPref = findPreference<CheckBoxPreference>("fiatDisabled")
        fiatPref?.isChecked = prefsUtil.fiatDisabled == true
        fiatPref?.setOnPreferenceChangeListener { _, newValue ->
            prefsUtil.fiatDisabled = newValue as Boolean
            true
        }

        // Whirlpool auto-labeling toggle (#6). PrefsUtil-backed, same
        // as haptic. Turning it on shows an advisory. The stored value
        // is the checkbox; dismissing the dialog leaves it on.
        val whirlpoolAutoLabelsPref =
            findPreference<CheckBoxPreference>("whirlpoolAutoLabels")
        whirlpoolAutoLabelsPref?.isChecked =
            prefsUtil.whirlpoolAutoLabels ?: false
        whirlpoolAutoLabelsPref?.let { pref ->
            pref.setOnPreferenceClickListener {
                val enabled = pref.isChecked
                prefsUtil.whirlpoolAutoLabels = enabled
                if (enabled) {
                    showWhirlpoolAutoLabelsAdvisory()
                }
                true
            }
        }

        val clearWallet = findPreference<Preference>("clear")

        val shareErrorLog = findPreference<Preference>("shareErrorLog")
        shareErrorLog?.setOnPreferenceClickListener {
            val file = File("${requireActivity().cacheDir}/error_dump.log")

            if (file.exists()) {
                val intent = Intent()
                intent.action = Intent.ACTION_SEND
                intent.type = "text/plain"
                if (Build.VERSION.SDK_INT >= 24) {
                    //From API 24 sending FIle on intent ,require custom file provider
                    intent.putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                            requireContext(),
                            requireContext()
                                    .packageName + ".provider", file))
                } else {
                    intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file))
                }
                requireActivity().startActivity(Intent.createChooser(intent, requireContext().getText(R.string.send_payment_code)))
            } else {
                Toast.makeText(requireContext(), "File does not exist", Toast.LENGTH_SHORT).show()
            }
            true
        }
        clearWallet?.setOnPreferenceClickListener {
            requirePin(GatedAction.ERASE)
            true
        }

        findPreference<Preference>("rescanXpub")
                ?.setOnPreferenceClickListener {
                    // No collection context here, so rescan every public key
                    // the app knows about - this entry point exists for the
                    // "my balance is wrong and I don't know which key" case.
                    // collectionsSnapshot(): the repository list is not public.
                    val collections = collectionRepository.collectionsSnapshot()
                    PubKeyRescanner.confirmAndRescan(
                        requireActivity() as SentinelActivity,
                        collections.flatMap { it.pubs },
                        collections.map { it.id }
                    )
                    true
                }

        findPreference<Preference>("import")
                ?.setOnPreferenceClickListener {
                    startActivity(Intent(requireActivity(), ImportBackUpActivity::class.java))
                    true
                }
        findPreference<Preference>("export")
                ?.setOnPreferenceClickListener {
                    requirePin(GatedAction.EXPORT)
                    true
                }
        findPreference<Preference>("importLabels")
                ?.setOnPreferenceClickListener {
                    importLabels()
                    true
                }
        findPreference<Preference>("importLabelsQr")
                ?.setOnPreferenceClickListener {
                    showImportLabelsScanner()
                    true
                }
        findPreference<Preference>("exportLabels")
                ?.setOnPreferenceClickListener {
                    exportLabels()
                    true
                }
    }

    /**
     * Shown only when the toggle turns on. The checkbox is already
     * saved; this dialog does not change it.
     */
    private fun showWhirlpoolAutoLabelsAdvisory() {
        if (!isAdded) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.whirlpool_auto_labels_dialog_title))
            .setMessage(getString(R.string.whirlpool_auto_labels_dialog_message))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun setExplorerSettings() {
        val entries: Array<CharSequence> = explorerRepository.getExplorers().toArray(arrayOfNulls<CharSequence>(explorerRepository.getExplorers().size))

        findPreference<ListPreference>("explorerSelection")
            ?.apply {
                setEntries(entries)
                entryValues = entries
                value = prefsUtil.selectedExplorer
                setOnPreferenceChangeListener { preference, newValue ->
                    if (newValue == ExplorerRepository.CUSTOM_EXPLORER_NAME) {
                        // Don't switch until a URL is actually saved - stay on
                        // the previous selection in the meantime.
                        promptForCustomExplorerUrl(preference as ListPreference)
                        false
                    } else {
                        prefsUtil.selectedExplorer = newValue as String
                        true
                    }
                }
            }
    }

    private fun promptForCustomExplorerUrl(preference: ListPreference) {
        (activity as SentinelActivity).alertWithInput(
            label = "Custom block explorer",
            buttonLabel = "Save",
            value = prefsUtil.customExplorerUrl.orEmpty(),
            labelEditText = "Onion URL, e.g. http://xyz.onion/tx/{txid}",
            maxLen = 256,
            onConfirm = { input ->
                val url = input.trim()
                if (url.isNotEmpty()) {
                    prefsUtil.customExplorerUrl = url
                    prefsUtil.selectedExplorer = ExplorerRepository.CUSTOM_EXPLORER_NAME
                    preference.value = ExplorerRepository.CUSTOM_EXPLORER_NAME
                } else {
                    Toast.makeText(requireContext(), "No URL entered", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun setExchangeSettings() {

        fun setCurrencies() {
            prefsUtil.exchangeRate = -1L
            val entries: Array<CharSequence> = exchangeRateRepository.getCurrencies().toArray(arrayOfNulls<CharSequence>(exchangeRateRepository.getCurrencies().size))
            findPreference<ListPreference>("selectedCurrency")
                    ?.apply {
                        setEntries(entries)
                        entryValues = entries
                        value = prefsUtil.selectedCurrency
                        setOnPreferenceChangeListener { _, newValue ->
                            prefsUtil.selectedCurrency = newValue as String
                            exchangeRateRepository.fetch()
                            true
                        }
                    }

        }

        val entries: Array<CharSequence> = exchangeRateRepository.getExchanges().toArray(arrayOfNulls<CharSequence>(exchangeRateRepository.getExchanges().size))

        findPreference<ListPreference>("exchangeSelection")
                ?.apply {
                    setEntries(entries)
                    entryValues = entries
                    value = prefsUtil.exchangeSelection
                    setOnPreferenceChangeListener { preference, newValue ->
                        exchangeRateRepository.fetch()
                        prefsUtil.exchangeSelection = newValue as String
                        exchangeRateRepository.reloadChanges()
                        setCurrencies()
                        true
                    }
                }
        setCurrencies()

    }

    /**
     * #298: deletes every on-disk copy of every payload under app_wallet:
     * primary, rotated .bak and any staged .tmp, for all four stores
     * (collections, dojo, utxo.meta, fee).
     *
     * Every payload write rotates the previous file to <name>.bak, so
     * before this, a wipe left collections.payload.bak holding the real
     * xpubs encrypted under the old PIN, plus fee.payload.bak and
     * utxo.meta.payload.bak - recoverable by anyone who later learns the
     * old PIN or pulls the files out of a device backup. The Dojo leg is
     * already owned by clearDojo()'s #208 deleteAll; the sweep includes
     * the dojo payload again because absent files count as gone.
     *
     * Failures are logged, not propagated: the data is already gone
     * logically at this point, and a failed delete must not turn a
     * completed wipe into a reported error.
     */
    private fun erasePayloadFiles(filesDir: File) {
        appWalletPayloads(filesDir).forEach { record ->
            try {
                if (!record.deleteAll()) {
                    Timber.w("Erase: copies of ${record.name} remain on disk")
                }
            } catch (e: Exception) {
                Timber.e(e, "Erase: could not delete payload ${record.name}")
            }
        }
    }

    private fun clearWallet() {
        (activity as AppCompatActivity).confirm(label = "Are you sure?",
                message = "All collections will be erased, Dojo/server disconnected and settings reset",
                positiveText = "Yes",
                negativeText = "No",
                onConfirm = { confirmed ->
                    if (confirmed) {
                        // #298: capture the payload dir on Main before the IO
                        // hop (#82 discipline) - requireContext() is a
                        // fragment call.
                        val payloadFilesDir = requireContext().filesDir
                        settingsScope.launch {
                            try {
                                SentinelTorManager.stop()
                                withContext(Dispatchers.IO) {
                                    roomDb.eraseStoredRows()
                                    dojoUtility.clearDojo()
                                    prefsUtil.clearAll()
                                    // Clear the PIN state BEFORE reset() so the
                                    // resulting empty payload is written in the
                                    // same (plaintext) form it will be read back in.
                                    AccessFactory.getInstance(null).clearDataKey()
                                    AccessFactory.getInstance(null).pin = null
                                    AccessFactory.getInstance(null).setPinProtected(false)
                                    collectionRepository.reset()
                                    UtxoMetaUtil.clearAll()
                                    // #298: erase the payload files too - reset()
                                    // and clearAll() above persist asynchronously
                                    // and their empty writes may land after this,
                                    // which is fine: rotation needs an existing
                                    // primary, and by now there is none. What
                                    // reappears is the cleared state with nothing
                                    // behind it.
                                    erasePayloadFiles(payloadFilesDir)
                                    // #164: no second stop() here - the pre-wipe
                                    // stop above already owns the shutdown; a
                                    // posthumous second call re-published STOPPING
                                    // after the observer's OFF and wedged the
                                    // state for process lifetime.
                                }
                                startActivity(Intent(activity, HomeActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                })
                                (activity as SettingsActivity).overridePendingTransition(R.anim.fade_in, R.anim.bottom_sheet_slide_out);
                                (activity as SettingsActivity).finish()
                            } catch (ex: Exception) {
                                withContext(Dispatchers.Main) {
                                    (activity as AppCompatActivity).showFloatingSnackBar(
                                            parent_view = requireView(),
                                            text = "Error : $ex"
                                    )
                                }
                            }
                        }
                    }
                }
        )
    }

    private fun showPinDialog(message: String, requestKey: String, purpose: String, tag: String) {
        if (parentFragmentManager.findFragmentByTag(tag) != null) return
        val dialog = LockScreenDialog.newInstance(
            message = message,
            requestKey = requestKey,
            purpose = purpose
        )
        parentFragmentManager.beginTransaction()
            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
            .add(android.R.id.content, dialog, tag)
            .addToBackStack("Lock")
            .commit()
    }

    /**
     * #288: asks for the current PIN before running [action].
     *
     * Export writes out every xpub and the Dojo pairing under a password the
     * person at the keyboard chooses, and Erase destroys the wallet - both
     * previously ran straight from a tap, so anyone holding the unlocked
     * phone could do either. Being inside the unlock window is not consent
     * for those two.
     *
     * With no PIN configured there is nothing to check and the action runs as
     * before; this gate is not a substitute for setting one.
     */
    private fun requirePin(action: GatedAction) {
        if (prefsUtil.pinHash.isNullOrEmpty()) {
            runGatedAction(action)
            return
        }
        pendingGatedAction = action
        showPinDialog(
            message = "Enter current pin code",
            requestKey = REQUEST_KEY_PIN_GATE,
            purpose = LockScreenDialog.PURPOSE_UNLOCK,
            tag = TAG_PIN_GATE
        )
    }

    private fun onGatePinEntered(pin: String) {
        val dialog = parentFragmentManager.findFragmentByTag(TAG_PIN_GATE) as? LockScreenDialog ?: return
        val action = pendingGatedAction
        val hash = prefsUtil.pinHash
        if (action == null || hash.isNullOrEmpty()) {
            // Nothing to authorise - the fragment was recreated, or the PIN
            // was removed while the pad was open.
            pendingGatedAction = null
            parentFragmentManager.popBackStack()
            return
        }
        settingsScope.launch {
            val valid = withContext(Dispatchers.IO) { accessFactory.validateHash(pin, hash) }
            if (valid) {
                // Consume the authorisation before running, so a replayed
                // result cannot run the action twice.
                pendingGatedAction = null
                dialog.dismiss()
                parentFragmentManager.popBackStack()
                runGatedAction(action)
            } else {
                dialog.showError()
            }
        }
    }

    private fun runGatedAction(action: GatedAction) {
        when (action) {
            GatedAction.EXPORT -> export()
            GatedAction.ERASE -> clearWallet()
        }
    }

    private fun onPinEntered(pin: String) {
        val dialog = parentFragmentManager.findFragmentByTag(TAG_PIN_ENTER) as? LockScreenDialog ?: return
        when (dialog.purpose) {
            LockScreenDialog.PURPOSE_SET -> {
                pendingNewPin = pin
                showPinDialog(
                    message = "Please Re-Enter Pin Code",
                    requestKey = REQUEST_KEY_PIN_CONFIRM,
                    purpose = LockScreenDialog.PURPOSE_CONFIRM,
                    tag = TAG_PIN_CONFIRM
                )
            }
            LockScreenDialog.PURPOSE_REMOVE -> {
                val box = findPreference<CheckBoxPreference>("pinEnabled") ?: return
                removePinCode(pin, dialog, box)
            }
        }
    }

    private fun onPinConfirmed(pin: String) {
        val dialog = parentFragmentManager.findFragmentByTag(TAG_PIN_CONFIRM) as? LockScreenDialog
        val first = pendingNewPin
        if (dialog == null || first == null) {
            pendingNewPin = null
            if (parentFragmentManager.findFragmentByTag(TAG_PIN_CONFIRM) != null) {
                parentFragmentManager.popBackStack()
            }
            return
        }
        if (pin == first) {
            val box = findPreference<CheckBoxPreference>("pinEnabled") ?: return
            pendingNewPin = null
            setPinCode(pin, box)
            // #433: this flow leaves TWO pad records behind - the set
            // pad and the confirm pad stacked above it. The old cleanup
            // dismissed the set pad, which only removed its fragment (a
            // raw add() is not show(), so dismiss() cannot pop the
            // record), then popped once - popping the confirm's record.
            // The set pad's record survived with a still-findable ghost;
            // showPinDialog's guard read the ghost as an open pad and
            // silently dropped the next toggle tap, and leaving Settings
            // took two Backs. Settings' back stack holds nothing but pad
            // records, so popping them all closes every pad at once.
            while (parentFragmentManager.backStackEntryCount > 0) {
                parentFragmentManager.popBackStack()
            }
        } else {
            Toast.makeText(requireContext(), "Pins are not the same", Toast.LENGTH_SHORT).show()
            dialog.showError()
        }
    }

    private fun removePinCode(pin: String, lockScreenDialog: LockScreenDialog, checkBox: CheckBoxPreference) {
        // #355: disable() re-derives the key (Argon2id) to verify first.
        kdfOverlay.show("Removing pin\u2026")
        val filesDir = requireContext().filesDir
        settingsScope.launch {
            val removed = withContext(Dispatchers.IO) {
                val ok = PinVault(prefsUtil).disable(pin, appWalletPayloads(filesDir))
                if (ok) {
                    accessFactory.reset()
                    dojoUtility.store()
                    collectionRepository.sync()
                }
                ok
            }
            kdfOverlay.dismiss()
            if (removed) {
                lockScreenDialog.dismiss()
                checkBox.isChecked = false
                parentFragmentManager.popBackStack()
            } else {
                lockScreenDialog.showError()
            }
        }
    }

    private fun setPinCode(pin: String, it: CheckBoxPreference) {
        // #355: enable() derives the PIN wrap key (Argon2id).
        kdfOverlay.show("Saving pin\u2026")
        val filesDir = requireContext().filesDir
        settingsScope.launch {
            val enabled = try {
                withContext(Dispatchers.IO) {
                    val vault = PinVault(prefsUtil)
                    vault.enable(pin)
                    if (!vault.upgradeExisting(appWalletPayloads(filesDir))) {
                        throw IllegalStateException("Could not update saved payloads")
                    }
                    vault.markPayloadsCurrent()
                    dojoUtility.store()
                    collectionRepository.sync()
                    true
                }
            } catch (e: Exception) {
                false
            }
            kdfOverlay.dismiss()
            if (enabled) {
                it.isChecked = true
                prefsUtil.pinEnabled = true
            } else {
                Toast.makeText(context, "Could not save PIN", Toast.LENGTH_LONG).show()
            }
        }
    }


    private fun export() {
        fun makeExportPayload(copyToClipBoard: Boolean) {
            settingsScope.launch(Dispatchers.IO) {
                val payload = try {
                    ExportImportUtil().makePayload()
                } catch (e: CollectionsNotLoadedException) {
                    // #289: do not offer a password for an empty snapshot.
                    Timber.e(e, "export refused: collections not loaded")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "Backup not created. Unlock the wallet and try again.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }
                val payloadText = payload.toString()
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    (activity as SentinelActivity).alertWithInput(labelEditText = "Password",
                            buttonLabel = "Encrypt",
                            maskInput = true,
                            maxLen = 128,
                            label = "Add payload password", onConfirm = { password ->
                        // #48: an empty password IS a valid AES key input, so
                        // export would silently produce a backup that import
                        // refuses (line-93 guard). Reject at the source.
                        if (password.isBlank()) {
                            Toast.makeText(
                                context,
                                "Password cannot be empty - backup not created",
                                Toast.LENGTH_LONG
                            ).show()
                            return@alertWithInput
                        }
                        settingsScope.launch {
                            // #355: the Argon2id encrypt is the app's longest
                            // wait; both exits below dismiss the overlay.
                            kdfOverlay.show("Encrypting backup\u2026")
                            val payloadEncrypted = try {
                                withContext(Dispatchers.IO) {
                                    ExportImportUtil().encryptBackup(payloadText, password)
                                }
                            } catch (e: Exception) {
                                kdfOverlay.dismiss()
                                Toast.makeText(context, "Could not create backup", Toast.LENGTH_LONG).show()
                                return@launch
                            }
                            kdfOverlay.dismiss()
                            exportedBackUp = payloadEncrypted
                            if (copyToClipBoard) {
                                val cm = context?.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
                                val clipData = ClipData
                                        .newPlainText("Sentinel BackUp", payloadEncrypted)
                                if (cm != null) {
                                    cm.setPrimaryClip(clipData)
                                    Toast.makeText(context, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
                                }
                                view?.let { it1 -> (activity as SentinelActivity).showFloatingSnackBar(it1, getString(R.string.copied_to_clipboard)) }
                            } else {
                                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                                intent.addCategory(Intent.CATEGORY_OPENABLE)
                                intent.type = "text/plain"
                                val current = Calendar.getInstance().time
                                val formatter = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
                                // #404: the envelope just built carries the
                                // network when the keychain is unanimous; put
                                // that word in the file name. Mixed or empty
                                // wallets keep today's name - the envelope is
                                // the single verdict, never a second one.
                                val declaredNetwork = JSONObject(payloadEncrypted).optString("network", "")
                                val networkWord = if (declaredNetwork.isEmpty()) "" else "_$declaredNetwork"
                                intent.putExtra(Intent.EXTRA_TITLE, "sentinel_backup${networkWord}_${formatter.format(current)}.txt")
                                startDocumentPickerForResult(intent, REQ_CODE_WRITE_BACKUP)
                            }
                        }
                    })
                }
            }
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.choose_export_opt))
            .setMessage("The backup includes collections, settings, and any Dojo pairing. You will choose a password to encrypt it.")
            .setPositiveButton("Save to file") { _, _ ->
                makeExportPayload(false)
            }
            .setNeutralButton("Copy to clipboard") { _, _ ->
                makeExportPayload(true)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()

    }

    /**
     * BIP-329 label import: pick a .jsonl file via SAF, merge into the
     * label tables (network-scoped), then report the counts.
     */
    private fun importLabels() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        // .jsonl has no reliably-registered MIME type across providers,
        // so accept anything and filter with EXTRA_MIME_TYPES.
        intent.type = "*/*"
        intent.putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/json", "text/plain", "application/octet-stream")
        )
        startDocumentPickerForResult(intent, REQ_CODE_IMPORT_LABELS)
    }

    private fun exportLabels() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "application/octet-stream"
        intent.putExtra(Intent.EXTRA_TITLE, "labels.jsonl")
        startDocumentPickerForResult(intent, REQ_CODE_EXPORT_LABELS)
    }

    private fun importLabelsFrom(uri: Uri) {
        settingsScope.launch {
            try {
                val result: ImportResult = withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)!!.use { input ->
                        bip329Importer.import(BufferedReader(InputStreamReader(input)))
                    }
                }
                MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Labels imported")
                        .setMessage(
                                "Imported: ${result.imported}\n" +
                                        "Updated: ${result.updated}\n" +
                                        "Skipped: ${result.skipped}"
                        )
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
            } catch (ex: Exception) {
                (activity as AppCompatActivity).showFloatingSnackBar(
                        parent_view = requireView(),
                        text = "Label import failed : $ex"
                )
            }
        }
    }

    private fun showImportLabelsScanner() {
        val sheet = ImportLabelsScanBottomSheet()
        sheet.setOnLabelsDecodedListener { decoded -> importLabelsFromQr(decoded) }
        sheet.show(requireActivity().supportFragmentManager, sheet.tag)
    }

    private fun importLabelsFromQr(decoded: String) {
        settingsScope.launch {
            try {
                val result: ImportResult = withContext(Dispatchers.IO) {
                    bip329Importer.import(BufferedReader(StringReader(decoded)))
                }
                MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Labels imported")
                        .setMessage(
                                "Imported: " + result.imported + "\n" +
                                        "Updated: " + result.updated + "\n" +
                                        "Skipped: " + result.skipped
                        )
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
            } catch (ex: Exception) {
                (activity as AppCompatActivity).showFloatingSnackBar(
                        parent_view = requireView(),
                        text = "Label import failed : " + ex
                )
            }
        }
    }

    private fun exportLabelsTo(uri: Uri) {
        settingsScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openOutputStream(uri)!!.use { output ->
                        bip329Exporter.export(PrintWriter(output))
                    }
                }
                (activity as AppCompatActivity).showFloatingSnackBar(
                        parent_view = requireView(),
                        text = "Labels exported"
                )
            } catch (ex: Exception) {
                (activity as AppCompatActivity).showFloatingSnackBar(
                        parent_view = requireView(),
                        text = "Label export failed : $ex"
                )
            }
        }
    }

    override fun onDestroy() {
        settingsScope.cancel()
        kdfOverlay.dismiss()
        super.onDestroy()
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CODE_IMPORT_LABELS) {
            if (resultCode == Activity.RESULT_OK && data != null && data.data != null) {
                importLabelsFrom(data.data!!)
            }
            return
        }
        if (requestCode == REQ_CODE_EXPORT_LABELS) {
            if (resultCode == Activity.RESULT_OK && data != null && data.data != null) {
                exportLabelsTo(data.data!!)
            }
            return
        }
        if (requestCode == REQ_CODE_WRITE_BACKUP) {
            when (resultCode) {
                Activity.RESULT_OK -> if (data != null
                        && data.data != null) {
                    writeInFile(data.data!!)
                    (activity as AppCompatActivity).showFloatingSnackBar(
                            parent_view = requireView(),
                            text = "File written Successfully"
                    )
                }
                Activity.RESULT_CANCELED -> {
                }
            }
        }
    }

    private fun writeInFile(uri: Uri) {
        val outputStream: OutputStream
        try {
            outputStream = requireContext().contentResolver.openOutputStream(uri)!!
            val bw = BufferedWriter(OutputStreamWriter(outputStream))
            bw.write(exportedBackUp)
            bw.flush()
            bw.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    companion object {
        const val REQ_CODE_WRITE_BACKUP = 12
        const val REQ_CODE_IMPORT_LABELS = 13
        const val REQ_CODE_EXPORT_LABELS = 14
        const val TAG_PIN_ENTER = "lock_screen_pin_enter"
        const val TAG_PIN_CONFIRM = "lock_screen_pin_confirm"
        const val REQUEST_KEY_PIN_ENTER = "lock_screen_pin_enter_result"
        const val REQUEST_KEY_PIN_CONFIRM = "lock_screen_pin_confirm_result"
        const val TAG_PIN_GATE = "lock_screen_pin_gate"
        const val REQUEST_KEY_PIN_GATE = "lock_screen_pin_gate_result"
    }
}
