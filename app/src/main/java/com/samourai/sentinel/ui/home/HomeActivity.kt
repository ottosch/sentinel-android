package com.samourai.sentinel.ui.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.ShapeAppearanceModel
import com.samourai.sentinel.R
import com.samourai.sentinel.core.ConnectionIndicator
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.core.SyncState
import com.samourai.sentinel.ui.views.ConnectionIndicatorController
import com.samourai.sentinel.databinding.ActivityHomeBinding
import com.samourai.sentinel.service.WebSocketHandler
import com.samourai.sentinel.service.WebSocketService
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.ui.SentinelActivity
import com.samourai.sentinel.ui.adapters.CollectionsAdapter
import com.samourai.sentinel.ui.collectionDetails.CollectionDetailsActivity
import com.samourai.sentinel.ui.dojo.DojoConfigureBottomSheet
import com.samourai.sentinel.ui.dojo.PubKeyRescanner
import com.samourai.sentinel.ui.fragments.AddNewPubKeyBottomSheet
import com.samourai.sentinel.ui.settings.ImportBackUpActivity
import com.samourai.sentinel.ui.settings.NetworkActivity
import com.samourai.sentinel.ui.settings.SettingsActivity
import com.samourai.sentinel.ui.tools.ToolsActivity
import com.samourai.sentinel.ui.utils.AndroidUtil
import com.samourai.sentinel.ui.utils.PermissionResult
import com.samourai.sentinel.ui.utils.permissionResultOf
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.utils.RecyclerViewItemDividerDecorator
import com.samourai.sentinel.ui.utils.SlideInItemAnimator
import com.samourai.sentinel.ui.utils.showFloatingSnackBar
import com.samourai.sentinel.ui.views.ConfirmBottomSheet
import com.samourai.sentinel.util.AppUtil
import com.samourai.sentinel.util.BalanceDisplayFormatter
import com.samourai.sentinel.util.BalanceDisplayMode
import com.samourai.sentinel.util.FormatsUtil
import com.samourai.sentinel.util.MonetaryUtil
import com.samourai.sentinel.util.TimeOutUtil
import com.samourai.sentinel.util.UtxoMetaUtil
import com.samourai.sentinel.widgets.popUpMenu.popupMenu
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber


class HomeActivity : SentinelActivity() {

    private lateinit var linearLayoutManager: LinearLayoutManager
    private val collectionsAdapter = CollectionsAdapter()
    private val webSocketHandler: WebSocketHandler by inject(WebSocketHandler::class.java)
    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java)
    private var connectingDojo = false
    private var torAutoSyncFired = false
    private lateinit var binding: ActivityHomeBinding
    private val model: HomeViewModel by viewModels()
    private var balance = -1L
    private var indicatorController: ConnectionIndicatorController? = null
    // #184: named observer so a menu re-pass can retire the previous one -
    // the indicator LiveData is one shared mediator for the process now.
    private var indicatorObserver: Observer<ConnectionIndicator>? = null

    override fun onCreateAdmitted(savedInstanceState: Bundle?) {
        binding = ActivityHomeBinding.inflate(layoutInflater)
        val view = binding.root
        window.statusBarColor = ContextCompat.getColor(this, R.color.mpm_black)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.grey_homeActivity)
        setContentView(view)
        setSwipeBackEnable(false)
        setSupportActionBar(binding.toolbarHome)

        title = ""

        // #210: durable listener for the first-run network choice. The
        // sheet delivers its answer through the Fragment Result API, and
        // this registration re-runs on every create - so the sheet that
        // Android restores after a recreation (process death, ui-mode or
        // font change; activities are portrait-locked, so rotation is not
        // the trigger here) still completes first-run setup when the user
        // taps Mainnet/Testnet.
        supportFragmentManager.setFragmentResultListener(
            REQUEST_KEY_FIRST_RUN_NETWORK,
            this
        ) { _, bundle ->
            onFirstRunNetworkChoice(bundle.getBoolean(ConfirmBottomSheet.RESULT_CONFIRMED))
        }
        // #404: durable listener for the first-run restore-vs-new
        // choice, same shape as the network leg above. The result is
        // cleared on delivery so a later recreation re-registering
        // this listener cannot replay an old answer - the restore arm
        // launches ImportBackUpActivity, which must never re-fire from
        // a stale mailbox.
        supportFragmentManager.setFragmentResultListener(
            REQUEST_KEY_FIRST_RUN_RESTORE,
            this
        ) { _, bundle ->
            supportFragmentManager.clearFragmentResult(REQUEST_KEY_FIRST_RUN_RESTORE)
            onFirstRunRestoreChoice(bundle.getBoolean(ConfirmBottomSheet.RESULT_CONFIRMED))
        }
        // #220: same shape as the network choice. A restored Dojo sheet
        // posts here; this registration runs on every create.
        supportFragmentManager.setFragmentResultListener(
            REQUEST_KEY_DOJO_SETUP,
            this
        ) { _, _ ->
            supportFragmentManager.clearFragmentResult(REQUEST_KEY_DOJO_SETUP)
            onDojoSetupFinished()
        }

        binding.toolbarIcon.setOnClickListener {
            showToolOptions(it)
        }

        val model: HomeViewModel by viewModels()
        UtxoMetaUtil.read()

        if (SentinelState.isTorRequired() && SentinelTorManager.getTorState().state == EnumTorState.OFF) {
            // #170: the boot start stays (a paired Dojo or the user's own
            // toggle gates it now); the enableTor stomp is gone - the
            // toggle is the user's alone.
            SentinelTorManager.start()
        }

        // #404: the notification ask moved out of first-run setup - it
        // now fires once, per device, when setup completes
        // (onDojoSetupFinished) or on the first resume after a
        // finished setup (restores), gated on notificationPromptDone.
        setUp()

        setUpCollectionList()

        model.getCollections().observe(this, {
            if (it.isNotEmpty())
                binding.welcomeMessage.visibility = View.GONE
            else
                binding.welcomeMessage.visibility = View.VISIBLE

            collectionsAdapter.update(it)
        })

        model.getBalance().observe(this) {
            balance = it
            applyBalanceDisplayMode()
        }

        // #301: frozen (do-not-spend) sum for the caption under the total.
        model.getFrozenBalance().observe(this) {
            renderFrozenCaption()
        }

        // #301: do-not-spend marks change outside the collections list
        // (Unspent outputs action mode). Rebind the rows so each re-derives
        // its spendable figure; the differ cannot - marks are not part of
        // PubKeyCollection, so the list diffs to itself as a no-op. The
        // total repaints through getBalance(), which HomeViewModel
        // re-derives on the same signal.
        UtxoMetaUtil.blockedChanges.observe(this) {
            collectionsAdapter.notifyDataSetChanged()
        }

        // Tap the balance to cycle BTC -> sats -> masked -> BTC.
        binding.homeBalanceBtc.setOnClickListener {
            prefsUtil.balanceDisplayMode = currentDisplayMode().next().name
            applyBalanceDisplayMode()
            collectionsAdapter.notifyDataSetChanged()
        }

        model.getFiatBalance().observe(this) { applyBalanceDisplayMode() }

        binding.fab.setBackgroundResource(R.drawable.background_gradient);

        binding.fab.setOnClickListener {
            connectingDojo = false
            if (!AndroidUtil.isPermissionGranted(Manifest.permission.CAMERA, applicationContext)) {
                this.askCameraPermission()
            } else {
                if (AppUtil.getInstance(applicationContext).isOfflineMode
                    ||  SentinelTorManager.getTorState().state == EnumTorState.STARTING)
                    Toast.makeText(this, "No data connection. Please wait, then try again", Toast.LENGTH_LONG).show()
                else
                    showPubKeyBottomSheet()
            }
        }

        // Drive all loading UI from the explicit SyncState.
        model.syncState().observe(this) { renderSyncState(it) }

        // NOTE: the toolbar indicator is wired up in setNetWorkMenu(), once the
        // menu's action view actually exists.

        // A corrupt payload must never look like "you have no wallets".
        model.payloadReadFailure().observe(this) { failure ->
            if (failure != null) {
                binding.welcomeMessage.visibility = View.GONE
                MaterialAlertDialogBuilder(this)
                    .setTitle("Could not load collections")
                    .setMessage(
                        "Your saved collections could not be read. They have NOT been " +
                            "deleted. Restart the app and re-enter your PIN. If this " +
                            "persists, restore from your backup."
                    )
                    .setPositiveButton(resources.getString(R.string.ok), null)
                    .show()
            }
        }

        binding.syncRetryButton.setOnClickListener {
            requestRefresh()
        }

        if (intent != null) {
            if (intent.hasExtra("forceRefresh") && intent.getBooleanExtra("forceRefresh", true)) {
                model.fetchBalance(userInitiated = true)
            }
        }

        binding.swipeRefreshCollection.setOnRefreshListener {
            // The banner is now the source of loading feedback; the pull spinner
            // only acknowledges the gesture.
            binding.swipeRefreshCollection.isRefreshing = false
            requestRefresh()
        }

        fetch(model)

        if (SentinelState.isTorRequired()) {
            SentinelTorManager.getTorStateLiveData().observe(this, ({
                if (it.state == EnumTorState.ON)
                    WebSocketService.start(applicationContext)
            }))
        } else {
            WebSocketService.start(applicationContext)
        }
    }

    /**
     * Renders the sync banner from the explicit [SyncState].
     *
     * Cached collections stay visible at all times; this only annotates them.
     */
    private var lastBannerText: String? = null

    private fun renderSyncState(state: SyncState) {
        val banner = binding.syncStatusBanner
        val text = binding.syncStatusText
        val progress = binding.syncStatusProgress
        val retry = binding.syncRetryButton

        Timber.i("SyncState -> $state")

        // Hysteresis (#47): glide layout changes instead of snapping so the
        // FAB never teleports, and suppress no-op text rewrites.
        val target = if (state is SyncState.Idle) View.GONE else View.VISIBLE
        if (banner.visibility != target) {
            TransitionManager.beginDelayedTransition(
                binding.root as ViewGroup,
                ChangeBounds().setDuration(250L)
            )
        }

        fun setText(s: String) {
            if (s != lastBannerText) {
                lastBannerText = s
                text.text = s
            }
        }

        when (state) {
            is SyncState.Idle -> {
                banner.visibility = View.GONE
            }

            is SyncState.WaitingForTor -> {
                banner.visibility = View.VISIBLE
                progress.visibility = View.VISIBLE
                retry.visibility = View.GONE
                // #94: determinate bootstrap % on the banner indicator -
                // same threshold as the text below it.
                if (state.progress in 1..99) {
                    if (progress.isIndeterminate) {
                        // Mode switches must never run on a VISIBLE indicator;
                        // INVISIBLE keeps the slot without drawing.
                        progress.visibility = View.INVISIBLE
                        progress.isIndeterminate = false
                        progress.visibility = View.VISIBLE
                    }
                    progress.setProgressCompat(state.progress, true)
                }
                setText(
                    if (state.progress in 1..99) {
                        "Connecting via Tor\u2026 ${state.progress}%"
                    } else {
                        "Connecting via Tor\u2026"
                    }
                )
            }

            is SyncState.Syncing -> {
                banner.visibility = View.VISIBLE
                retry.visibility = View.GONE
                // #94: a Tor bar frozen at N% must not ride through the
                // sync round - reset to indeterminate behind INVISIBLE.
                if (!progress.isIndeterminate) {
                    progress.visibility = View.INVISIBLE
                    progress.isIndeterminate = true
                }
                progress.visibility = View.VISIBLE
                val base = if (state.total > 1) {
                    "Syncing ${state.done} of ${state.total} collections\u2026"
                } else {
                    "Syncing\u2026"
                }
                setText(
                    if (state.slow) {
                        "$base still working over Tor, this can take a while"
                    } else {
                        base
                    }
                )
            }

            is SyncState.Success -> {
                progress.visibility = View.GONE
                // #94: re-arm indeterminate behind GONE for the next cycle.
                progress.isIndeterminate = true
                retry.visibility = View.GONE
                setText("Updated just now")
                banner.visibility = View.VISIBLE
                // Briefly confirm, then glide out of the way.
                banner.postDelayed({
                    if (model.syncState().value is SyncState.Success) {
                        TransitionManager.beginDelayedTransition(
                            binding.root as ViewGroup,
                            ChangeBounds().setDuration(250L)
                        )
                        banner.visibility = View.GONE
                    }
                }, 2000)
            }

            is SyncState.Failed -> {
                banner.visibility = View.VISIBLE
                progress.visibility = View.GONE
                // #94: re-arm indeterminate behind GONE for the next cycle.
                progress.isIndeterminate = true
                setText(state.reason)
                retry.visibility = if (state.retryable) View.VISIBLE else View.GONE
            }
        }
    }

    /**
     * Single entry point for user-initiated refresh, from either the pull gesture
     * or the banner's Retry button.
     */
    /**
     * Single entry point for user-initiated refresh, from either the pull
     * gesture or the banner's Retry button. User refreshes REPLACE any
     * in-flight round; auto triggers never do (#47).
     */
    private fun requestRefresh() {
        if (SentinelState.isTorRequired()) {
            when (SentinelTorManager.getTorState().state) {
                EnumTorState.ON -> model.fetchBalance(userInitiated = true)
                EnumTorState.OFF -> {
                    // #170: refresh-triggered start, no stomp on the toggle -
                    // isTorRequired() already gates this arm.
                    SentinelTorManager.start()
                }
                // STARTING / STOPPING: the banner already communicates progress.
                else -> Unit
            }
        } else {
            model.fetchBalance(userInitiated = true)
        }
    }

    private fun showToolOptions(it: View) {
        val toolWindowSize = applicationContext.resources.displayMetrics.density * 220;
        val popupMenu = popupMenu {
            fixedContentWidthInPx = toolWindowSize.toInt()
            style = R.style.Theme_Samourai_Widget_MPM_Menu_Dark
            section {
                item {
                    label = "Sentinel"
                    iconDrawable = ContextCompat.getDrawable(applicationContext, R.drawable.icon_innergradient)
                    iconSize = 34
                    labelColor = ContextCompat.getColor(applicationContext, R.color.white)
                    disableTint = true
                    iconShapeAppearanceModel = ShapeAppearanceModel().toBuilder()
                        .setAllCornerSizes(resources.getDimension(R.dimen.qr_image_corner_radius))
                        .build()
                    isTitle = true
                }
                item {
                    label = "\tTools"
                    icon = R.drawable.ic_tools
                    iconSize = 18
                    hasNestedItems
                    callback = {
                        val intent = Intent(this@HomeActivity, ToolsActivity::class.java)
                        startActivity(intent)
                    }
                }

            }
            section {
                item {
                    label = "\tSettings"
                    icon = R.drawable.ic_cog
                    iconSize = 18
                    callback = {
                        TimeOutUtil.getInstance().updatePin()
                        val intent = Intent(this@HomeActivity, SettingsActivity::class.java)
                        startActivity(intent)
                    }
                }
                item {
                    label = "\tExit"
                    iconSize = 18
                    iconColor = ContextCompat.getColor(this@HomeActivity, R.color.mpm_red)
                    labelColor = ContextCompat.getColor(this@HomeActivity, R.color.mpm_red)
                    icon = R.drawable.ic_baseline_power_settings_new_24
                    callback = {
                        this@HomeActivity.onBackPressed()
                    }
                }
            }
        }
        popupMenu.show(this@HomeActivity, it)
    }
    /**
     * Auto-sync once, when Tor first reports ON. Single-shot: kmp-tor can
     * re-emit ON during bootstrap, and each emission arming a fresh
     * fetchBalance was the rapid round churn at startup (#47).
     */
    private fun fetch(model: HomeViewModel) {
        if (!SentinelState.isRecentlySynced()) {
            if (SentinelState.isTorRequired() && SentinelTorManager.getTorState().state == EnumTorState.ON) {
                torAutoSyncFired = true
                model.fetchBalance()
            } else {
                SentinelTorManager.getTorStateLiveData().observe(this, ({
                    if (it.state == EnumTorState.ON && !torAutoSyncFired) {
                        torAutoSyncFired = true
                        model.fetchBalance()
                    }
                }))
            }
        }
    }

    private fun currentDisplayMode(): BalanceDisplayMode =
        BalanceDisplayMode.fromString(prefsUtil.balanceDisplayMode)

    /**
     * Re-renders the home balance according to the persisted display mode
     * (BTC / sats / masked). Street mode always wins and masks everything.
     */
    private fun applyBalanceDisplayMode() {
        val mode = currentDisplayMode()
        val showFiat = BalanceDisplayFormatter.showsFiat(
            mode, prefsUtil.streetMode == true, prefsUtil.fiatDisabled == true
        )
        if (balance == -1L) {
            paintFiat(showFiat)
            renderFrozenCaption()
            return
        }
        if (prefsUtil.streetMode == true || mode == BalanceDisplayMode.MASKED) {
            binding.homeBalanceBtc.text = BalanceDisplayFormatter.MASKED_TEXT
            paintFiat(false)
            renderFrozenCaption()
            return
        }
        if (mode == BalanceDisplayMode.SATS) {
            binding.homeBalanceBtc.text = String.format(java.util.Locale.US, "%,d sats", balance)
        } else {
            updateBalance(balance)
        }
        paintFiat(showFiat)
        renderFrozenCaption()
    }

    /** Blank and gone. A hidden or unpriced fiat line must not read "00.00". */
    private fun paintFiat(show: Boolean) {
        val fiat = model.getFiatBalance().value
        if (!show || fiat.isNullOrBlank()) {
            binding.exchangeRateTxt.text = ""
            binding.exchangeRateTxt.visibility = View.GONE
            return
        }
        binding.exchangeRateTxt.visibility = View.VISIBLE
        updateFiat(fiat)
    }

    /**
     * #301: "x BTC frozen (do not spend)" caption under the Home total,
     * shown only while some coin is marked do-not-spend. Renders in the
     * active display mode and hides entirely in street/masked modes: the
     * frozen sum is balance information of the same class as the total.
     */
    private fun renderFrozenCaption() {
        val frozenValue = model.getFrozenBalance().value ?: 0L
        if (frozenValue <= 0L || prefsUtil.streetMode == true ||
            currentDisplayMode() == BalanceDisplayMode.MASKED
        ) {
            binding.frozenBalanceTxt.visibility = View.GONE
            binding.frozenBalanceTxt.text = ""
            return
        }
        binding.frozenBalanceTxt.visibility = View.VISIBLE
        binding.frozenBalanceTxt.text = when (currentDisplayMode()) {
            BalanceDisplayMode.SATS -> String.format(
                java.util.Locale.US, "%,d sats frozen (do not spend)", frozenValue
            )
            else -> "${MonetaryUtil.getInstance().getBTCDecimalFormat(frozenValue)} BTC frozen (do not spend)"
        }
    }

    override fun onResume() {
        super.onResume()
        applyBalanceDisplayMode()
        collectionsAdapter.notifyDataSetChanged()
        // #404: per-device, one-shot notification ask for setups that
        // did not just complete on this screen (restores above all).
        // maybeAskNotificationPermission gates itself: no-op when
        // already granted, already asked, or setup still mid-flight.
        maybeAskNotificationPermission()
    }

    private fun setUp() {
        if (prefsUtil.firstRun == true && AppUtil.getInstance(applicationContext).isSideLoaded) {
            // #404: the first-run choice is now restore-vs-new; the
            // network choice follows on the "Set up new" arm. Same
            // #210 contract: stable tag, stable request key, args in
            // arguments, answers through the durable listener in
            // onCreate. Tag dedupe: a recreation re-runs setUp() while
            // the restored sheet is already showing.
            if (supportFragmentManager.findFragmentByTag(TAG_FIRST_RUN_RESTORE_SHEET) == null) {
                ConfirmBottomSheet.newInstance(
                    label = "Set up Sentinel",
                    message = "Restore from a backup, or start fresh on this device?",
                    positiveText = "Restore from backup",
                    negativeText = "Set up new",
                    isCancelable = false,
                    requestKey = REQUEST_KEY_FIRST_RUN_RESTORE
                ).show(supportFragmentManager, TAG_FIRST_RUN_RESTORE_SHEET)
            }
        } else {
            // #404: firstRun is consumed by the restore-vs-new choice
            // above, so this arm means setup already progressed. If a
            // restored network sheet is still showing (process death
            // between "Set up new" and the Mainnet/Testnet tap), hold
            // off - its durable listener re-runs the flow when it is
            // answered, and stacking the Dojo wizard under a pending
            // choice is the stacking bug #210 fixed for the sheets.
            if (supportFragmentManager.findFragmentByTag(TAG_FIRST_RUN_NETWORK_SHEET) == null) {
                showServerConfig()
            }
        }
    }

    /**
     * #210: state-derived outcome of the first-run network choice -
     * deliberately free of captured context, so the durable listener can
     * call it from whichever activity instance happens to be alive.
     * #404: firstRun is consumed by the restore-vs-new choice now;
     * this handler no longer touches it.
     */
    private fun onFirstRunNetworkChoice(mainnet: Boolean) {
        if (!mainnet) {
            prefsUtil.testnet = true
        }
        showServerConfig()
    }

    /**
     * #404: state-derived outcome of the first-run restore-vs-new
     * choice, same shape as the network leg above. "Restore from
     * backup" hands off to ImportBackUpActivity; "Set up new" chains
     * into the network choice. firstRun is consumed HERE - the
     * restore sheet owns it - so backing out of the import screen
     * lands on plain Home and the next launch goes straight to
     * normal setup; accepted consequence, noted in the PR body.
     */
    private fun onFirstRunRestoreChoice(restore: Boolean) {
        prefsUtil.firstRun = false
        if (restore) {
            startActivity(Intent(applicationContext, ImportBackUpActivity::class.java))
        } else {
            showFirstRunNetworkSheet()
        }
    }

    /**
     * #210's network-choice sheet, extracted: the "Set up new" arm of
     * the restore choice and any future caller lead here. Tag dedupe
     * - a recreation re-runs the caller while the restored sheet is
     * already showing.
     */
    private fun showFirstRunNetworkSheet() {
        if (supportFragmentManager.findFragmentByTag(TAG_FIRST_RUN_NETWORK_SHEET) == null) {
            ConfirmBottomSheet.newInstance(
                label = "Choose network",
                positiveText = "Mainnet",
                negativeText = "Testnet",
                isCancelable = false,
                requestKey = REQUEST_KEY_FIRST_RUN_NETWORK
            ).show(supportFragmentManager, TAG_FIRST_RUN_NETWORK_SHEET)
        }
    }

    private fun showServerConfig() {
        if (prefsUtil.apiEndPoint.isNullOrEmpty()) {
            connectingDojo = true
            if (!AndroidUtil.isPermissionGranted(Manifest.permission.CAMERA, applicationContext)) {
                this.askCameraPermission()
            } else {
                showDojoSetUpBottomSheet()
            }
        }
    }

    private fun showDojoSetUpBottomSheet() {
        // #220: the restored sheet is already in the FragmentManager.
        // Showing another stacks a second wizard over the one the
        // durable listener is waiting on.
        if (supportFragmentManager.findFragmentByTag(TAG_DOJO_SETUP) != null) return
        DojoConfigureBottomSheet.newInstance(
            requestKey = REQUEST_KEY_DOJO_SETUP
        ).show(supportFragmentManager, TAG_DOJO_SETUP)
    }

    /**
     * #220: state-derived. Success is "pairing live, endpoints set"
     * (#159); the body re-reads prefs rather than closing over the sheet.
     */
    private fun onDojoSetupFinished() {
        if (!prefsUtil.isAPIEndpointEnabled()) {
            Toast.makeText(applicationContext, "No Dojo connected", Toast.LENGTH_SHORT).show()
        } else {
            // #404: setup just completed on this screen - the calm
            // moment for the one-shot notification ask on this arm.
            // The helper queues the rescan offer until the rationale
            // dialog (if any) is dismissed, so the two never stack.
            maybeAskNotificationPermission { offerRescanPublicKeys() }
        }
    }

    /**
     * The rescan offer #220 attached to first-Dojo success: public
     * keys added before the Dojo existed were never imported
     * anywhere, so offer to register them now. This sheet is only
     * ever reached from the first-time setup flow, so a successful
     * connection here is always the user's first Dojo.
     * collectionsSnapshot(): the repository list is not public.
     */
    private fun offerRescanPublicKeys() {
        val collections = model.repository.collectionsSnapshot()
        if (collections.any { it.pubs.isNotEmpty() }) {
            PubKeyRescanner.confirmAndRescan(
                this@HomeActivity,
                collections.flatMap { it.pubs },
                collections.map { it.id }
            )
        }
    }

    /**
     * #404: the one-shot, per-device notification permission ask.
     * Either setup arm lands here: onDojoSetupFinished on a fresh
     * setup, the first onResume after a restore. Gate: setup finished
     * (isAPIEndpointEnabled - mid-setup Home sits resumed under the
     * Dojo sheet with firstRun already false, so firstRun alone would
     * stack this ask on top of the wizard), API 33+, not granted, not
     * yet asked. Denial counts as asked; a grant made from system
     * settings is adopted silently on the next resume. A no-Dojo
     * restore never satisfies the gate and is never asked - it stays
     * grantable from system settings; documented in the PR body.
     */
    private fun maybeAskNotificationPermission(onComplete: () -> Unit = {}) {
        val granted = AndroidUtil.isPermissionGranted(
            Manifest.permission.POST_NOTIFICATIONS, applicationContext
        )
        val sdkEligible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        if (granted && prefsUtil.notificationPromptDone != true) {
            prefsUtil.notificationPromptDone = true
        }
        if (!prefsUtil.isAPIEndpointEnabled() || !sdkEligible
            || granted || prefsUtil.notificationPromptDone == true
        ) {
            onComplete()
            return
        }
        // Marked BEFORE the ask, so process death mid-dialog cannot
        // re-ask on the next resume.
        prefsUtil.notificationPromptDone = true
        this.askNotificationPermission(onComplete)
    }

    private fun updateBalance(it: Long) {
        if (prefsUtil.streetMode == true)
            binding.homeBalanceBtc.text = "********"
        else
            binding.homeBalanceBtc.text = "${MonetaryUtil.getInstance().getBTCDecimalFormat(it)} BTC"
    }

    private fun updateFiat(it: String) {
        if (prefsUtil.streetMode == true)
            binding.exchangeRateTxt.text = "********"
        else
            binding.exchangeRateTxt.text = it
    }

    private fun showPubKeyBottomSheet() {
        val bottomSheetFragment = AddNewPubKeyBottomSheet(secure = prefsUtil.displaySecure == true)
        bottomSheetFragment.show(supportFragmentManager, bottomSheetFragment.tag)
    }


    private fun setUpCollectionList() {

        collectionsAdapter.setOnClickListener {
            startActivity(Intent(applicationContext, CollectionDetailsActivity::class.java).apply {
                putExtra("collection", it.id)
            })
        }

        linearLayoutManager = LinearLayoutManager(this)
        linearLayoutManager.orientation = LinearLayoutManager.VERTICAL
        val decorator = RecyclerViewItemDividerDecorator(ContextCompat.getDrawable(applicationContext, R.drawable.divider_tx)!!)
        binding.collectionRecyclerView.apply {
            adapter = collectionsAdapter
            layoutManager = linearLayoutManager
            itemAnimator = SlideInItemAnimator(slideFromEdge = Gravity.TOP)
            setHasFixedSize(true)
            addItemDecoration(decorator)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!SentinelState.checkedClipBoard) {
            SentinelState.checkedClipBoard = true
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // NOTE: grantResults can be EMPTY when the dialog is cancelled - never index it directly.
        when (requestCode) {
            SentinelActivity.Companion.CAMERA_PERMISSION -> {
                when (permissionResultOf(grantResults)) {
                    PermissionResult.GRANTED -> Unit
                    PermissionResult.DENIED ->
                        Toast.makeText(this, "Permission denied", Toast.LENGTH_LONG).show()
                    // User dismissed the dialog; take no action at all.
                    PermissionResult.CANCELLED -> return
                }
                if (connectingDojo) showDojoSetUpBottomSheet() else showPubKeyBottomSheet()
            }

            SentinelActivity.Companion.NOTIF_PERMISSION -> {
                when (permissionResultOf(grantResults)) {
                    PermissionResult.GRANTED ->
                        Toast.makeText(this, "Notification permissions granted.", Toast.LENGTH_SHORT).show()
                    PermissionResult.DENIED ->
                        Toast.makeText(this, "Notification permissions denied.", Toast.LENGTH_SHORT).show()
                    PermissionResult.CANCELLED -> Unit
                }
            }
        }
    }

    /**
     * #299: routes the camera rationale's NO through the same branch
     * onRequestPermissionsResult runs for the system dialog, instead
     * of the base class's unconditional AddNewPubKeyBottomSheet. On
     * the first-run path (showServerConfig sets connectingDojo) the
     * user declined the camera on the way to Dojo setup - the sheet
     * still pairs via Paste with no camera. The FAB path sets
     * connectingDojo = false and keeps the pubkey sheet.
     */
    override fun onCameraRationaleDeclined() {
        if (connectingDojo) showDojoSetUpBottomSheet() else showPubKeyBottomSheet()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.home_menu, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onBackPressed() {
        MaterialAlertDialogBuilder(this)
                .setTitle(resources.getString(R.string.confirm_exit))
                .setMessage(resources.getString(R.string.ask_you_sure_exit))
                .setNegativeButton(resources.getString(R.string.no)) { _, _ ->
                }
                .setPositiveButton(resources.getString(R.string.yes)) { _, _ ->
                    SentinelTorManager.stop()
                    super.onBackPressed()
                }
                .show()
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.let { setNetWorkMenu(it) }
        return super.onPrepareOptionsMenu(menu)
    }

    private fun setNetWorkMenu(menu: Menu) {
        val alertMenuItem: MenuItem = menu.findItem(R.id.activity_home_menu_network)
        val rootView = alertMenuItem.actionView ?: return
        val statusCircle = rootView.findViewById<View>(R.id.home_menu_network_shape) as FrameLayout

        // Traffic-light: green = Tor connected AND synced, flashing yellow =
        // connecting/syncing, red = failed. Driven by the ViewModel so Tor state
        // and sync state are considered together.
        // #184: the indicator LiveData is one shared mediator for the process
        // now - a menu re-pass must retire the previous observer, or the
        // stale observer keeps rendering into the disposed controller while
        // the fresh controller misses the replayed current value.
        indicatorController?.dispose()
        indicatorObserver?.let { model.connectionIndicator().removeObserver(it) }
        val controller = ConnectionIndicatorController(statusCircle)
        indicatorController = controller
        val observer = Observer<ConnectionIndicator> { controller.render(it) }
        indicatorObserver = observer
        model.connectionIndicator().observe(this, observer)

        rootView.setOnClickListener {
            startActivity(Intent(this, NetworkActivity::class.java))
        }
    }

    override fun onDestroy() {
        indicatorController?.dispose()
        indicatorController = null
        // #184: lifecycle teardown owns observer removal; this drops the
        // stale reference so a later pass cannot reuse a dead observer.
        indicatorObserver = null
        super.onDestroy()
    }

    fun connectSocket() {
        try {
            webSocketHandler.connect()
        } catch (ex: Exception) {
        }
    }

    companion object {
        // #210: stable identity for the first-run network-choice sheet and
        // its result - a stable TAG enables the recreation dedupe in
        // setUp(); a stable REQUEST_KEY enables the durable listener
        // registered in onCreate.
        const val TAG_FIRST_RUN_NETWORK_SHEET = "first_run_network_choice_sheet"
        const val REQUEST_KEY_FIRST_RUN_NETWORK = "first_run_network_choice_result"
        // #404: stable identity for the first-run restore-vs-new sheet
        // and its result - same contract as the #210 pair above: the
        // TAG enables recreation dedupe in setUp(), the REQUEST_KEY
        // enables the durable listener registered in onCreate.
        const val TAG_FIRST_RUN_RESTORE_SHEET = "first_run_restore_choice_sheet"
        const val REQUEST_KEY_FIRST_RUN_RESTORE = "first_run_restore_choice_result"
        const val TAG_DOJO_SETUP = "home_dojo_setup_sheet"
        const val REQUEST_KEY_DOJO_SETUP = "home_dojo_setup_result"
    }
}
