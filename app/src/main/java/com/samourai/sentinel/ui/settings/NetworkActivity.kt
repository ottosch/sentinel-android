package com.samourai.sentinel.ui.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.samourai.sentinel.R
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.core.DojoContact
import com.samourai.sentinel.core.DojoReachability
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.tor.TorState
import com.samourai.sentinel.ui.SentinelActivity
import com.samourai.sentinel.ui.dojo.DojoConfigureBottomSheet
import com.samourai.sentinel.ui.dojo.DojoCredentialsBottomSheet
import com.samourai.sentinel.ui.dojo.DojoDisplaySnapshot
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.dojo.pairedExplorerOperator
import com.samourai.sentinel.ui.home.HomeActivity
import com.samourai.sentinel.ui.utils.Preferences
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.utils.showFloatingSnackBar
import com.samourai.sentinel.ui.views.confirm
import com.samourai.sentinel.ui.webview.ExplorerBinding
import com.samourai.sentinel.ui.webview.ExplorerHealth
import com.samourai.sentinel.ui.webview.ExplorerHealthProbe
import com.samourai.sentinel.ui.webview.ExplorerProbeCoordinator
import com.samourai.sentinel.ui.webview.ExplorerProbeRequest
import com.samourai.sentinel.ui.webview.ExplorerRepository
import com.samourai.sentinel.ui.webview.explorerOperatorVisible
import com.samourai.sentinel.ui.webview.explorerRowRetryable
import com.samourai.sentinel.util.apiScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import kotlin.reflect.KProperty

private const val TAG_DOJO_SETUP = "network_dojo_setup_sheet"
private const val REQUEST_KEY_DOJO_SETUP = "network_dojo_setup_result"
private val EXPLORER_PREF_NAMES = setOf(
    "selectedExplorer",
    "customExplorerUrl",
    "dojoExplorerUrl",
    "dojoDisplayName",
    "dojoDisplayFlag",
    "dojoDisplayUrl",
    "dojoDisplayNetwork",
    "apiEndPoint",
    "testnet",
)

class NetworkActivity : SentinelActivity() {

    var torRenewBtn: TextView? = null
    var torConnectionStatus: TextView? = null
    var dojoConnectionStatus: TextView? = null
    var torButton: Button? = null
    var dojoButton: Button? = null
    var dojoDetailsButton: Button? = null
    var torConnectionIcon: ImageView? = null
    var dojoConnectionIcon: ImageView? = null
    var networkStatusIcon: ImageView? = null
    var networkStatus: TextView? = null
    var networkSwitchBtn: Button? = null
    var explorerStatusIcon: ImageView? = null
    var explorerStatus: TextView? = null
    var explorerStatusDetail: TextView? = null
    var activeColor = 0
    var disabledColor = 0
    var waiting = 0
    var thirdPartyColor = 0
    var quietColor = 0
    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java);
    private val explorerRepository: ExplorerRepository by inject(ExplorerRepository::class.java)
    private var explorerRow: View? = null
    private val explorerProbes: ExplorerProbeCoordinator by lazy {
        ExplorerProbeCoordinator(
            scope = lifecycleScope,
            repository = explorerRepository,
            probe = { base, endpoint -> ExplorerHealthProbe.probe(base, endpoint) },
            onChanged = {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) refreshExplorerRow()
                }
            },
        )
    }
    private val explorerPrefListener = object : Preferences.SharedPrefsListener {
        override fun onSharedPrefChanged(property: KProperty<*>) {
            if (property.name !in EXPLORER_PREF_NAMES) return
            // The pref delegate notifies before the write. Post so the
            // read below sees the new value.
            window?.decorView?.post { refreshExplorerRow() }
        }
    }
    private val dojoUtility: DojoUtility by inject(DojoUtility::class.java);
    private val repository: CollectionRepository by inject(CollectionRepository::class.java)
    private val apiService: ApiService by inject(ApiService::class.java)

    // #85: erase-all-data unsets the Dojo pairing while this activity's
    // IO launches can still be mid-flight; a failure there must reach
    // logging, not the process (#81's handler pattern).
    private val networkExceptionHandler = CoroutineExceptionHandler { _, t ->
        Timber.e(t, "network import failed")
    }

    // #409: the burst subscription handle - assigned ONCE so subscribe and
    // unsubscribe always carry the same reference. The holder publishes
    // ticks to the CURRENT screen; this instance renders them in
    // onBurstEvent below.
    private val burstListener: (BurstEvent) -> Unit = { event ->
        onBurstEvent(event)
    }


    override fun onCreateAdmitted(savedInstanceState: Bundle?) {
        setContentView(R.layout.activity_network)
        setSupportActionBar(findViewById(R.id.toolbarCollectionDetails))
        if (supportActionBar != null) {
            supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        }
        activeColor = ContextCompat.getColor(this, R.color.green_ui_2)
        disabledColor = ContextCompat.getColor(this, R.color.disabledRed)
        waiting = ContextCompat.getColor(this, R.color.warning_yellow)
        thirdPartyColor = ContextCompat.getColor(this, R.color.v3_accent)
        quietColor = ContextCompat.getColor(this, R.color.grey_accent)
        torButton = findViewById(R.id.networking_tor_btn)
        torRenewBtn = findViewById(R.id.networking_tor_renew)
        dojoButton = findViewById(R.id.networking_dojo_btn)
        dojoDetailsButton = findViewById(R.id.networking_dojo_details_btn)
        torConnectionIcon = findViewById(R.id.network_tor_status_icon)
        torConnectionStatus = findViewById(R.id.network_tor_status)
        dojoConnectionIcon = findViewById(R.id.network_dojo_status_icon)
        dojoConnectionStatus = findViewById(R.id.network_dojo_status)
        networkStatusIcon = findViewById(R.id.network_bitcoin_status_icon)
        networkStatus = findViewById(R.id.network_bitcoin_status)
        networkSwitchBtn = findViewById(R.id.networking_network_switch_btn)
        explorerStatusIcon = findViewById(R.id.network_explorer_status_icon)
        explorerStatus = findViewById(R.id.network_explorer_status)
        explorerStatusDetail = findViewById(R.id.network_explorer_detail)
        // The row has no other tap action. Checking, unconfirmed, and unreachable retry.
        explorerRow = findViewById(R.id.network_explorer_layout)
        explorerRow?.setOnClickListener { onExplorerRowTapped() }
        prefsUtil.addListener(explorerPrefListener)
        // #220: re-registered every create. The sheet posts on pairing
        // success; this does not depend on the sheet instance.
        supportFragmentManager.setFragmentResultListener(
            REQUEST_KEY_DOJO_SETUP,
            this
        ) { _, _ ->
            supportFragmentManager.clearFragmentResult(REQUEST_KEY_DOJO_SETUP)
            onDojoSetupFinished()
        }
        torRenewBtn?.setOnClickListener {
            // #161: no optimistic feedback here - tor's verbatim reply
            // (success or rate-limit notice) is the single source of
            // truth and arrives on the notice stream observed below.
            SentinelTorManager.newIdentity()
        }
        setTorConnectionState(SentinelTorManager.getTorState())
        SentinelTorManager.getTorStateLiveData().observe(this, {
            setTorConnectionState(it)
            // Tor becoming ready is when an unknown Dojo row may probe.
            setDojoStatus()
            maybeProbeDojo()
        })
        // boot=100 can be published before the SOCKS port exists, and
        // that STATE stream does not fire again when the port appears
        // or moves (#135). This epoch does.
        SentinelTorManager.getSocksEpochLiveData().observe(this, {
            refreshExplorerRow()
        })
        // #161: tor's verbatim NEWNYM reply - success or rate-limit
        // notice - on the themed snackbar. Null is the ack no-op: the
        // notice is acked on delivery so a config-change re-subscribe
        // replays nothing.
        SentinelTorManager.getNewNymNoticeLiveData().observe(this, { notice ->
            if (notice == null) return@observe
            this.showFloatingSnackBar(
                findViewById(R.id.toolbarCollectionDetails),
                text = notice
            )
            SentinelTorManager.ackNewNymNotice()
        })
        dojoDetailsButton?.setOnClickListener {
            showDojoCredentialsBottomSheet()
        }
        dojoButton?.setOnClickListener {
            if (dojoUtility.isDojoEnabled()) {
                confirm(label = "Remove Dojo?", positiveText = "Remove", negativeText = "Cancel") {
                    if (it)
                        removeDojo()
                }
            } else {
                // #432: no camera ask at the door. The sheet opens directly;
                // its scan page asks for itself when the user actually opens
                // the camera, and the community-list / Paste paths never need one.
                showDojoSetUpBottomSheet()
            }
        }
        torButton!!.setOnClickListener {
            if (SentinelTorManager.getTorState().state == EnumTorState.ON) {
                if (!dojoUtility.isDojoEnabled()) {
                    SentinelTorManager.stop()
                    prefsUtil.enableTor = false
                }
                else
                    this.showFloatingSnackBar(torButton!!.rootView, text = getString(R.string.tor_disable_dojo_blocked))
            } else {
                SentinelTorManager.start()
                prefsUtil.enableTor = true
            }

        }
        networkSwitchBtn?.setOnClickListener { onNetworkSwitchTapped() }
        setNetworkStatus()
        setDojoStatus()
        refreshExplorerRow()
    }

    private fun removeDojo() {
        dojoUtility.clearDojo()
        setDojoStatus()
        refreshExplorerRow()
        if (prefsUtil.apiEndPoint.isNullOrEmpty()) {
            // #432: same as the dojo door - straight to the sheet.
            showDojoSetUpBottomSheet()
        }
    }

    private fun importAllXpubs() {
        // #85: guard the invariant here, not just at the caller - the
        // erase-all-data flow can clear the endpoints after the caller's
        // check has passed and while this loop is still mid-flight.
        if (!prefsUtil.isAPIEndpointEnabled()) {
            return
        }
        // #158/#159: single-flight - one live burst at a time. The pairing
        // sheet can fire its listener more than once per pairing (#159);
        // a second launch while one is live is skipped, never interleaved
        // onto the same row.
        if (!KeyRegistrationBurst.markStarted()) {
            return
        }
        apiScope.launch(networkExceptionHandler) {
            var registered = 0
            var failed = 0
            var roundRan = false
            try {
                val toImport = mutableListOf<Pair<String, String>>()

                repository.collectionsSnapshot().forEach { collection ->
                    collection.pubs.forEach { pub ->
                        val purpose = "bip${pub.getPurpose()}"
                        toImport.add(pub.pubKey to purpose)
                    }
                }

                // #98: an empty wallet has nothing to register - skip the
                // burst UI with no noise; the pairing sheet already showed
                // its own progress. The finally below resets the holder
                // silently - no residue, no report (the silence leg).
                if (toImport.isEmpty()) {
                    return@launch
                }

                // #98: the burst ran fire-and-forget with zero view writes -
                // the flow-1 device observation ("no progress indication at
                // any point", #97 leg). Per-item progress on the Dojo row,
                // completion tally at the end; setDojoStatus() owns the row's
                // rest state.
                // #158: the holder mirrors the round so a re-entered screen
                // renders from state, and the finally guarantees every round
                // reports - retries included, never zero times.
                roundRan = true
                KeyRegistrationBurst.begin(toImport.size)
                toImport.forEachIndexed { index, (pubKey, purpose) ->
                    // #85: re-check per iteration - the pairing can be erased
                    // mid-loop, and importXpub -> buildClient -> getAPIUrl
                    // throws ApiNotConfigured once the endpoints are gone.
                    if (!prefsUtil.isAPIEndpointEnabled()) {
                        return@forEachIndexed
                    }
                    // #158: attempt counter in the holder - option B keeps
                    // the row counting attempts; failures advance it too.
                    // #409: onItemStarted publishes the tick from the
                    // holder to the current subscriber - the launching
                    // instance's own view write is gone, so a recreated
                    // screen no longer freezes behind a dead writer.
                    KeyRegistrationBurst.onItemStarted(index + 1)
                    try {
                        apiService.importXpub(pubKey, purpose)
                        registered++
                    } catch (e: ApiService.ApiNotConfigured) {
                        // #85: ApiNotConfigured is a Throwable, NOT an
                        // Exception - the generic catch below could never
                        // intercept it, so it escaped the launch and killed
                        // the process.
                        Timber.e(e, "Dojo pairing gone, skipping remaining xpub imports")
                        return@forEachIndexed
                    } catch (e: Exception) {
                        Timber.d(e, "Error")
                        failed++
                    }
                }
            } catch (e: CancellationException) {
                // #129 discipline: cancellation is rethrown, never laundered.
                // apiScope is app-lifetime and never cancels today; the arm
                // order is the contract anyway.
                throw e
            } catch (t: Throwable) {
                // #158 leg 2: the finally below still reports - this catch
                // only keeps an escape-class failure off the process (with
                // the launch CEH) while the report lands anyway.
                Timber.e(t, "derivation path registration burst escaped mid-flight")
            } finally {
                if (roundRan) {
                    // #158: every round states its own delta; the gate
                    // re-arms so the next retry round reports too.
                    // #409: complete() publishes the Finished event, so
                    // the row restore and the tally snackbar land on
                    // whichever screen is live - not only the launcher.
                    KeyRegistrationBurst.complete(registered, failed, KeyRegistrationBurst.total)
                } else {
                    KeyRegistrationBurst.markFinished()
                }
            }
        }
    }

    // #409: burst events render here. KeyRegistrationBurst publishes
    // ticks and completions to whoever is subscribed, so the row advances
    // on the CURRENT screen - not only on the instance that launched the
    // burst, whose destroy-guarded writes no-op'd after a recreate behind
    // the pairing sheet. Main-confined and destroy-guarded like every view
    // write on this screen (#85 containment semantics untouched); the
    // registration work itself is unchanged.
    private fun onBurstEvent(event: BurstEvent) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            when (event) {
                is BurstEvent.Progress ->
                    dojoConnectionStatus?.text =
                        KeyRegistrationBurst.progressText(event.done, event.total)
                is BurstEvent.Finished -> {
                    // #98/#158: restore the row from holder state, then the
                    // tally - partial rounds keep the retry note.
                    setDojoStatus()
                    this@NetworkActivity.showFloatingSnackBar(
                        findViewById(R.id.network_dojo_layout),
                        text = KeyRegistrationBurst.resultText(
                            event.registered, event.failed, event.total
                        )
                    )
                }
            }
        }
    }

    private var dojoProbe: Job? = null

    private val dojoReachabilityListener: (DojoContact) -> Unit = {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            setDojoStatus()
        }
    }

    fun setDojoStatus() {
        if (dojoUtility.isDojoEnabled()) {
            // #158: a burst in flight or a partial registration still
            // owns the text. The color and the rest label come from the
            // last real Dojo contact, not from the pairing existing.
            val torReady = !SentinelState.isTorRequired() || SentinelTorManager.isTorReady()
            val row = DojoStatusRow.present(
                contact = DojoReachability.current,
                now = System.currentTimeMillis(),
                torReady = torReady,
                checking = dojoProbe?.isActive == true,
            )
            dojoConnectionStatus?.text = KeyRegistrationBurst.statusText(row.text)
            dojoButton?.text = getString(R.string.disable_dojo)
            val color = when (row.tone) {
                DojoRowTone.GREEN -> activeColor
                DojoRowTone.AMBER -> waiting
                DojoRowTone.RED -> disabledColor
            }
            dojoConnectionIcon!!.setColorFilter(color)
            // Only offer pairing details when a payload actually exists.
            dojoDetailsButton?.visibility = View.VISIBLE
        } else {
            dojoConnectionStatus?.text = getString(R.string.disabled)
            dojoButton?.text = getString(R.string.enable)
            dojoConnectionIcon!!.setColorFilter(disabledColor)
            dojoDetailsButton?.visibility = View.GONE
        }
    }

    /**
     * One fees read while this screen is open and the row has no fresh
     * contact. Cancelled when the screen leaves. Tor-only: [ApiService]
     * refuses a client until Tor is ready, and this does not start when
     * it is not.
     */
    private fun maybeProbeDojo() {
        if (isFinishing || isDestroyed) return
        if (!dojoUtility.isDojoEnabled()) return
        if (dojoProbe?.isActive == true) return
        val torReady = !SentinelState.isTorRequired() || SentinelTorManager.isTorReady()
        if (!DojoStatusRow.shouldProbe(DojoReachability.current, System.currentTimeMillis(), torReady)) {
            return
        }
        dojoProbe = lifecycleScope.launch {
            try {
                apiService.getFees().close()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // callOn already recorded Unreachable or DojoError.
            } finally {
                if (!isFinishing && !isDestroyed) setDojoStatus()
            }
        }
    }

    /**
     * #208: renders the Bitcoin Network row from state. Mainnet reads
     * green, Testnet reads warning-yellow - the same visual grammar as
     * the Tor and Dojo rows. An established keychain pins the network:
     * #42's derivation would revert a manual flag flip on the next
     * launch, so the row states that instead of offering a switch that
     * cannot stick.
     */
    private fun setNetworkStatus() {
        val currentIsTestnet = prefsUtil.testnet == true
        val name = getString(
            if (currentIsTestnet) R.string.network_testnet else R.string.network_mainnet
        )
        networkStatusIcon?.setColorFilter(if (currentIsTestnet) waiting else activeColor)
        when (val row = NetworkSwitch.rowState(
            currentIsTestnet,
            SentinelState.isNetworkEstablished()
        )) {
            is NetworkSwitch.Row.Switchable -> {
                networkStatus?.text = name
                networkSwitchBtn?.visibility = View.VISIBLE
                networkSwitchBtn?.text = getString(
                    if (row.targetIsTestnet) R.string.network_switch_to_testnet
                    else R.string.network_switch_to_mainnet
                )
            }
            is NetworkSwitch.Row.KeyPinned -> {
                networkStatus?.text = name + " \u2014 " + getString(R.string.network_set_by_keys)
                networkSwitchBtn?.visibility = View.GONE
            }
        }
    }

    /**
     * #208: re-derives the row state at tap time - the keychain can have
     * changed since render - and asks for confirmation with the full
     * consequences spelled out: Dojo removal and a restart, watch-only
     * keys/labels/settings kept. A key-pinned wallet never gets here
     * with a visible button; the guard keeps a mid-flight state change
     * from switching anyway.
     */
    private fun onNetworkSwitchTapped() {
        val row = NetworkSwitch.rowState(
            prefsUtil.testnet == true,
            SentinelState.isNetworkEstablished()
        )
        if (row !is NetworkSwitch.Row.Switchable) {
            setNetworkStatus()
            return
        }
        val targetName = getString(
            if (row.targetIsTestnet) R.string.network_testnet else R.string.network_mainnet
        )
        confirm(
            label = getString(R.string.network_switch_confirm_title, targetName),
            message = getString(R.string.network_switch_confirm_message, targetName),
            positiveText = getString(R.string.network_switch_confirm_positive),
            negativeText = "Cancel",
            onConfirm = { confirmed ->
                if (confirmed) performNetworkSwitch(row.targetIsTestnet)
            }
        )
    }

    /**
     * #208: the confirmed switch. Order matters: flag first, then Dojo
     * removal, then Tor stop, then the task relaunch - erase-all's recipe
     * (MainSettingsFragment.clearWallet) minus the nuke. The process
     * survives the relaunch, so the async pref write and the async
     * payload delete both land - no sync-commit dance needed. enableTor
     * is deliberately untouched: SentinelApplication.setUpTor re-starts
     * Tor on the next launch whenever the user's own toggle is on.
     */
    private fun performNetworkSwitch(targetIsTestnet: Boolean) {
        prefsUtil.testnet = targetIsTestnet
        dojoUtility.clearDojo()
        SentinelTorManager.stop()
        startActivity(Intent(applicationContext, HomeActivity::class.java).apply {
            // #528: CLEAR_TASK is ignored unless NEW_TASK is set too.
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
        finish()
    }

    /**
     * Shows the pairing credentials for the connected Dojo.
     *
     * #511: the payload, the pairing QR, and the node API key stay
     * secure on this sheet's own window whatever Disable Screenshots
     * says. The host window is left on the setting.
     */
    private fun showDojoCredentialsBottomSheet() {
        if (!dojoUtility.isDojoEnabled()) {
            this.showFloatingSnackBar(
                findViewById(R.id.toolbarCollectionDetails),
                text = getString(R.string.no_dojo_connected)
            )
            return
        }
        val sheet = DojoCredentialsBottomSheet()
        sheet.show(supportFragmentManager, sheet.tag)
    }

    private var explorerRefreshDepth = 0
    private var screenStarted = false
    private var checkingTicker: Job? = null

    /**
     * Block Explorer row. A recent check for this explorer is shown
     * as-is. A timeout stays fresh only briefly, so a cold Tor circuit
     * does not stick. An older result stays on screen while a new check
     * runs. Checking and "Could not confirm" are amber. A definitive
     * failure is red. The probe runs only while this screen is started,
     * and stopping the screen cancels the attempt in flight.
     */
    private fun refreshExplorerRow() {
        val status = explorerStatus ?: return
        if (isFinishing || isDestroyed) return
        // A probe that finishes before it suspends re-enters through
        // onChanged. Cap the nest so a Tor change mid-read cannot recurse.
        if (explorerRefreshDepth >= 3) return
        explorerRefreshDepth++
        try {
            explorerProbes.refresh(currentExplorerRequest())
            status.let { renderExplorerRow() }
        } finally {
            explorerRefreshDepth--
        }
    }

    private fun currentExplorerRequest(): ExplorerProbeRequest {
        val tor = SentinelTorManager.getTorState()
        return ExplorerProbeRequest(
            resolution = explorerRepository.resolution(),
            torState = tor.state,
            torProgress = tor.progressIndicator,
            socksEndpoint = SentinelTorManager.socksEndpoint(),
            socksGeneration = SentinelTorManager.socksGeneration(),
        )
    }

    /**
     * The row has no other tap action. Checking, Could not confirm,
     * and Unreachable start one new probe. Any other state ignores the tap.
     */
    private fun onExplorerRowTapped() {
        val request = currentExplorerRequest()
        val health = explorerProbes.health(request).health
        if (!explorerRowRetryable(health)) return
        explorerProbes.retry(request)
        renderExplorerRow()
    }

    private fun renderExplorerRow() {
        val status = explorerStatus ?: return
        val icon = explorerStatusIcon ?: return
        if (isFinishing || isDestroyed) return
        val request = currentExplorerRequest()
        val state = explorerProbes.health(request)
        val resolution = request.resolution
        val operator = (resolution as? ExplorerBinding.ExplorerResolution.Bound)
            ?.takeIf { it.kind == ExplorerBinding.ExplorerKind.PAIRED }
            ?.let {
                pairedExplorerOperator(
                    prefsUtil.apiEndPoint,
                    DojoDisplaySnapshot(
                        prefsUtil.dojoDisplayUrl,
                        prefsUtil.dojoDisplayName,
                        prefsUtil.dojoDisplayFlag,
                        prefsUtil.dojoDisplayJurisdiction,
                        prefsUtil.dojoDisplayNetwork,
                    ),
                    getString(R.string.dojo_unnamed),
                )
            }
        val (text, color) = when (state.health) {
            ExplorerHealth.CHECKING ->
                getString(R.string.explorer_status_checking_elapsed, checkingSeconds()) to waiting
            ExplorerHealth.TOR_STARTING ->
                getString(R.string.explorer_status_tor_starting) to waiting
            ExplorerHealth.BOUND ->
                getString(R.string.explorer_status_bound, state.hostLabel.orEmpty()) to activeColor
            ExplorerHealth.THIRD_PARTY ->
                getString(R.string.explorer_status_third_party, state.hostLabel.orEmpty()) to thirdPartyColor
            ExplorerHealth.NONE ->
                getString(R.string.explorer_status_none) to quietColor
            ExplorerHealth.UNREACHABLE ->
                getString(R.string.explorer_status_unreachable) to disabledColor
            ExplorerHealth.UNCONFIRMED ->
                getString(R.string.explorer_status_unconfirmed) to waiting
            ExplorerHealth.TOR_REQUIRED ->
                getString(R.string.explorer_status_tor_required) to disabledColor
        }
        status.text = text
        icon.setColorFilter(color)
        val retryable = explorerRowRetryable(state.health)
        val row = explorerRow
        row?.isClickable = retryable
        row?.isFocusable = retryable
        if (retryable) {
            row?.contentDescription = getString(R.string.explorer_row_retry_description, text)
        } else {
            row?.contentDescription = null
        }
        val detail = explorerStatusDetail
        when {
            explorerOperatorVisible(state.health, operator) && detail != null -> {
                detail.text = operator
                detail.visibility = View.VISIBLE
            }
            retryable && detail != null -> {
                detail.text = getString(R.string.explorer_status_tap_to_retry)
                detail.visibility = View.VISIBLE
            }
            else -> {
                detail?.text = ""
                detail?.visibility = View.GONE
            }
        }
        syncCheckingTicker()
    }

    private fun checkingSeconds(): Int {
        val seconds = explorerProbes.checkingElapsedMs() / 1_000L
        return seconds.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * 1s ticks into "Checking… Ns". The job exists only while that
     * state is on screen, and [onStop] cancels it.
     */
    private fun syncCheckingTicker() {
        val checking = screenStarted &&
            explorerProbes.health(currentExplorerRequest()).health == ExplorerHealth.CHECKING
        if (!checking) {
            stopCheckingTicker()
            return
        }
        if (checkingTicker?.isActive == true) return
        checkingTicker = lifecycleScope.launch {
            while (isActive) {
                delay(1_000)
                if (!isActive || isFinishing || isDestroyed || !screenStarted) return@launch
                val health = explorerProbes.health(currentExplorerRequest()).health
                if (health != ExplorerHealth.CHECKING) return@launch
                paintCheckingElapsed()
            }
        }
    }

    private fun paintCheckingElapsed() {
        val status = explorerStatus ?: return
        val text = getString(R.string.explorer_status_checking_elapsed, checkingSeconds())
        status.text = text
        if (explorerRow?.isClickable == true) {
            explorerRow?.contentDescription =
                getString(R.string.explorer_row_retry_description, text)
        }
    }

    private fun stopCheckingTicker() {
        checkingTicker?.cancel()
        checkingTicker = null
    }

    private fun setTorConnectionState(torState: TorState) {
        runOnUiThread {
            when (torState.state) {

                EnumTorState.ON -> {
                    torButton!!.text = getString(R.string.disable)
                    torButton!!.isEnabled = true
                    torRenewBtn!!.visibility = View.VISIBLE
                    // #99: ON lands at boot=5; the 5->100 climb is real work
                    // (relay handshake, consensus, descriptors, circuits).
                    // Render it with a stage summary in the Tor Browser's
                    // voice; plain "Enabled" only at boot=100, which is when
                    // the home light goes GREEN.
                    if (torState.progressIndicator in 1..99) {
                        torConnectionIcon!!.setColorFilter(waiting)
                        torConnectionStatus!!.text =
                            torBootstrapStage(torState.progressIndicator)
                    } else {
                        torConnectionIcon!!.setColorFilter(activeColor)
                        torConnectionStatus!!.text = getString(R.string.Enabled)
                    }
                }
                EnumTorState.STARTING -> {
                    torRenewBtn!!.visibility = View.INVISIBLE
                    torButton!!.text = getString(R.string.loading)
                    torButton!!.isEnabled = false
                    torConnectionIcon!!.setColorFilter(waiting)
                    // #94: surface the bootstrap % so "Tor slow today" is
                    // distinguishable from "wallet struggling" at a glance.
                    torConnectionStatus!!.text =
                        if (torState.progressIndicator in 1..99) {
                            "${getString(R.string.tor_initializing)}\u2026 ${torState.progressIndicator}%"
                        } else {
                            getString(R.string.tor_initializing)
                        }
                }
                else -> {
                    torRenewBtn!!.visibility = View.INVISIBLE
                    torButton!!.text = getString(R.string.enable)
                    torButton!!.isEnabled = true
                    torConnectionIcon!!.setColorFilter(disabledColor)
                    torConnectionStatus!!.text = getString(R.string.disabled)
                }
            }
            refreshExplorerRow()
        }
    }

    /**
     * #99: Tor-Browser-voice stage for the post-ON climb. Derived client-side
     * from progressIndicator only - the control-spec bootstrap phases are
     * monotonic and ordered (Tor spec 5.5), so the % is enough to locate the
     * stage. TorState stays untouched (declared non-change); code-set text
     * matches this file's #94 interpolation precedent.
     */
    private fun torBootstrapStage(progress: Int): String {
        val summary = when {
            progress in 1..19 -> "Reaching the first Tor relay\u2026"
            progress in 20..44 -> "Loading network consensus\u2026"
            progress in 45..74 -> "Loading relay directory info\u2026"
            progress in 75..89 -> "Building Tor circuits\u2026"
            else -> "Almost ready\u2026"
        }
        return "Tor $progress% - $summary"
    }

    private fun showDojoSetUpBottomSheet() {
        if (supportFragmentManager.findFragmentByTag(TAG_DOJO_SETUP) != null) return
        DojoConfigureBottomSheet.newInstance(
            requestKey = REQUEST_KEY_DOJO_SETUP,
            pairingScan = true,
        ).show(supportFragmentManager, TAG_DOJO_SETUP)
    }

    /** #220: re-reads Dojo and network prefs. Same work the old listener did. */
    private fun onDojoSetupFinished() {
        setDojoStatus()
        refreshExplorerRow()
        // #274: #273's pairing guard can flip the network flag
        // at Pair time while this activity sits behind the sheet -
        // re-render the Bitcoin Network row too, or it keeps the
        // pre-pairing network (and the wrong switch direction)
        // until the activity is recreated.
        setNetworkStatus()
        if (!prefsUtil.isAPIEndpointEnabled()) {
            Toast.makeText(applicationContext, getString(R.string.no_dojo_connected), Toast.LENGTH_SHORT).show()
        } else
            importAllXpubs()
    }

    /** The explorer probe runs only while this screen is visible. */
    override fun onStart() {
        super.onStart()
        screenStarted = true
        explorerProbes.onScreenStart()
        refreshExplorerRow()
    }

    /**
     * #274 general case: any writer that flips the network flag or the
     * Dojo pairing while this activity sits stopped on the back stack
     * is reflected on return - not just the pairing sheet's dismiss.
     * Pure re-render: setNetworkStatus() re-derives from prefs, and
     * onNetworkSwitchTapped() re-derives again at tap time, so a stale
     * row can never act wrong.
     */
    override fun onResume() {
        super.onResume()
        // #409: attach BEFORE the first render so no tick can land between
        // the subscribe and the snapshot below; a mid-burst arrival gets
        // the current count immediately from subscribe's snapshot event.
        KeyRegistrationBurst.subscribe(burstListener)
        DojoReachability.subscribe(dojoReachabilityListener)
        setNetworkStatus()
        maybeProbeDojo()
        setDojoStatus()
        refreshExplorerRow()
    }

    override fun onStop() {
        screenStarted = false
        dojoProbe?.cancel()
        dojoProbe = null
        stopCheckingTicker()
        explorerProbes.onScreenStop()
        super.onStop()
    }

    override fun onDestroy() {
        // #409: detach - a destroyed instance must not receive view
        // writes (the guard inside onBurstEvent is the backstop; this is
        // the clean exit). The burst itself keeps running on apiScope by
        // design (#85).
        KeyRegistrationBurst.unsubscribe(burstListener)
        DojoReachability.unsubscribe(dojoReachabilityListener)
        dojoProbe?.cancel()
        prefsUtil.removeListener(explorerPrefListener)
        explorerProbes.onScreenStop()
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
