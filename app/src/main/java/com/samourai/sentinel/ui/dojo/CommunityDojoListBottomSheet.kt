package com.samourai.sentinel.ui.dojo

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.button.MaterialButton
import com.samourai.sentinel.R
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.views.GenericBottomSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.samourai.sentinel.helpers.fromJSON
import org.koin.java.KoinJavaComponent.inject
import java.text.DateFormat
import java.util.Date

/**
 * Lists the community Dojos published by Dojo Bay (fetched live, over Tor) and
 * lets the user either pair directly to one or copy its pairing JSON.
 *
 * The last list fetched successfully is saved ([CommunityDojoCache]). It is
 * shown straight away while a fresh fetch runs, and stays up with a
 * "Dojo Bay unreachable" indicator and its date when the fetch fails. The
 * directory address is a user setting ([DojoBaySource], toolbar menu).
 *
 * Only ever shown after the caller has put the user through
 * [DojoConfigureBottomSheet]'s privacy warning dialog - this sheet itself assumes
 * that consent has already been given.
 *
 * #210: no primary-constructor parameters anymore - the required lambda made
 * this class non-recreatable (Fragment$InstantiationException on process death
 * or a ui-mode change while the list was showing). Selection resolves the host
 * sheet at click time, so a restored list still pairs.
 */
class CommunityDojoListBottomSheet() : GenericBottomSheet() {

    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java)

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var retryButton: MaterialButton
    private lateinit var changeAddressButton: MaterialButton
    private lateinit var banner: LinearLayout
    private lateinit var bannerText: TextView
    private lateinit var bannerRetry: MaterialButton

    private var fetchInFlight = false

    /** True while "Tor is still starting (n%)…" is shown after the Tor wait limit. */
    private var torStillStarting = false

    /** The directory this sheet reads; re-read when the address changes. */
    private var source: String = DojoBayConstants.BASE_URL

    /** Bumped when the address changes, so a fetch for the old address is dropped. */
    private var generation = 0

    /** Fails the Tor wait if Tor never starts (CommunityDojoTorWait.START_GRACE_MS). */
    private var torWaitJob: Job? = null

    /** Saved list for [source], loaded once per open and after each success. */
    private var cached: CommunityDojoCache.Entry? = null

    private fun cacheStore(): CommunityDojoCache =
        CommunityDojoCache.forDirectory(requireContext().applicationContext.noBackupFilesDir)

    /** #135: ticks elapsed seconds into the status line while a fetch runs. */
    private var elapsedTicker: Job? = null

    /*
     * #135: the dominant directory latency is Dojo Bay's own probing of every
     * listed node (server-side, unshortenable from here). A client-side 120 s
     * timeout therefore does NOT mean the work was wasted - the operator
     * measured attempt 2 answering in ~3 s right after a 120 s timeout, the
     * signature of a warmed probe cache. One automatic retry converts the
     * worst first-run experience into a self-healing one. The retry follows
     * a failure whichever side gave up (the app's timeout or Tor failing the
     * stream), on a fresh Tor circuit: see CommunityDojoFetchPolicy.
     */

    /**
     * How many times this sheet has loaded or fetched the list. A test
     * reads it to show that coming back from DON'T PAIR loads nothing.
     */
    @VisibleForTesting
    internal var directoryLoads = 0
        private set

    private val adapter: CommunityDojoAdapter = CommunityDojoAdapter(
        onSelect = { item -> selectListing(item) },
        onCopy = { item -> copyPairingJson(item) },
        onOpenPayNym = { code -> context?.let { PayNymPage.openSigner(it, lifecycleScope, code) } },
    )

    /**
     * A listing picked: a verified one goes to the full pre-pair flow, an
     * unverified one gets the notice.
     */
    private fun selectListing(item: CheckedDojo) {
        val node = item.node
        // Only a listing signed by its owner's payment code pairs, and it
        // pairs with the details from inside the signature, never the
        // directory's unsigned copy.
        val json = item.verified?.signedPayloadJson
        if (json == null) {
            showUnverifiedNotice()
        } else {
            // Snapshot directory display metadata now: past this callback
            // only the pairing JSON travels, and the stored payload has no
            // name/flag/jurisdiction. The node object is already in memory
            // from the directory fetch (over Tor) - nothing new is requested.
            // The snapshot rides with the payload and is saved only when
            // the pairing commits: DON'T PAIR leaves nothing behind.
            val snapshot = DojoDisplaySnapshot(
                url = fromJSON<DojoPairing>(json)?.pairing?.url,
                name = node.name?.takeIf { it.isNotBlank() },
                flag = node.flagEmoji,
                // #256: jurisdiction rides the same snapshot - a directory
                // datum, display-only, cleared with the pairing (see
                // DojoUtility.clearDojo).
                jurisdiction = node.jurisdiction?.takeIf { it.isNotBlank() },
                // #250: the network rides the same snapshot - the source
                // the pre-pair dialog's guard reads. It comes from the
                // signed URL only (CheckedDojo.network), never from the
                // directory's unsigned label, so a relabelled listing can
                // never bless a pairing for the wrong network.
                network = item.network.directoryName(),
                // Another listing in this list uses the same name under
                // a different signer: the dialog says so.
                nameCollision = adapter.nameCollides(item),
                // The version the row shows, for the pre-pair dialog.
                version = node.version?.takeIf { it.isNotBlank() },
            )
            // #221: the configure sheet is the parent. onScanPayload
            // validates, stores the payload, and moves to the connect
            // page, and it toasts on an invalid payload. This list stays
            // open under the pre-pair dialog: DON'T PAIR comes back to it
            // as it is, and Pair closes it (DojoConfigureBottomSheet).
            val host = parentFragment as? DojoConfigureBottomSheet
            host?.onScanPayload(json, snapshot, signerCode = item.verified?.paymentCode)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottomsheet_community_dojo_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val toolbar = view.findViewById<MaterialToolbar>(R.id.communityDojoToolbar)
        recyclerView = view.findViewById(R.id.communityDojoRecyclerView)
        progressBar = view.findViewById(R.id.communityDojoProgress)
        statusText = view.findViewById(R.id.communityDojoStatusText)
        retryButton = view.findViewById(R.id.communityDojoRetryButton)
        changeAddressButton = view.findViewById(R.id.communityDojoChangeAddressButton)
        banner = view.findViewById(R.id.communityDojoBanner)
        bannerText = view.findViewById(R.id.communityDojoBannerText)
        bannerRetry = view.findViewById(R.id.communityDojoBannerRetry)

        toolbar.setNavigationOnClickListener { dismiss() }
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_dojobay_address) {
                showAddressDialog()
                true
            } else {
                false
            }
        }
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        retryButton.setOnClickListener { connectTorThenFetch() }
        bannerRetry.setOnClickListener { connectTorThenFetch() }
        changeAddressButton.setOnClickListener { showAddressDialog() }

        loadCacheThenFetch()
    }

    private fun currentNetwork(): String = CommunityDojoDisplay.networkName(prefsUtil.testnet == true)

    /** Reads the saved list off the main thread, shows it if it has Dojos for this network, then fetches. */
    private fun loadCacheThenFetch() {
        directoryLoads++
        source = DojoBaySource.effectiveBaseUrl(prefsUtil.dojoBayUrl)
        val store = cacheStore()
        val forSource = source
        viewLifecycleOwner.lifecycleScope.launch {
            val pinned = prefsUtil.dojoBayPinnedCode
            val network = currentNetwork()
            val saved = withContext(Dispatchers.IO) { store.read(forSource) }
            val shown = withContext(Dispatchers.Default) { checkCached(saved, forSource, pinned, network) }
            if (!isAdded) return@launch
            cached = saved
            cachedShown = shown
            if (refusedWithoutCode()) return@launch
            connectTorThenFetch()
        }
    }

    /**
     * A custom address saved without an operator code is refused before
     * anything is requested: no Tor start, no operator.json, no list.
     */
    private fun refusedWithoutCode(): Boolean {
        if (DojoBayTrust.expectedCode(source, prefsUtil.dojoBayPinnedCode) != null) return false
        torWaitJob?.cancel()
        render(CommunityDojoDisplay.Refused(DojoBayTrust.RefusalReason.NO_CODE, null))
        return true
    }

    private fun showAddressDialog() {
        DojoBayAddressDialog.show(
            requireContext(),
            prefsUtil,
            requireContext().applicationContext.noBackupFilesDir,
            viewLifecycleOwner.lifecycleScope,
        ) {
            if (!isAdded) return@show
            // New address: the old list is gone (the dialog cleared it);
            // fetch from the new one. An in-flight fetch against the old
            // address finishes into the guard below and is dropped.
            generation++
            cached = null
            cachedShown = emptyList()
            adapter.submitList(emptyList())
            fetchInFlight = false
            loadCacheThenFetch()
        }
    }

    /** The saved list for this network, or empty. */
    private fun cachedForNetwork(): List<CheckedDojo> = cachedShown

    /** [cached], trust-checked and signature-checked once, so the 1 s ticker does not redo it. */
    private var cachedShown: List<CheckedDojo> = emptyList()

    private fun checkCached(saved: CommunityDojoCache.Entry?, base: String, pinned: String?, network: String): List<CheckedDojo> {
        if (saved == null) return emptyList()
        if (!DojoBayTrust.cachedListTrusted(base, saved.operatorCode, pinned)) return emptyList()
        return CommunityDojoDisplay.forNetwork(saved.nodes, network)
    }

    private fun formatDate(ms: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

    /** Day and month in the user's locale ("12 Oct"), with the year when it isn't this year. */
    private fun formatSavedDate(ms: Long): String {
        val locale = resources.configuration.locales[0]
        val skeleton = CommunityDojoDisplay.savedDateSkeleton(ms, System.currentTimeMillis())
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
        return java.text.SimpleDateFormat(pattern, locale).format(Date(ms))
    }

    private fun connectTorThenFetch() {
        if (fetchInFlight) return
        torStillStarting = false
        directoryLoads++
        // State can say ON from a stale LiveData while the proxy object
        // is not (yet) available; require both to take the fast path.
        // #135: ON alone is NOT enough - it is published at boot=5, before
        // the SOCKS listener exists and before boot=100. Ask the manager for
        // full readiness so a fresh start cannot fetch through a proxy-less
        // or circuit-less client.
        if (SentinelTorManager.isTorReady()) {
            showLoading(getString(R.string.community_dojo_connecting_tor))
            fetchDirectory()
            return
        }
        // Neutral state while Tor comes up: on a cold start Tor still reports
        // OFF for a few seconds after start(), which is not a failure
        // (CommunityDojoTorWait).
        showLoading(getString(R.string.community_dojo_waiting_tor), waitingForTor = true)
        SentinelTorManager.setUp(requireContext().applicationContext as Application)
        // #170: directory-fetch start, no stomp on the user's toggle.
        SentinelTorManager.start()
        var sawStart = false
        val liveData = SentinelTorManager.getTorStateLiveData()
        // One observer per sheet: a Retry must not stack another one.
        liveData.removeObservers(viewLifecycleOwner)
        torWaitJob?.cancel()
        torWaitJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(CommunityDojoTorWait.START_GRACE_MS)
            if (CommunityDojoTorWait.failsAfterGrace(sawStart, SentinelTorManager.isTorReady(), fetchInFlight)) {
                renderTorFailure()
                return@launch
            }
            // Tor started but "Waiting for Tor…" must not stay up forever
            // (a stuck or very slow bootstrap). The observer stays, so a
            // late ready still starts the fetch.
            delay(CommunityDojoTorWait.READY_LIMIT_MS - CommunityDojoTorWait.START_GRACE_MS)
            if (CommunityDojoTorWait.failsAfterReadyLimit(SentinelTorManager.isTorReady(), fetchInFlight)) {
                renderTorWaitLimit()
            }
        }
        liveData.observe(viewLifecycleOwner) { state ->
            sawStart = CommunityDojoTorWait.sawStart(state.state, sawStart)
            // Keep the "still starting" percentage live until Tor is ready
            // (the fetch replaces it) or stops (the failure replaces it as
            // soon as Tor is stopping, #585 I3).
            if (torStillStarting && !fetchInFlight) {
                if (CommunityDojoTorWait.stillStartingEnds(state.state)) {
                    renderTorFailure()
                } else {
                    CommunityDojoTorWait.stillStartingProgress(state.state, state.progressIndicator)
                        ?.let { renderTorStillStarting(it) }
                }
            }
            when (CommunityDojoTorWait.onState(state.state, sawStart)) {
                CommunityDojoTorWait.Action.FETCH_WHEN_READY -> {
                    // #135: this sheet has no transition dedupe, so every ON
                    // emission of the 5->100 climb used to call
                    // fetchDirectory() at boot=5-ish. Await full readiness
                    // instead (returns false on OFF/STOPPING, letting the
                    // OFF handling own the failure); the fetchInFlight
                    // guard collapses any overlapping ready emissions.
                    viewLifecycleOwner.lifecycleScope.launch {
                        if (SentinelTorManager.awaitTorReady()) {
                            torWaitJob?.cancel()
                            if (!fetchInFlight && isAdded) {
                                // Back to the loading state in case the
                                // wait limit already showed the failure.
                                showLoading(getString(R.string.community_dojo_connecting_tor))
                                fetchDirectory()
                            }
                        }
                    }
                }
                // Tor started and then stopped while the sheet is open;
                // surface a retryable error instead of spinning forever.
                CommunityDojoTorWait.Action.FAIL -> if (!fetchInFlight) {
                    torWaitJob?.cancel()
                    renderTorFailure()
                }
                CommunityDojoTorWait.Action.WAIT -> Unit
            }
        }
    }

    /**
     * The "Waiting for Tor…" limit passed with no fetch. A bootstrap that is
     * past 0 % is still running (a first start can take minutes, #581), so
     * say so with its percentage instead of "Tor is not connected". The
     * observer keeps listening and fetches as soon as Tor is ready.
     */
    private fun renderTorWaitLimit() {
        val tor = SentinelTorManager.getTorState()
        val progress = CommunityDojoTorWait.stillStartingProgress(tor.state, tor.progressIndicator)
        if (progress == null) renderTorFailure() else renderTorStillStarting(progress)
    }

    /** Same layout and buttons as the Tor failure, with the live bootstrap percentage. */
    private fun renderTorStillStarting(progress: Int) {
        if (!isAdded) return
        torStillStarting = true
        val display = CommunityDojoDisplay.afterFailure(
            CommunityDojoRepository.Failure.TOR_UNAVAILABLE,
            cached,
            source,
            prefsUtil.dojoBayPinnedCode,
            currentNetwork(),
            attempts = 0,
        )
        if (display is CommunityDojoDisplay.Cached) {
            showList(
                display.dojos,
                bannerMessage = getString(
                    R.string.community_dojo_cached_tor_starting,
                    formatDate(display.fetchedAtMs),
                    progress,
                ),
            )
        } else {
            showMessage(
                getString(R.string.community_dojo_tor_still_starting, progress),
                canRetry = true,
                canChangeAddress = true,
            )
        }
    }

    /** Tor is not available: the saved list, if any, stays up with the indicator. */
    private fun renderTorFailure() {
        if (!isAdded) return
        torStillStarting = false
        render(
            CommunityDojoDisplay.afterFailure(
                CommunityDojoRepository.Failure.TOR_UNAVAILABLE,
                cached,
                source,
                prefsUtil.dojoBayPinnedCode,
                currentNetwork(),
                attempts = 0,
            )
        )
    }

    private fun fetchDirectory() {
        if (fetchInFlight) return
        if (refusedWithoutCode()) return
        fetchInFlight = true
        torStillStarting = false
        val forSource = source
        val gen = generation
        val store = cacheStore()
        viewLifecycleOwner.lifecycleScope.launch {
            var attempt = 0
            while (true) {
                attempt++
                startElapsedTicker()
                try {
                    val directory = CommunityDojoRepository.fetchDirectory(
                        forSource,
                        expectedCode = DojoBayTrust.expectedCode(forSource, prefsUtil.dojoBayPinnedCode),
                    )
                    val nodes = directory.nodes
                    val fetchedAt = System.currentTimeMillis()
                    val decision = DojoBayTrust.decide(forSource, directory.operator, prefsUtil.dojoBayPinnedCode)
                    // Only a trusted directory's list is saved. The fetch runs
                    // in the view's lifecycle scope, so closing the sheet
                    // cancels it and nothing late is saved. A served
                    // operator.json that proves a different or failed binding
                    // drops the saved list (DojoBayTrust.dropsSavedList); a
                    // refusal without such proof leaves it alone. An empty
                    // list is not saved: the previous one stays as the
                    // offline fallback.
                    val keepSaved = CommunityDojoDisplay.keepsSavedList(nodes)
                    if (gen == generation) {
                        withContext(Dispatchers.IO) {
                            try {
                                if (decision is DojoBayTrust.Decision.Trusted) {
                                    if (!keepSaved) store.write(forSource, nodes, fetchedAt, decision.operatorCode)
                                } else if (DojoBayTrust.dropsSavedList(decision)) {
                                    store.clear()
                                }
                            } catch (e: Exception) {
                                // A failed save only costs the offline fallback.
                            }
                        }
                    }
                    // The directory fetch may take up to 120s and can complete
                    // after the user has dismissed this sheet. Touching the
                    // fragment context or its views at that point crashes with
                    // IllegalStateException (not attached to a context), so
                    // bail out whenever we are no longer attached.
                    if (!isAdded || gen != generation) {
                        // A stale fetch must not stop the ticker of the
                        // fetch that replaced it.
                        if (gen == generation) {
                            fetchInFlight = false
                            stopElapsedTicker()
                        }
                        return@launch
                    }
                    val network = currentNetwork()
                    val display = withContext(Dispatchers.Default) {
                        CommunityDojoDisplay.afterSuccess(directory, decision, network, cached)
                    }
                    if (decision is DojoBayTrust.Decision.Trusted && !keepSaved) {
                        cached = CommunityDojoCache.Entry(forSource, fetchedAt, nodes, decision.operatorCode)
                        cachedShown = (display as? CommunityDojoDisplay.Fresh)?.dojos.orEmpty()
                    } else if (DojoBayTrust.dropsSavedList(decision)) {
                        cached = null
                        cachedShown = emptyList()
                    }
                    fetchInFlight = false
                    stopElapsedTicker()
                    render(display)
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (gen != generation) return@launch
                    stopElapsedTicker()
                    val failure = CommunityDojoRepository.classify(e).failure
                    if (CommunityDojoFetchPolicy.shouldRetry(failure, attempt)) {
                        // #135: the first attempt's server-side probing is
                        // not wasted - retry once before giving up (operator
                        // datum: 120 s timeout then ~3 s success). Also when
                        // Tor failed the stream first: right after a Tor
                        // start the first onion connection can stall, and
                        // the retry's fresh circuit gets past it.
                        if (!isAdded) {
                            fetchInFlight = false
                            return@launch
                        }
                        showLoading(getString(R.string.community_dojo_auto_retry))
                        delay(CommunityDojoFetchPolicy.AUTO_RETRY_DELAY_MS)
                        if (!isAdded) {
                            fetchInFlight = false
                            return@launch
                        }
                        continue
                    }
                    fetchInFlight = false
                    if (!isAdded) {
                        return@launch
                    }
                    val display = withContext(Dispatchers.Default) {
                        CommunityDojoDisplay.afterFailure(
                            failure, cached, forSource, prefsUtil.dojoBayPinnedCode, currentNetwork(),
                            attempts = attempt,
                        )
                    }
                    render(display)
                    return@launch
                }
            }
        }
    }

    private fun render(display: CommunityDojoDisplay) {
        when (display) {
            is CommunityDojoDisplay.ListedNone -> showList(
                display.dojos,
                bannerMessage = getString(R.string.community_dojo_cached_listed_none, formatDate(display.fetchedAtMs)),
            )
            CommunityDojoDisplay.DirectoryEmpty ->
                showMessage(getString(R.string.community_dojo_listed_none), canRetry = true, canChangeAddress = true)
            is CommunityDojoDisplay.Fresh -> showList(display.dojos, bannerMessage = null)
            is CommunityDojoDisplay.Empty ->
                showMessage(getString(R.string.community_dojo_empty, display.network), canRetry = true)
            is CommunityDojoDisplay.Cached -> showList(
                display.dojos,
                bannerMessage = when (display.banner) {
                    // "Saved list from 12 Oct · ...": the day and month (and
                    // the year when it isn't this year), as in the design.
                    CommunityDojoDisplay.SavedListBanner.NO_OPERATOR_STATEMENT ->
                        getString(R.string.community_dojo_cached_no_operator, formatSavedDate(display.fetchedAtMs))
                    CommunityDojoDisplay.SavedListBanner.UNREACHABLE ->
                        getString(R.string.community_dojo_cached_unreachable, formatDate(display.fetchedAtMs))
                },
            )
            is CommunityDojoDisplay.Failed -> showMessage(
                if (CommunityDojoFetchPolicy.failedAfterRetry(display.failure, display.attempts)) {
                    // The app already made its one automatic retry.
                    getString(R.string.community_dojo_failed_two_tries)
                } else if (display.failure == CommunityDojoRepository.Failure.NO_OPERATOR_STATEMENT) {
                    getString(R.string.community_dojo_refused_missing, DojoBaySource.label(source))
                } else {
                    getString(R.string.community_dojo_unreachable_no_cache, display.failure.message)
                },
                canRetry = true,
                canChangeAddress = true,
            )
            is CommunityDojoDisplay.Refused -> showMessage(
                refusalText(display),
                canRetry = display.reason != DojoBayTrust.RefusalReason.NO_CODE,
                canChangeAddress = true,
            )
        }
    }

    private fun refusalText(display: CommunityDojoDisplay.Refused): String {
        val isDefault = DojoBaySource.isDefault(source)
        val label = DojoBaySource.label(source)
        return when (display.reason) {
            DojoBayTrust.RefusalReason.NO_CODE ->
                getString(R.string.community_dojo_refused_no_code, label)
            DojoBayTrust.RefusalReason.OPERATOR_MISSING ->
                getString(R.string.community_dojo_refused_missing, label)
            DojoBayTrust.RefusalReason.OPERATOR_UNVERIFIED ->
                getString(R.string.community_dojo_refused_unverified, label)
            DojoBayTrust.RefusalReason.CODE_MISMATCH -> {
                val found = DojoBaySignatures.shortCode(display.operatorCode.orEmpty())
                if (isDefault) {
                    getString(R.string.community_dojo_refused_default_pin, found)
                } else {
                    getString(
                        R.string.community_dojo_refused_pin,
                        found,
                        DojoBaySignatures.shortCode(prefsUtil.dojoBayPinnedCode.orEmpty()),
                    )
                }
            }
        }
    }

    /** #135: 1 s ticks into the status line so the wait stays legible. */
    private fun startElapsedTicker() {
        stopElapsedTicker()
        // First paint: probing copy with "(0 s elapsed)" at once,
        // instead of leaving the previous label up for the first tick.
        renderElapsed(0)
        val startedAt = SystemClock.elapsedRealtime()
        elapsedTicker = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(1_000)
                // Same attachment rule as the fetch completion path: never
                // touch views once the sheet is gone.
                if (!isAdded) return@launch
                val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
                renderElapsed(seconds)
            }
        }
    }

    /** Elapsed time goes on the banner over a saved list, else on the status line. */
    private fun renderElapsed(seconds: Long) {
        val saved = cached
        if (saved != null && cachedForNetwork().isNotEmpty()) {
            bannerText.text = getString(
                R.string.community_dojo_cached_refreshing,
                formatDate(saved.fetchedAtMs),
                seconds,
            )
        } else {
            statusText.text = getString(R.string.community_dojo_fetching_elapsed, seconds)
        }
    }

    private fun stopElapsedTicker() {
        elapsedTicker?.cancel()
        elapsedTicker = null
    }

    /**
     * While loading, a saved list for this network stays visible under the
     * banner (it is still pairable); otherwise the spinner and status line.
     */
    private fun showLoading(message: String, waitingForTor: Boolean = false) {
        changeAddressButton.visibility = View.GONE
        retryButton.visibility = View.GONE
        val saved = cached
        val savedNodes = cachedForNetwork()
        if (saved != null && savedNodes.isNotEmpty()) {
            progressBar.visibility = View.GONE
            statusText.visibility = View.GONE
            banner.visibility = View.VISIBLE
            bannerRetry.visibility = View.GONE
            bannerText.text = if (waitingForTor) {
                getString(R.string.community_dojo_cached_waiting_tor, formatDate(saved.fetchedAtMs))
            } else {
                getString(R.string.community_dojo_cached_refreshing, formatDate(saved.fetchedAtMs), 0)
            }
            recyclerView.visibility = View.VISIBLE
            adapter.submitList(savedNodes)
            return
        }
        banner.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        statusText.visibility = View.VISIBLE
        statusText.text = message
        recyclerView.visibility = View.GONE
    }

    private fun showMessage(message: String, canRetry: Boolean, canChangeAddress: Boolean = false) {
        stopElapsedTicker()
        banner.visibility = View.GONE
        progressBar.visibility = View.GONE
        statusText.visibility = View.VISIBLE
        statusText.text = message
        retryButton.visibility = if (canRetry) View.VISIBLE else View.GONE
        changeAddressButton.visibility = if (canChangeAddress) View.VISIBLE else View.GONE
        recyclerView.visibility = View.GONE
    }

    /** [bannerMessage] null: a fresh list, no banner. Otherwise the saved-list banner with Retry. */
    private fun showList(nodes: List<CheckedDojo>, bannerMessage: String?) {
        stopElapsedTicker()
        progressBar.visibility = View.GONE
        statusText.visibility = View.GONE
        retryButton.visibility = View.GONE
        changeAddressButton.visibility = View.GONE
        if (bannerMessage == null) {
            banner.visibility = View.GONE
        } else {
            banner.visibility = View.VISIBLE
            bannerText.text = bannerMessage
            bannerRetry.visibility = View.VISIBLE
        }
        recyclerView.visibility = View.VISIBLE
        adapter.submitList(nodes)
    }

    /**
     * Why a dimmed listing can't be paired. A Snackbar, not a toast: a toast
     * is cut to two lines on many devices and this text needs three or four.
     */
    private fun showUnverifiedNotice() {
        // Shown in the sheet's own CoordinatorLayout so it is drawn on top of
        // the list; made on the sheet's root it would land in the dialog's
        // coordinator, under the sheet.
        val host = view?.findViewById<View>(R.id.communityDojoSnackbarHost) ?: return
        Snackbar.make(host, R.string.community_dojo_unverified_toast, Snackbar.LENGTH_LONG)
            .setTextMaxLines(6)
            .show()
    }

    private fun copyPairingJson(item: CheckedDojo) {
        val json = item.verified?.signedPayloadJson
        if (json == null) {
            showUnverifiedNotice()
            return
        }
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Dojo pairing payload", json)
        // The pairing JSON carries the Dojo's API key: keep it out of the
        // clipboard preview on Android 13+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }
}
