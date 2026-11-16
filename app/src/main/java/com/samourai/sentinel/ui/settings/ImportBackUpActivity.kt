package com.samourai.sentinel.ui.settings

import android.Manifest
import android.animation.StateListAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.transition.TransitionManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialSharedAxis
import com.samourai.sentinel.R
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.core.NetworkClassifier
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.core.access.AccessFactory
import com.samourai.sentinel.core.access.PinVault
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.db.appWalletPayloads
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.databinding.ActivityImportBackUpBinding
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.ui.SentinelActivity
import com.samourai.sentinel.ui.dojo.isAcceptableDojoPairingUrl
import com.samourai.sentinel.ui.dojo.validateDojoPairingPayload
import com.samourai.sentinel.ui.home.HomeActivity
import com.samourai.sentinel.ui.utils.AndroidUtil
import com.samourai.sentinel.ui.utils.PairingWordsAsset
import com.samourai.sentinel.ui.utils.PermissionResult
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.utils.enablePasteAction
import com.samourai.sentinel.ui.utils.enableRevealToggle
import com.samourai.sentinel.ui.utils.permissionResultOf
import com.samourai.sentinel.ui.utils.startDocumentPickerForResult
import com.samourai.sentinel.ui.utils.showFloatingSnackBar
import com.samourai.sentinel.ui.views.KdfProgressOverlay
import com.samourai.sentinel.ui.views.LockScreenDialog
import com.samourai.sentinel.util.ExportImportUtil
import com.samourai.sentinel.util.ImportLimits
import com.samourai.sentinel.util.ImportTooLargeException
import com.samourai.sentinel.util.PairingImportFailure
import com.samourai.sentinel.util.PairingWords
import com.samourai.sentinel.util.pairingImportUserMessage
import com.samourai.sentinel.util.readSamouraiPairingPayloadTrying
import com.samourai.sentinel.util.apiScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import java.io.FileNotFoundException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.IdentityHashMap
import java.util.Locale

class ImportBackUpActivity : SentinelActivity() {

    enum class ImportType {
        SAMOURAI,
        SENTINEL,
        SENTINEL_LEGACY
    }

    class ImportBackUpViewModel : ViewModel()

    private var payloadObject: JSONObject? = null
    private var importType = ImportType.SENTINEL
    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java)
    private var requireRestart = false
    private val viewModel: ImportBackUpViewModel by viewModels()
    private lateinit var binding: ActivityImportBackUpBinding
    private val repository: CollectionRepository by inject(CollectionRepository::class.java)
    private val apiService: ApiService by inject(ApiService::class.java)

    // #220: the import work captured by the pin pad. Set only while this
    // activity instance is alive. A recreated activity finds the pad and
    // no continuation, and closes the pad. The decrypted backup is not saved.
    private var importPinContinuation: ((String) -> Unit)? = null

    /** #355/#358: waiting room for the import Argon2id runs. */
    private val kdfOverlay by lazy { KdfProgressOverlay(this) }

    /** #404: the preview line's date format - local locale, local timezone. */
    private val backupPreviewDateFormat by lazy {
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    }

    // #80: real failures in the import launches must reach the
    // invokeOnCompletion reporting path, not the process. Masking
    // them as CancellationException destroyed type and stack.
    private val importExceptionHandler = CoroutineExceptionHandler { _, t ->
        Timber.e(t, "import failed")
    }

    /**
     * #420: the offer and the "backup PIN not applied" notice. Neither
     * is a fragment, so a recreated activity shows them again from
     * [onSaveInstanceState]. The PIN typed on the pad is not saved.
     */
    private var restorePinOfferDialog: AlertDialog? = null
    private var restorePinNoticeDialog: AlertDialog? = null
    private var offerPinPending = false
    private var pinNoticePending = false

    /**
     * #460: success draws "Imported ✓" on [importStartBtn] and a
     * restart snackbar anchored to that button, then opens the PIN
     * dialog. The activity window is transparent (swipe-back) and both
     * of those views are elevated, so they composite above the dialog
     * and cover Set PIN / Not now. While the dialog (or the pad it
     * opens) is up, the button is not drawn and the snackbar is gone.
     * [pendingImportSuccessMessage] is shown once that UI is gone.
     */
    private var pendingImportSuccessMessage: String? = null
    private var restorePinPadOpen = false
    private var importButtonHeld = false
    private var heldButtonVisibility = View.VISIBLE
    private var heldButtonAnimator: StateListAnimator? = null
    private var heldButtonElevation = 0f
    private var heldButtonTranslationZ = 0f

    /**
     * #420: first entry of the set-PIN pad. Memory of this activity
     * only, same rule as the settings confirm step (#220): a recreated
     * activity has none, and the confirm pad is closed.
     */
    private var pendingRestorePin: String? = null

    /**
     * #363 / #420: pads are added and removed without the back stack,
     * so a back-stack listener never sees them. View creation and
     * destruction cover show, dismiss, and recreation.
     */
    private val importPadLifecycle = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager,
            f: Fragment,
            v: View,
            savedInstanceState: Bundle?,
        ) {
            if (f is LockScreenDialog) schedulePadAccessibilitySync()
        }

        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            if (f is LockScreenDialog) schedulePadAccessibilitySync()
        }
    }

    override fun onCreateAdmitted(savedInstanceState: Bundle?) {
        binding = ActivityImportBackUpBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)
        // Registered before any pad transaction so a restored pad's
        // view is observed when it is created.
        supportFragmentManager.registerFragmentLifecycleCallbacks(importPadLifecycle, false)
        setSupportActionBar(binding.toolbarImportActivity)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        window.navigationBarColor = ContextCompat.getColor(this, R.color.v3_background)

        // #220: the pad posts here. No continuation means this screen was
        // rebuilt under a pad from the previous instance — close it.
        supportFragmentManager.setFragmentResultListener(
            REQUEST_KEY_IMPORT_PIN,
            this
        ) { _, bundle ->
            supportFragmentManager.clearFragmentResult(REQUEST_KEY_IMPORT_PIN)
            val continuation = importPinContinuation
            if (continuation == null) {
                dismissImportPinDialog()
            } else {
                continuation(bundle.getString(LockScreenDialog.RESULT_PIN).orEmpty())
            }
        }
        if (supportFragmentManager.findFragmentByTag(TAG_IMPORT_PIN) != null && importPinContinuation == null) {
            dismissImportPinDialog()
        }
        // #420: the set-PIN pad posts here. Confirm compares against
        // pendingRestorePin, which is not saved, so a restored confirm
        // pad is closed and the enter pad underneath can be used again.
        supportFragmentManager.setFragmentResultListener(
            REQUEST_KEY_RESTORE_PIN_SET,
            this
        ) { _, bundle ->
            supportFragmentManager.clearFragmentResult(REQUEST_KEY_RESTORE_PIN_SET)
            onRestorePinEntered(bundle.getString(LockScreenDialog.RESULT_PIN).orEmpty())
        }
        supportFragmentManager.setFragmentResultListener(
            REQUEST_KEY_RESTORE_PIN_CONFIRM,
            this
        ) { _, bundle ->
            supportFragmentManager.clearFragmentResult(REQUEST_KEY_RESTORE_PIN_CONFIRM)
            onRestorePinConfirmed(bundle.getString(LockScreenDialog.RESULT_PIN).orEmpty())
        }
        if (supportFragmentManager.findFragmentByTag(TAG_RESTORE_PIN_CONFIRM) != null &&
            pendingRestorePin == null
        ) {
            dismissRestorePinPad(TAG_RESTORE_PIN_CONFIRM)
        }
        // The window token is not ready inside onCreate. Posting the
        // dialog keeps a restored prompt on screen after rotation
        // without showing it before the activity has a window.
        if (savedInstanceState?.getBoolean(STATE_OFFER_PIN) == true) {
            offerPinPending = true
            binding.root.post {
                if (!isDestroyed && !isFinishing) showRestorePinOffer()
            }
        }
        if (savedInstanceState?.getBoolean(STATE_PIN_NOTICE) == true) {
            pinNoticePending = true
            binding.root.post {
                if (!isDestroyed && !isFinishing) showBackupPinNotApplied()
            }
        }

        // #282: the password field can be revealed (eye icon, which also
        // switches PASSWORD/VISIBLE_PASSWORD) and pasted into (no
        // suggestions, no autofill),
        // and a wrong password is reported on the field itself, cleared as
        // soon as the user edits it. This field also takes Sentinel backup
        // passwords the user chose, so it is never reshaped as they type.
        binding.importPasswordInputLayout.enableRevealToggle(binding.importPasswordInput)
        binding.importPasswordInputLayout.enablePasteAction(binding.importPasswordInput)
        binding.importPasswordInput.addTextChangedListener(afterTextChanged = {
            binding.importPasswordInputLayout.error = null
        })

        binding.importChoosePayloadBtn.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "*/*"
            startDocumentPickerForResult(intent, REQUEST_FILE_CODE)
            binding.importPayloadTextView.text = ""
        }

        binding.importPastePayloadBtn.setOnClickListener {
            val clipped = AndroidUtil.getClipBoardString(applicationContext) ?: return@setOnClickListener
            deliverImportPayload(clipped)
        }

        // #432: the camera is asked here, when SCAN is tapped, not when
        // this screen opens. Choose file and Paste do not need it.
        binding.importScanPayloadBtn.setOnClickListener {
            if (AndroidUtil.isPermissionGranted(Manifest.permission.CAMERA, applicationContext)) {
                showImportScanSheet()
            } else {
                askCameraPermission()
            }
        }

        binding.importStartBtn.isEnabled = false

        binding.importStartBtn.setOnClickListener {
            // #48: empty password is NO LONGER refused. Backups exported with
            // an empty password (before the export-side guard existed) are
            // valid AES payloads keyed on the empty string; refusing empty
            // here made them permanently unimportable. A wrong password on a
            // protected backup still fails honestly in decryptPayload().
            decryptPayload()
        }
        showImportButton(true)
        // After the startup hide, so a restored dialog does not save
        // the inflated VISIBLE and pop the button back on dismiss (#460).
        if (savedInstanceState?.getBoolean(STATE_OFFER_PIN) == true ||
            savedInstanceState?.getBoolean(STATE_PIN_NOTICE) == true ||
            savedInstanceState?.getBoolean(STATE_HOLD_IMPORT_CHROME) == true
        ) {
            holdImportChromeBehindDialog()
        }
    }

    /**
     * Registers xpubs with the configured server (#48).
     *
     * [pubs] null (the SENTINEL arm) registers every xpub in every
     * collection; the SENTINEL_LEGACY arm (#279) passes just the keys its
     * import brought in, so a merge does not re-register - and so re-scan -
     * the keys already on the device.
     *
     * Per-item containment: one failing registration must NOT cancel the
     * rest (this was the 2-of-3 partial import). Awaited by the caller so
     * the completion handler reports the real outcome. Returns
     * (imported, failed).
     */
    private suspend fun importAllXpubs(pubs: List<PubKeyModel>? = null): Pair<Int, Int> {
        var imported = 0
        var failed = 0
        val toRegister = pubs ?: ArrayList(repository.collectionsSnapshot()).flatMap { it.pubs }
        toRegister.forEach { pub ->
            try {
                apiService.importXpub(pub.pubKey, "bip${pub.getPurpose()}")
                imported++
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiService.ApiNotConfigured) {
                // #125: NOT a crash site - this launch carries importExceptionHandler
                // (:67), which already absorbed the class. The defect was reporting
                // fidelity: without this arm the throw skipped the per-item ladder and
                // aborted the loop, losing the (ok of ok+bad) partial tally at
                // :343-347, so an import that registered nothing could report clean.
                failed++
                Timber.e(e, "Failed to register xpub (bip${pub.getPurpose()})")
            } catch (e: Exception) {
                failed++
                Timber.e(e, "Failed to register xpub (bip${pub.getPurpose()})")
            }
        }
        return imported to failed
    }

    /**
     * Prefs may carry a second copy of the Dojo URL. Apply it only when
     * it meets the pairing rule. [com.samourai.sentinel.ui.dojo.DojoUtility.setDojo]
     * has already stored the pairing URL; an unacceptable prefs copy is left unused.
     */
    private fun applyImportedDojoEndpoints(prefs: JSONObject) {
        prefs.optString("apiEndPointTor").takeIf { isAcceptableDojoPairingUrl(it) }
            ?.let { prefsUtil.apiEndPointTor = it }
        prefs.optString("apiEndPoint").takeIf { isAcceptableDojoPairingUrl(it) }
            ?.let { prefsUtil.apiEndPoint = it }
    }

    /**
     * Dojo pairing leg (#48). DojoUtil.setDojo persists creds + endpoints
     * LOCALLY first, then makes ONE network auth call - which is what dies
     * with "unable to resolve host" while the Tor circuit is still
     * bootstrapping. So: wait for Tor readiness (bootstrap is legitimately
     * slow), retry the auth call boundedly, and report failure as a
     * PARTIAL (collections + prefs + creds already imported) instead of
     * letting one auth exception fail the whole import's report.
     *
     * Returns true when pairing succeeded (or there was nothing to do).
     */
    private suspend fun importDojoWithRetry(dojo: JSONObject?): Boolean {
        if (dojo == null) return true
        // Same gate as a scanned pairing. Fail before the Tor wait so a
        // payload with a blank credential or a plain-http host is not retried.
        if (!validateDojoPairingPayload(dojo.toString())) return false
        // #99: the bar now spans the Tor wait AND the bounded auth-retry
        // window. The old code hid it at ON - before repeat(3), where rounds
        // fail on a not-yet-usable circuit (10-02: two auth attempts failed
        // ~1s after ON; attempt 3 succeeded ~12.7s after ON).
        showTorWait(true)
        try {
            // start() stays pre-ON only: an already-running daemon must not
            // be re-started. An ON-but-proxy-less window just waits below.
            if (SentinelTorManager.getTorState().state != EnumTorState.ON) {
                // #170: pairing-leg start, no stomp on the user's toggle.
                SentinelTorManager.start()
            }
            // #10 precedent (pairing sheet): readiness is state==ON AND a
            // live SOCKS proxy - ON alone is not SOCKS-ready (10-02 D3).
            // The poll keeps updateTorProgressBar alive through the post-ON
            // climb, so the import screen now shows the climb too.
            val deadline = System.currentTimeMillis() + 60_000L
            while ((SentinelTorManager.getTorState().state != EnumTorState.ON ||
                    SentinelTorManager.getProxy() == null) &&
                System.currentTimeMillis() < deadline
            ) {
                updateTorProgressBar(SentinelTorManager.getTorState().progressIndicator)
                delay(500L)
            }
            val torReady = SentinelTorManager.getTorState().state == EnumTorState.ON &&
                SentinelTorManager.getProxy() != null
            if (!torReady) {
                Timber.e(
                    "dojo import: Tor not ready within 60s " +
                        "(state=${SentinelTorManager.getTorState().state}, " +
                        "proxyLive=${SentinelTorManager.getProxy() != null})"
                )
                return false
            }
            repeat(3) { attempt ->
                setTorStageLabel("Waiting for Tor circuit\u2026 (attempt ${attempt + 1} of 3)")
                try {
                    ExportImportUtil().importDojo(dojo)
                    return true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "dojo import auth attempt ${attempt + 1}/3 failed")
                }
                if (attempt < 2) delay(3_000L)
            }
            return false
        } finally {
            // Every exit hides the bar: Tor ready, the 60s deadline, or
            // cancellation. finally only hides views - it never swallows
            // the CE rethrow above (#80).
            showTorWait(false)
        }
    }

    /**
     * #99: retry-window stage on textView19 (the bar's own label). Main-
     * confined like every other view write on this screen (#80). Cleared
     * by showTorWait(false) in the finally above.
     */
    private fun setTorStageLabel(text: String) {
        runOnUiThread {
            binding.textView19.text = text
        }
    }

    /**
     * The import runs long (Tor wait up to 60s + network legs) with no
     * other feedback; without this the app reads as hung (#48 QA).
     * The start button doubles as the progress cue.
     */
    private fun setImportBusy(busy: Boolean) {
        // #80: completion handlers run on the completing thread (an IO
        // worker); confine the view writes to main.
        runOnUiThread {
            binding.importStartBtn.text = if (busy) "Importing\u2026" else "Import Sentinel Backup"
            binding.importStartBtn.isEnabled = !busy
        }
    }

    /**
     * #94: shows/hides the Tor bootstrap bar for the Dojo pairing wait.
     * Main-confined per setImportBusy (#80); every exit path hides
     * cleanly so no residue carries into later screens.
     */
    private fun showTorWait(show: Boolean) {
        runOnUiThread {
            val bar = binding.importTorProgressBar
            if (show) {
                bar.visibility = View.VISIBLE
            } else {
                bar.visibility = View.GONE
                // Re-arm indeterminate while GONE for the next wait.
                bar.isIndeterminate = true
                binding.textView19.text = ""
            }
        }
    }

    /**
     * #94: drives the Tor bar from the existing 500ms poll tick - no
     * second poller. Determinate while a bootstrap % exists (the mode
     * flip runs INVISIBLE-first, never on a VISIBLE indicator),
     * indeterminate below that; textView19 carries the %.
     */
    private fun updateTorProgressBar(progress: Int) {
        runOnUiThread {
            val bar = binding.importTorProgressBar
            if (progress in 1..99) {
                if (bar.isIndeterminate) {
                    bar.visibility = View.INVISIBLE
                    bar.isIndeterminate = false
                    bar.visibility = View.VISIBLE
                }
                bar.setProgressCompat(progress, true)
                binding.textView19.text = "Connecting via Tor\u2026 $progress%"
            } else {
                binding.textView19.text = "Connecting via Tor\u2026"
            }
        }
    }

    private fun showImportButton(hide: Boolean) {
        val sharedAxis = MaterialSharedAxis(MaterialSharedAxis.Y, !hide)
        TransitionManager.beginDelayedTransition(binding.importStartBtn.rootView as ViewGroup, sharedAxis)
        binding.importStartBtn.isEnabled = !hide
        binding.importStartBtn.visibility = if (hide) View.GONE else View.VISIBLE
    }

    private fun decryptPayload() {
        setImportBusy(true)
        when (importType) {
            ImportType.SENTINEL -> {
                // #355: Argon2id decrypt is seconds of dead screen; the
                // overlay brackets only the KDF - the button cue keeps
                // covering the long import legs after it.
                kdfOverlay.show("Decrypting backup\u2026")
                // #82: the decrypt (AES + PBKDF2) ran on the click
                // thread and stalled Main (Davey 2534ms, 227 skipped
                // frames). Run it on IO; setImportBusy(true) above
                // keeps the progress cue for the duration.
                viewModel.viewModelScope.launch(Dispatchers.Main + importExceptionHandler) {
                    var payload: Triple<ArrayList<PubKeyCollection>?, JSONObject, JSONObject?>? = null
                    val payloadText = payloadObject.toString()
                    val password = binding.importPasswordInput.text.toString()
                    try {
                        payload = withContext(Dispatchers.IO) {
                            ExportImportUtil().decryptSentinel(payloadText, password)
                        }
                    } catch (e: Exception) {
                        // #282: inline, on the field, with the text kept.
                        binding.importPasswordInputLayout.error =
                            getString(R.string.backup_password_wrong)
                    }

                    kdfOverlay.dismiss()
                    if (payload == null) {
                        setImportBusy(false)
                        return@launch
                    }
                    // #471: bound what a decrypted backup may write,
                    // before any planning or write touches the wallet.
                    val backupCollections = payload.first.orEmpty()
                    try {
                        ImportLimits.requireImportCounts(
                            backupCollections.size,
                            backupCollections.sumOf { it.pubs.size }
                        )
                    } catch (e: ImportTooLargeException) {
                        setImportBusy(false)
                        showFloatingSnackBar(
                            binding.importPastePayloadBtn, e.message.orEmpty(),
                            anchorView = binding.importStartBtn.id
                        )
                        return@launch
                    }

                    // #272: the backup door's network plan, computed
                    // post-decrypt, pre-write - and pre-PIN: no point
                    // asking for the PIN of an import that will be
                    // refused. Pure planner in its own file (the
                    // PairingNetworkDecision precedent), tick reads
                    // captured here on Main (#82 pattern).
                    val collectionsTicked = binding.importCollections.isChecked
                    val clearExisting = binding.importClearExisting.isChecked
                    val prefsTicked = binding.importPrefs.isChecked
                    val dojoTicked = binding.importDojo.isChecked
                    val currentTestnet = SentinelState.isTestNet()
                    val networkPlan = backupImportNetworkPlan(
                        currentTestnet = currentTestnet,
                        existingVotes = repository.collectionsSnapshot()
                            .flatMap { it.pubs }
                            .map { NetworkClassifier.fromXpub(it.pubKey) },
                        backupVotes = payload.first.orEmpty()
                            .flatMap { it.pubs }
                            .map { NetworkClassifier.fromXpub(it.pubKey) },
                        importCollections = collectionsTicked,
                        clearExisting = clearExisting,
                        importPrefs = prefsTicked,
                        backupPrefsTestnet = if (prefsTicked)
                            payload.second.optBoolean("testnet", currentTestnet)
                        else null,
                        dojoWhisper = if (dojoTicked)
                            NetworkClassifier.fromUrl(
                                payload.third?.optJSONObject("pairing")?.optString("url")
                            )
                        else null
                    )
                    if (networkPlan.refusal != null) {
                        setImportBusy(false)
                        showFloatingSnackBar(
                            binding.importPastePayloadBtn, networkPlan.refusal,
                            anchorView = binding.importStartBtn.id
                        )
                        return@launch
                    }

                    // Older backups include a PIN verifier. Confirm it before
                    // writing. A device that does not already have one then
                    // stores that PIN in the current key format.
                    // #420: a current-format backup has neither key. That
                    // restore does not set a PIN; the success path offers
                    // to set one when this install has none.
                    val legacyPinHash = payload.second.optString("pinHash", "")
                    if (backupCarriesLegacyPinVerifier(payload.second)) {
                        if (supportFragmentManager.findFragmentByTag(TAG_IMPORT_PIN) != null) {
                            setImportBusy(false)
                            return@launch
                        }
                        val lockScreenDialog = LockScreenDialog.newInstance(
                            message = "Enter pin code",
                            requestKey = REQUEST_KEY_IMPORT_PIN,
                            purpose = LockScreenDialog.PURPOSE_IMPORT
                        )
                        importPinContinuation = { entered ->
                            if (AccessFactory.getInstance(this@ImportBackUpActivity).validateHash(
                                    entered,
                                    legacyPinHash
                                )) {
                                importPinContinuation = null
                                lockScreenDialog.dismiss()
                                launchSentinelImport(payload, networkPlan, entered)
                            } else {
                                lockScreenDialog.showError()
                            }
                        }
                        supportFragmentManager.beginTransaction()
                            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                            .add(android.R.id.content, lockScreenDialog, TAG_IMPORT_PIN)
                            .commit()
                    } else {
                        launchSentinelImport(payload, networkPlan, freshPin = null)
                    }
                }
            }
            ImportType.SAMOURAI -> {
                // #81 residue: SAMOURAI was assigned by validatePayload
                // but never handled here - the start tap fell through
                // to the else branch's "choose a valid file" snackbar.
                // The parser is the same one the WalletPairingFragment
                // pairing sheet uses.
                // #82 pattern: EditText reads on Main, crypto on IO.
                val payloadText = payloadObject.toString()
                val password = binding.importPasswordInput.text.toString()
                viewModel.viewModelScope.launch(Dispatchers.Main + importExceptionHandler) {
                    val outcome = withContext(Dispatchers.IO) {
                        // #282: exactly as typed first. A pairing-format
                        // payload's password is three list words, so only if
                        // that fails to decrypt is the canonical form tried.
                        // A password that opens the ciphertext is kept even
                        // when the plaintext does not parse (#399) — trying
                        // the next candidate used to report that as a wrong
                        // password. #431: the canonical form also re-joins
                        // words that were typed with no separators at all.
                        val canonical = PairingWordsAsset.load(this@ImportBackUpActivity)
                            ?.canonical(password)
                            ?: PairingWords.normalize(password)
                        readSamouraiPairingPayloadTrying(
                            payloadText,
                            listOf(password, canonical)
                        )
                    }
                    val collection = outcome.collection
                    if (collection == null) {
                        setImportBusy(false)
                        // #399: only a failed decrypt is a wrong password.
                        // The shared reader names every other outcome.
                        if (outcome.failure == PairingImportFailure.DECRYPT_FAILED) {
                            binding.importPasswordInputLayout.error =
                                getString(R.string.backup_password_wrong)
                            this@ImportBackUpActivity.showFloatingSnackBar(
                                binding.importPayloadTextView.parent as ViewGroup,
                                text = "Import failed - check the password, or use the wallet's Pairing tool for watch-only setup.",
                                duration = Snackbar.LENGTH_SHORT
                            )
                        } else {
                            val message = pairingImportUserMessage(this@ImportBackUpActivity, outcome)
                                ?: getString(R.string.pairing_import_unrecognised)
                            binding.importPasswordInputLayout.error = message
                            this@ImportBackUpActivity.showFloatingSnackBar(
                                binding.importPayloadTextView.parent as ViewGroup,
                                text = message,
                                duration = Snackbar.LENGTH_LONG
                            )
                        }
                        return@launch
                    }
                    val importNote = pairingImportUserMessage(this@ImportBackUpActivity, outcome)
                    // #272: the same door plan for the Samourai arm -
                    // these imports always merge (replace=false), so an
                    // established wallet plus a cross-network backup is
                    // a hard refusal; an empty wallet announces the flip.
                    val currentTestnet = SentinelState.isTestNet()
                    val networkPlan = backupImportNetworkPlan(
                        currentTestnet = currentTestnet,
                        existingVotes = repository.collectionsSnapshot()
                            .flatMap { it.pubs }
                            .map { NetworkClassifier.fromXpub(it.pubKey) },
                        backupVotes = collection.pubs
                            .map { NetworkClassifier.fromXpub(it.pubKey) },
                        importCollections = true,
                        clearExisting = false,
                        importPrefs = false,
                        backupPrefsTestnet = null,
                        dojoWhisper = null
                    )
                    if (networkPlan.refusal != null) {
                        setImportBusy(false)
                        showFloatingSnackBar(
                            binding.importPastePayloadBtn, networkPlan.refusal,
                            anchorView = binding.importStartBtn.id
                        )
                        return@launch
                    }
                    viewModel.viewModelScope.launch(Dispatchers.IO + importExceptionHandler) {
                        try {
                            ExportImportUtil().startImportCollections(
                                arrayListOf(collection),
                                false
                            )
                        } catch (e: Exception) {
                            Timber.e("backup-import %s", e.javaClass.simpleName)
                            throw e
                        }
                    }.invokeOnCompletion {
                        runOnUiThread {
                            if (it == null) {
                                binding.importStartBtn.text = "Imported \u2713"
                                requireRestart = true
                                val note = importNote?.let { " $it" }.orEmpty()
                                showFloatingSnackBar(
                                    binding.importPastePayloadBtn, "Successfully imported collection.$note${networkPlan.flipNote.orEmpty()}",
                                    anchorView = binding.importStartBtn.id,
                                    actionText = "restart",
                                    actionClick = { restart() }
                                )
                            } else if (it is CancellationException) {
                                // Scope teardown, not a failure (#80).
                                setImportBusy(false)
                            } else {
                                setImportBusy(false)
                                showFloatingSnackBar(
                                    binding.importPastePayloadBtn,
                                    "Error: ${it.message}",
                                    anchorView = binding.importStartBtn.id
                                )
                            }
                        }
                    }
                }
            }
            ImportType.SENTINEL_LEGACY -> {
                // #82 pattern: capture the EditText reads on Main (the
                // click thread) before the IO hop.
                val payloadText = payloadObject.toString()
                val password = binding.importPasswordInput.text.toString()
                // #272: hoisted for the completion handler - a refusal
                // is the import's outcome (not an error, not a success).
                var networkRefusal: String? = null
                var networkFlipNote: String? = null
                // #276: the dojo leg's completion state - the SENTINEL
                // arm's hoisted pattern, mirrored for this arm.
                var dojoPairingFailed = false
                var networkDojoSets = false
                var networkDojoObjection: String? = null
                var networkDojoSetNote: String? = null
                // #279: the registration tally for a backup with no Dojo.
                var xpubImport: Pair<Int, Int>? = null
                viewModel.viewModelScope.launch(Dispatchers.IO + importExceptionHandler) {
                    try {
                        val payload = ExportImportUtil().decryptSentinelLegacy(
                                payloadText,
                                password
                        )
                        val pubKeys = payload.first
                        // #471: bounded before any write.
                        ImportLimits.requireImportCounts(1, pubKeys.size)
                        // #276: the validated dojo (decryptSentinelLegacy
                        // returns it as the pair's second) is no longer
                        // dropped at this door - it gets its leg below.
                        val dojo = payload.second
                        if (pubKeys.isNotEmpty()) {
                            val collection = PubKeyCollection()
                            collection.pubs = pubKeys
                            collection.collectionLabel = "Sentinel Import"
                            // #272: the door plan, post-decrypt pre-write.
                            // Legacy imports always merge (no clear tick on
                            // this arm), carry no prefs - and their dojo
                            // leg rides the plan below (#276).
                            val networkPlan = backupImportNetworkPlan(
                                currentTestnet = SentinelState.isTestNet(),
                                existingVotes = repository.collectionsSnapshot()
                                    .flatMap { it.pubs }
                                    .map { NetworkClassifier.fromXpub(it.pubKey) },
                                backupVotes = pubKeys
                                    .map { NetworkClassifier.fromXpub(it.pubKey) },
                                importCollections = true,
                                clearExisting = false,
                                importPrefs = false,
                                backupPrefsTestnet = null,
                                // #276: the backup-borne dojo rides the
                                // same URL whisper as the SENTINEL arm.
                                dojoWhisper = NetworkClassifier.fromUrl(
                                    dojo?.optJSONObject("pairing")?.optString("url")
                                )
                            )
                            if (networkPlan.refusal != null) {
                                networkRefusal = networkPlan.refusal
                            } else {
                                networkFlipNote = networkPlan.flipNote
                                networkDojoSets = networkPlan.dojoSetsNetwork
                                networkDojoObjection = networkPlan.dojoObjection
                                networkDojoSetNote = networkPlan.dojoSetNote
                                ExportImportUtil().startImportCollections(
                                        arrayListOf(collection),
                                        false
                                )
                                // #276: the parsed-and-validated dojo is no
                                // longer dropped on the floor here - the
                                // same leg, the same helper and the same
                                // #273 decision table as the SENTINEL arm.
                                // #279: a backup's Dojo plays the role of the
                                // SENTINEL arm's ticked "Import Dojo" box.
                                if (dojo != null) {
                                    if (networkPlan.importDojo) {
                                        if (importDojoWithRetry(dojo)) {
                                            if (networkPlan.dojoSetsNetwork) {
                                                prefsUtil.testnet = networkPlan.postImportTestnet
                                            }
                                        } else {
                                            dojoPairingFailed = true
                                        }
                                    }
                                    // A refused Dojo (cross-network) leaves its
                                    // objection on the completion note and
                                    // registers nothing - as in the SENTINEL arm.
                                } else {
                                    // #279: no Dojo in the backup - register the
                                    // imported keys with the server this device
                                    // already uses, the SENTINEL arm's
                                    // dojo-unticked path (#48), instead of
                                    // leaving balances waiting on a manual retry
                                    // from the Network screen.
                                    xpubImport = importAllXpubs(pubKeys)
                                }
                            }
                        } else {
                            throw IllegalStateException("0 public keys found")
                        }
                    } catch (e: Exception) {
                        Timber.e("backup-import %s", e.javaClass.simpleName)
                        throw e
                    }
                }
                        .invokeOnCompletion {
                            runOnUiThread {
                            val refusalMsg = networkRefusal
                            if (refusalMsg != null) {
                                // #272: the refusal IS the outcome - not a
                                // success toast, not an error stack.
                                setImportBusy(false)
                                showFloatingSnackBar(
                                        binding.importPastePayloadBtn, refusalMsg,
                                        anchorView = binding.importStartBtn.id
                                )
                            } else if (it == null) {
                                binding.importStartBtn.text = "Imported \u2713"
                                requireRestart = true
                                // #276: the dojo leg's outcome rides the
                                // completion note - the SENTINEL arm's
                                // runtime composition, mirrored.
                                val networkNote =
                                    if (networkDojoSets && !dojoPairingFailed)
                                        networkDojoSetNote.orEmpty()
                                    else
                                        networkFlipNote.orEmpty() +
                                                networkDojoObjection.orEmpty()
                                // #279: same registration tally as the
                                // SENTINEL arm.
                                val xpubNote = xpubImport?.let { (ok, bad) ->
                                    xpubRegistrationNote(ok, bad)
                                } ?: ""
                                val note = xpubNote +
                                    (if (dojoPairingFailed)
                                        " Dojo pairing failed - retry from Network screen."
                                    else "") + networkNote
                                showFloatingSnackBar(
                                        binding.importPastePayloadBtn, "Successfully imported collection.$note",
                                        anchorView = binding.importStartBtn.id,
                                        actionText = "restart",
                                        actionClick = { restart() }
                                )
                            } else if (it is CancellationException) {
                                // Scope teardown, not a failure (#80).
                                setImportBusy(false)
                            } else {
                                setImportBusy(false)
                                showFloatingSnackBar(
                                        binding.importPastePayloadBtn,
                                        "Error: ${it.message}",
                                        anchorView = binding.importStartBtn.id
                                )
                            }
                            }
                        }
            }
            else -> {
                setImportBusy(false)
                showFloatingSnackBar(binding.importPastePayloadBtn, "Please choose a valid Sentinel backup file")
            }
        }
    }

    /**
     * Writes a decrypted Sentinel backup.
     *
     * Shared by the verifier and no-verifier arms of the legacy pinHash
     * branch. [freshPin] is set when that backup still has a verifier.
     * The shared Main hop is where the KDF overlay (#358) comes down,
     * before the Tor and network legs.
     */
    private fun launchSentinelImport(
        payload: Triple<ArrayList<PubKeyCollection>?, JSONObject, JSONObject?>,
        networkPlan: BackupImportNetworkPlan,
        freshPin: String?,
    ) {
        // Hoisted out of the launch body: invokeOnCompletion below is
        // outside that scope and must close over these (#48).
        var xpubImport: Pair<Int, Int>? = null
        var dojoPairingFailed = false
        var customExplorerRejected = false
        // #420: captured before protectFreshImport. A legacy verifier on
        // a keyless install enables during the import, and reading PIN
        // state again at the end would hide the "already had a PIN" case
        // and would also offer a second PIN after the first was just set.
        val pinFollowUp = restorePinFollowUp(
            legacyVerifierAccepted = freshPin != null,
            deviceHadPin = !isKeylessInstall(
                prefsUtil.isPinConfigured(),
                AccessFactory.getInstance(null).dataKey,
            ),
        )
        viewModel.viewModelScope.launch(Dispatchers.IO + importExceptionHandler) {
            try {
                if (freshPin != null) {
                    // #358: protectFreshImport derives the PIN-profile
                    // Argon2id key when this device has no data key yet.
                    // The bracket shows the waiting room for that call
                    // and dismisses it on success, failure, and cancellation.
                    protectImportedPin(
                        show = { kdfOverlay.show(it) },
                        dismiss = { kdfOverlay.dismiss() },
                    ) {
                        PinVault(prefsUtil).protectFreshImport(
                            freshPin,
                            appWalletPayloads(filesDir)
                        )
                    }
                }
                withContext(Dispatchers.Main) {
                    // #358: the derive is over. Dismiss here as well
                    // (idempotent, and a no-op when no PIN was saved)
                    // before the Tor and network legs, which keep the
                    // button cue. onDestroy covers the window while
                    // the blocking derive is still running.
                    kdfOverlay.dismiss()
                    binding.importPasswordInputLayout.visibility = View.INVISIBLE
                    binding.importSentinelBackUpLayout.visibility = View.VISIBLE
                    binding.importCollections.text =
                        binding.importCollections.text.toString().substringBefore(" (") +
                            " (${payload.first?.size})"
                }

                if (binding.importPrefs.isChecked) {
                    customExplorerRejected = ExportImportUtil().importPrefs(payload.second)
                }

                if (binding.importCollections.isChecked) {
                    payload.first?.let {
                        ExportImportUtil().startImportCollections(
                            it,
                            binding.importClearExisting.isChecked
                        )
                    }
                }
                if (binding.importDojo.isChecked) {
                    // #272: the plan may refuse the backup-borne
                    // Dojo (cross-network whisper) - the loud
                    // objection rides the completion snackbar
                    // and the pairing leg is skipped.
                    if (networkPlan.importDojo && importDojoWithRetry(payload.third)) {
                        applyImportedDojoEndpoints(payload.second)
                        // #273's PROCEED_AND_SET_NETWORK arm: the
                        // import tap was the consent, and the
                        // backup's Dojo is the first
                        // network-bearing input.
                        if (networkPlan.dojoSetsNetwork) {
                            prefsUtil.testnet = networkPlan.postImportTestnet
                        }
                    } else if (networkPlan.importDojo) {
                        dojoPairingFailed = true
                    }
                } else {
                    xpubImport = importAllXpubs()
                }
            } catch (e: Exception) {
                // #358: a failure before the Main hop must not leave the
                // overlay up. Posted rather than withContext: this catch
                // is also the cancellation path, and a suspending hop on
                // a cancelled job would skip the dismiss.
                runOnUiThread { kdfOverlay.dismiss() }
                Timber.e("backup-import %s", e.javaClass.simpleName)
                throw e
            }
        }.invokeOnCompletion {
            runOnUiThread {
                if (it == null) {
                    val xpubNote = xpubImport?.let { (ok, bad) ->
                        xpubRegistrationNote(ok, bad)
                    } ?: ""
                    // #272: network notes, runtime-composed -
                    // a successful will-set leg supersedes the
                    // interim prefs/keychain flip note; a failed
                    // one leaves it standing.
                    val networkNote =
                        if (networkPlan.dojoSetsNetwork && !dojoPairingFailed)
                            networkPlan.dojoSetNote.orEmpty()
                        else
                            networkPlan.flipNote.orEmpty() +
                                networkPlan.dojoObjection.orEmpty()
                    val importNoun =
                        if (binding.importCollections.isChecked) " collection(s)" else ""
                    val note = "$xpubNote" +
                        (if (dojoPairingFailed)
                            " Dojo pairing failed - retry from Network screen."
                        else "") +
                        (if (customExplorerRejected)
                            " The custom block explorer was not used. Choose one in Settings."
                        else "") +
                        networkNote
                    // #460: the restart snackbar is anchored to the bottom
                    // button. Showing it before the PIN dialog covers the
                    // dialog actions. The dialog is shown first.
                    presentSentinelImportSuccess(
                        "Successfully imported$importNoun.$note",
                        pinFollowUp,
                    )
                } else if (it is CancellationException) {
                    // Scope teardown, not a failure (#80).
                    setImportBusy(false)
                } else {
                    setImportBusy(false)
                    Timber.e(it)
                    showFloatingSnackBar(
                        binding.importPastePayloadBtn,
                        "Error: ${it.message}",
                        anchorView = binding.importStartBtn.id
                    )
                }
            }
        }
    }

    private fun restart() {
        startActivity(Intent(this, HomeActivity::class.java).apply {
            // #528: CLEAR_TASK is ignored unless NEW_TASK is set too.
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
        overridePendingTransition(R.anim.fade_in, R.anim.bottom_sheet_slide_out)
        finish()
    }

    /**
     * Validates backup payloads
     * Method uses coroutines to parse json
     */
    private fun validatePayload(string: String) {
        viewModel.viewModelScope.launch(Dispatchers.Default + importExceptionHandler) {
            try {
                val json = JSONObject(string)
                withContext(Dispatchers.Main) {
                    // #81 residue: drop any previously-validated payload
                    // before classifying the new one - a failed or
                    // different-type re-pick must not leave the old
                    // payload armed or the Sentinel checkbox panel
                    // visible.
                    payloadObject = null
                    importType = ImportType.SENTINEL
                    binding.importSentinelBackUpLayout.visibility = View.GONE
                    if (json.has("external") && json.has("payload")) {
                        payloadObject = json
                        importType = ImportType.SAMOURAI
                        showImportButton(false)
                    } else if (json.has("time") && json.has("payload")) {
                        payloadObject = json
                        importType = ImportType.SENTINEL
                        showImportButton(false)
                        binding.importSentinelBackUpLayout.visibility = View.VISIBLE
                    } else if (json.has("payload")) {
                        payloadObject = json
                        importType = ImportType.SENTINEL_LEGACY
                        showImportButton(false)
                    } else {
                        // #81 residue: showImportButton(false) SHOWS the
                        // start button; an invalid payload must restore
                        // the hidden baseline, not arm it.
                        showImportButton(true)
                        showFloatingSnackBar(binding.importStartBtn, text = "Invalid payload")
                    }
                    // #404 (decision 7)'s calm preview line stays; the raw
                    // envelope returns BENEATH it. The pre-#405 dump
                    // carried real distinguishing value (format, kdf era,
                    // app version, blob size) that a date-only line cannot;
                    // an old backup's date-only line reads as "the data is
                    // gone". The dump truncates the base64 blob to head +
                    // size, and nothing decrypts - every value is
                    // envelope-level, the ciphertext stays ciphertext.
                    // Rendered only for CLASSIFIED envelopes: a parseable-
                    // but-unrecognized object must not read as a valid
                    // backup - the else branch's snackbar is its only
                    // verdict. File pick, paste, and an accepted scan clear
                    // the view before validating, so an invalid re-pick
                    // leaves the card blank, never stale.
                    if (payloadObject != null) {
                        binding.importPayloadTextView.text =
                            backupPreviewLine(json, backupPreviewDateFormat) +
                                "\n\n" + backupEnvelopeDump(json)
                    }
                }
            } catch (e: Exception) {
                throw e
            }
        }.invokeOnCompletion {
            if (it != null && it !is CancellationException) {
                Timber.e(it)
            }
        }
    }


    /**
     * PASTE and an accepted SCAN both land here, then in validatePayload.
     * The text view is cleared first, matching the old paste click, so a
     * rejected or in-flight candidate never sits in the card.
     */
    private fun deliverImportPayload(text: String) {
        binding.importPayloadTextView.text = ""
        when (val intake = intakeImportPayload(text)) {
            ImportPayloadIntake.Ignore -> Unit
            ImportPayloadIntake.TooLarge ->
                showFloatingSnackBar(binding.importPastePayloadBtn, text = importTooLargeMessage())
            is ImportPayloadIntake.Accept -> validatePayload(intake.text)
        }
    }

    /**
     * One admitted camera decode. Blank keeps the sheet open. Anything
     * else closes it: over-cap and accepted text use [deliverImportPayload],
     * and a decode validatePayload would not arm shows one neutral
     * message and drops a previously armed payload (#232, #81).
     * The decoded text is not logged.
     *
     * @return true when the sheet should close and the scanner should stop.
     */
    internal fun onScannedImportPayload(text: String): Boolean {
        return when (val decision = decideImportScan(text)) {
            ImportScanDecision.Ignore -> false
            ImportScanDecision.TooLarge -> {
                deliverImportPayload(text)
                true
            }
            ImportScanDecision.Unrecognized -> {
                disarmImportPayload()
                showFloatingSnackBar(binding.importPastePayloadBtn, text = IMPORT_SCAN_UNRECOGNIZED)
                true
            }
            is ImportScanDecision.Accept -> {
                deliverImportPayload(decision.text)
                true
            }
        }
    }

    /** A failed scan must not leave the previous envelope armed or on screen. */
    private fun disarmImportPayload() {
        payloadObject = null
        importType = ImportType.SENTINEL
        binding.importPayloadTextView.text = ""
        binding.importSentinelBackUpLayout.visibility = View.GONE
        showImportButton(true)
    }

    internal fun showImportScanSheet() {
        if (isFinishing || isDestroyed) return
        if (supportFragmentManager.findFragmentByTag(ImportPayloadScanBottomSheet.TAG) != null) return
        ImportPayloadScanBottomSheet().show(
            supportFragmentManager,
            ImportPayloadScanBottomSheet.TAG,
        )
    }

    /**
     * The rationale's NO is not a pubkey add on this screen. Decline
     * and a system denial both snackbar; the buttons stay usable.
     */
    override fun onCameraRationaleDeclined() {
        showCameraPermissionDenied()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_PERMISSION) return
        when (permissionResultOf(grantResults)) {
            PermissionResult.GRANTED -> showImportScanSheet()
            PermissionResult.DENIED -> showCameraPermissionDenied()
            PermissionResult.CANCELLED -> Unit
        }
    }

    private fun showCameraPermissionDenied() {
        showFloatingSnackBar(
            binding.importScanPayloadBtn,
            text = getString(R.string.camera_permission_denied),
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (data != null && data.data != null && data.data!!.path != null && requestCode == REQUEST_FILE_CODE) {
            val job =
                    viewModel.viewModelScope.launch(Dispatchers.Main + importExceptionHandler) {
                        try {
                            // #82: up to 5MB of file I/O ran on Main in
                            // the picker result; the read (size guard
                            // included) belongs on IO.
                            // #471: available() is not the document size;
                            // count what is read and stop at limit + 1.
                            val string = withContext(Dispatchers.IO) {
                                val inputStream = contentResolver.openInputStream(data.data!!)
                                    ?: throw FileNotFoundException("Unable to open file")
                                inputStream.use {
                                    ImportLimits.readBoundedText(it, ImportLimits.BACKUP_MAX_BYTES)
                                }
                            }
                            withContext(Dispatchers.Main) {
                                validatePayload(string)
                            }
                        } catch (fn: FileNotFoundException) {
                            Timber.e("backup-read %s", fn.javaClass.simpleName)
                            throw fn
                        } catch (ioe: IOException) {
                            Timber.e("backup-read %s", ioe.javaClass.simpleName)
                            throw ioe
                        } catch (ex: Exception) {
                            Timber.e("backup-read %s", ex.javaClass.simpleName)
                            throw ex
                        }
                    }
            job.invokeOnCompletion {
                if (it != null && it !is CancellationException) {
                    this.showFloatingSnackBar(binding.importPastePayloadBtn, "Error ${it.message}")
                }
            }
        }
    }

    /**
     * #420: after a successful Sentinel import. [followUp] was decided
     * before the write, so a PIN installed by the legacy verifier does
     * not also open the set-PIN offer.
     *
     * #460: the bottom import button and the restart snackbar are not
     * drawn while the follow-up dialog is up. [message] is the success
     * snackbar copy, shown once that dialog (and the set-PIN pad, when
     * the user opens it) has closed.
     */
    internal fun presentSentinelImportSuccess(message: String, followUp: RestorePinFollowUp) {
        binding.importStartBtn.text = "Imported \u2713"
        requireRestart = true
        when (followUp) {
            RestorePinFollowUp.NONE -> showImportRestartSnackBar(message)
            RestorePinFollowUp.OFFER_SET,
            RestorePinFollowUp.NOTICE_KEPT -> {
                pendingImportSuccessMessage = message
                applyRestorePinFollowUp(followUp)
                if (restorePinOfferDialog?.isShowing != true &&
                    restorePinNoticeDialog?.isShowing != true &&
                    pendingImportSuccessMessage != null
                ) {
                    val deferred = pendingImportSuccessMessage
                    pendingImportSuccessMessage = null
                    if (deferred != null) showImportRestartSnackBar(deferred)
                }
            }
        }
    }

    /**
     * #420: after a successful Sentinel import. [followUp] was decided
     * before the write, so a PIN installed by the legacy verifier does
     * not also open the set-PIN offer.
     */
    internal fun applyRestorePinFollowUp(followUp: RestorePinFollowUp) {
        when (followUp) {
            RestorePinFollowUp.OFFER_SET -> showRestorePinOffer()
            RestorePinFollowUp.NOTICE_KEPT -> showBackupPinNotApplied()
            RestorePinFollowUp.NONE -> Unit
        }
    }

    private fun showRestorePinOffer() {
        if (restorePinOfferDialog?.isShowing == true) {
            offerPinPending = true
            return
        }
        if (!isKeylessInstall(
                prefsUtil.isPinConfigured(),
                AccessFactory.getInstance(null).dataKey,
            )
        ) {
            offerPinPending = false
            releaseImportChromeBehindDialog()
            return
        }
        holdImportChromeBehindDialog()
        offerPinPending = true
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.restore_pin_offer_title)
            .setMessage(R.string.restore_pin_offer_message)
            .setPositiveButton(R.string.restore_pin_set) { _, _ ->
                offerPinPending = false
                showRestoreSetPinPad()
            }
            .setNegativeButton(R.string.restore_pin_not_now) { _, _ ->
                offerPinPending = false
            }
            .setOnCancelListener { offerPinPending = false }
            .setOnDismissListener {
                // Set PIN opens the pad before this runs. Not now and
                // cancel do not, so the success snackbar can return.
                if (!restorePinPadOpen) {
                    releaseImportChromeBehindDialog()
                }
            }
            .create()
        restorePinOfferDialog = dialog
        showAboveImportChrome(dialog)
    }

    private fun showBackupPinNotApplied() {
        if (restorePinNoticeDialog?.isShowing == true) {
            pinNoticePending = true
            return
        }
        holdImportChromeBehindDialog()
        pinNoticePending = true
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.restore_pin_kept_title)
            .setMessage(R.string.restore_pin_kept_message)
            .setPositiveButton(R.string.ok, null)
            .setOnDismissListener {
                pinNoticePending = false
                releaseImportChromeBehindDialog()
            }
            .create()
        restorePinNoticeDialog = dialog
        showAboveImportChrome(dialog)
    }

    /**
     * #460: floating dialog above the import screen. The theme leaves
     * dimming off; turn it on for this window and lift it above the
     * bottom button and the snackbar.
     */
    private fun showAboveImportChrome(dialog: AlertDialog) {
        dialog.show()
        dialog.window?.let { window ->
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setDimAmount(0.32f)
            val px = 32f * resources.displayMetrics.density
            window.setElevation(px)
            window.decorView.elevation = px
        }
    }

    private fun showImportRestartSnackBar(message: String) {
        showFloatingSnackBar(
            binding.importPastePayloadBtn,
            message,
            anchorView = binding.importStartBtn.id,
            actionText = "restart",
            actionClick = { restart() },
        )
    }

    private fun holdImportChromeBehindDialog() {
        dismissFloatingSnackBars()
        if (importButtonHeld) return
        val button = binding.importStartBtn
        importButtonHeld = true
        heldButtonVisibility = button.visibility
        heldButtonAnimator = button.stateListAnimator
        heldButtonElevation = button.elevation
        heldButtonTranslationZ = button.translationZ
        button.stateListAnimator = null
        button.elevation = 0f
        button.translationZ = 0f
        // INVISIBLE keeps the button's space so the screen does not jump,
        // and it is not drawn over the dialog actions.
        button.visibility = View.INVISIBLE
    }

    private fun releaseImportChromeBehindDialog() {
        if (importButtonHeld) {
            val button = binding.importStartBtn
            button.visibility = heldButtonVisibility
            button.elevation = heldButtonElevation
            button.translationZ = heldButtonTranslationZ
            button.stateListAnimator = heldButtonAnimator
            importButtonHeld = false
        }
        val message = pendingImportSuccessMessage ?: return
        pendingImportSuccessMessage = null
        if (isFinishing || isDestroyed) return
        // Next loop, after the dialog window is gone. Showing it inside
        // the dismiss listener puts the restart action over the dialog.
        binding.root.post {
            if (!isFinishing && !isDestroyed) showImportRestartSnackBar(message)
        }
    }

    private fun dismissFloatingSnackBars() {
        val decor = window?.decorView ?: return
        val bars = LinkedHashSet<Snackbar>()
        (decor.getTag(R.id.floating_snackbar) as? Snackbar)?.let { bars.add(it) }
        decor.setTag(R.id.floating_snackbar, null)
        collectTaggedSnackBars(decor, bars)
        bars.forEach { bar ->
            // Hide before dismiss so the exit slide cannot cross the dialog.
            bar.view.visibility = View.GONE
            bar.dismiss()
        }
    }

    private fun collectTaggedSnackBars(view: View, bars: MutableSet<Snackbar>) {
        (view.getTag(R.id.floating_snackbar) as? Snackbar)?.let { bars.add(it) }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collectTaggedSnackBars(view.getChildAt(i), bars)
            }
        }
    }

    private fun showRestoreSetPinPad() {
        // Set before the offer dialog dismisses, so that dismiss does not
        // put the restart snackbar on top of this pad (#460).
        restorePinPadOpen = true
        if (supportFragmentManager.findFragmentByTag(TAG_RESTORE_PIN_SET) != null) return
        val dialog = LockScreenDialog.newInstance(
            message = getString(R.string.create_pin),
            requestKey = REQUEST_KEY_RESTORE_PIN_SET,
            purpose = LockScreenDialog.PURPOSE_SET,
        )
        supportFragmentManager.beginTransaction()
            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
            .add(android.R.id.content, dialog, TAG_RESTORE_PIN_SET)
            .commit()
    }

    private fun onRestorePinEntered(pin: String) {
        val dialog = supportFragmentManager.findFragmentByTag(TAG_RESTORE_PIN_SET) as? LockScreenDialog
            ?: return
        if (dialog.purpose != LockScreenDialog.PURPOSE_SET) return
        pendingRestorePin = pin
        if (supportFragmentManager.findFragmentByTag(TAG_RESTORE_PIN_CONFIRM) != null) return
        val confirm = LockScreenDialog.newInstance(
            message = getString(R.string.confirm_pin),
            requestKey = REQUEST_KEY_RESTORE_PIN_CONFIRM,
            purpose = LockScreenDialog.PURPOSE_CONFIRM,
        )
        supportFragmentManager.beginTransaction()
            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
            .add(android.R.id.content, confirm, TAG_RESTORE_PIN_CONFIRM)
            .commit()
    }

    private fun onRestorePinConfirmed(pin: String) {
        val dialog = supportFragmentManager.findFragmentByTag(TAG_RESTORE_PIN_CONFIRM) as? LockScreenDialog
        val first = pendingRestorePin
        if (dialog == null || dialog.purpose != LockScreenDialog.PURPOSE_CONFIRM || first == null) {
            pendingRestorePin = null
            dismissRestorePinPad(TAG_RESTORE_PIN_CONFIRM)
            return
        }
        if (pin != first) {
            Toast.makeText(this, getString(R.string.restore_pin_mismatch), Toast.LENGTH_SHORT).show()
            dialog.showError()
            return
        }
        pendingRestorePin = null
        val chosen = first
        dismissRestorePinPads()
        if (!isKeylessInstall(
                prefsUtil.isPinConfigured(),
                AccessFactory.getInstance(null).dataKey,
            )
        ) {
            // The pad is removed with commit(), which runs after this
            // returns. Release once it is gone so the button does not
            // draw over the last frame of the pad.
            binding.root.post {
                restorePinPadOpen = false
                releaseImportChromeBehindDialog()
            }
            return
        }
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            try {
                protectImportedPin(
                    show = { kdfOverlay.show(it) },
                    dismiss = { kdfOverlay.dismiss() },
                ) {
                    enablePinOnKeylessInstall(
                        PinVault(prefsUtil),
                        chosen,
                        appWalletPayloads(filesDir),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The PIN value, its verifier, and the wrapped key stay
                // out of the log. The failure class is enough to tell a
                // wrap error from a payload rewrite.
                Timber.w("Could not save PIN after restore (${e.javaClass.simpleName})")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@ImportBackUpActivity,
                        getString(R.string.restore_pin_save_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } finally {
                // After the saving dialog is gone. Doing it earlier puts
                // the restart snackbar over that dialog (#460).
                withContext(Dispatchers.Main + NonCancellable) {
                    restorePinPadOpen = false
                    releaseImportChromeBehindDialog()
                }
            }
        }
    }

    private fun dismissRestorePinPad(tag: String) {
        val existing = supportFragmentManager.findFragmentByTag(tag) ?: return
        supportFragmentManager.beginTransaction().remove(existing).commit()
    }

    private fun dismissRestorePinPads() {
        dismissRestorePinPad(TAG_RESTORE_PIN_CONFIRM)
        dismissRestorePinPad(TAG_RESTORE_PIN_SET)
    }

    private fun dismissImportPinDialog() {
        val existing = supportFragmentManager.findFragmentByTag(TAG_IMPORT_PIN) ?: return
        supportFragmentManager.beginTransaction().remove(existing).commit()
    }

    /**
     * #363 / #420: a pad added to android.R.id.content covers the import
     * screen but does not replace it, and the confirm pad is added the
     * same way on top of the set pad. Accessibility services still walk
     * every sibling, so TalkBack can reach the import controls and, on
     * the confirm step, the set pad's keys and its confirm control.
     * While a pad is showing, every other child of the content root is
     * hidden. Closing that pad puts each child's own mode back.
     */
    private val accessibilityBeforePad = IdentityHashMap<View, Int>()

    private fun schedulePadAccessibilitySync() {
        syncPadAccessibility()
        if (!::binding.isInitialized) return
        binding.root.post {
            if (!isDestroyed) syncPadAccessibility()
        }
    }

    private fun syncPadAccessibility() {
        if (!::binding.isInitialized || isDestroyed) return
        val content = findViewById<ViewGroup>(android.R.id.content) ?: return
        val top = topmostShowingPad()
        val cover = LinkedHashSet<View>()
        if (top != null) {
            for (i in 0 until content.childCount) {
                val child = content.getChildAt(i)
                if (child !== top) cover.add(child)
            }
            // A pad hosted in its own window is not one of those children.
            // The loop already hid the import screen. Hide every other pad
            // and leave the one on top reachable.
            if (top.parent !== content) {
                for (tag in padTagsBottomToTop()) {
                    val view = showingPadView(tag) ?: continue
                    if (view === top || view.parent === content) continue
                    cover.add(view)
                }
            }
        }
        val uncover = ArrayList<View>()
        for (view in accessibilityBeforePad.keys) {
            if (view !in cover) uncover.add(view)
        }
        for (view in uncover) {
            val mode = accessibilityBeforePad.remove(view) ?: continue
            view.importantForAccessibility = mode
        }
        for (view in cover) {
            // A later sync must not record the mode this pad just wrote.
            if (!accessibilityBeforePad.containsKey(view)) {
                accessibilityBeforePad[view] = view.importantForAccessibility
            }
            view.importantForAccessibility =
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
    }

    private fun topmostShowingPad(): View? {
        var top: View? = null
        for (tag in padTagsBottomToTop()) {
            val view = showingPadView(tag) ?: continue
            top = view
        }
        return top
    }

    private fun showingPadView(tag: String): View? {
        val fragment = supportFragmentManager.findFragmentByTag(tag) as? LockScreenDialog
            ?: return null
        if (!fragment.isAdded || fragment.isRemoving) return null
        return fragment.view
    }

    private fun padTagsBottomToTop(): List<String> = listOf(
        TAG_IMPORT_PIN,
        TAG_RESTORE_PIN_SET,
        TAG_RESTORE_PIN_CONFIRM,
    )

    override fun onStart() {
        super.onStart()
        // A pad restored with the activity does not always emit a view
        // callback after the listener is registered. Settle once the
        // view exists.
        syncPadAccessibility()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_OFFER_PIN, offerPinPending)
        outState.putBoolean(STATE_PIN_NOTICE, pinNoticePending)
        outState.putBoolean(STATE_HOLD_IMPORT_CHROME, importButtonHeld)
    }

    override fun onDestroy() {
        supportFragmentManager.unregisterFragmentLifecycleCallbacks(importPadLifecycle)
        accessibilityBeforePad.clear()
        // #358: the import derive blocks on IO. viewModelScope is
        // cancelled only after that returns, so drop the overlay with
        // the window. Also covers the decrypt wait (#355). Safe if the
        // overlay was never shown, and safe if the job dismisses too.
        kdfOverlay.dismiss()
        // #420: drop the windows with the activity. Clearing the
        // listeners first keeps a teardown dismiss from looking like
        // the user pressed Not now after the state was already saved.
        restorePinOfferDialog?.setOnCancelListener(null)
        restorePinOfferDialog?.setOnDismissListener(null)
        restorePinOfferDialog?.dismiss()
        restorePinNoticeDialog?.setOnDismissListener(null)
        restorePinNoticeDialog?.dismiss()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (requireRestart) {
            startActivity(Intent(this, HomeActivity::class.java).apply {
                // #528: CLEAR_TASK is ignored unless NEW_TASK is set too.
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            })
            overridePendingTransition(R.anim.fade_in, R.anim.bottom_sheet_slide_out)
            finish()
        } else
            super.onBackPressed()
    }

    companion object {
        const val REQUEST_FILE_CODE = 44
        const val TAG_IMPORT_PIN = "lock_screen_import"
        const val REQUEST_KEY_IMPORT_PIN = "lock_screen_import_result"
        const val TAG_RESTORE_PIN_SET = "lock_screen_restore_set"
        const val TAG_RESTORE_PIN_CONFIRM = "lock_screen_restore_confirm"
        const val REQUEST_KEY_RESTORE_PIN_SET = "lock_screen_restore_set_result"
        const val REQUEST_KEY_RESTORE_PIN_CONFIRM = "lock_screen_restore_confirm_result"
        private const val STATE_OFFER_PIN = "restore_offer_pin"
        private const val STATE_PIN_NOTICE = "restore_pin_notice"
        private const val STATE_HOLD_IMPORT_CHROME = "restore_pin_hold_import_chrome"
    }
}
