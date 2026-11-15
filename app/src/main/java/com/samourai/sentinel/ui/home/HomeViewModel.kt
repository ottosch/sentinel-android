package com.samourai.sentinel.ui.home

import androidx.lifecycle.*
import com.samourai.sentinel.core.ConnectionIndicator
import com.samourai.sentinel.core.DojoContact
import com.samourai.sentinel.core.DojoReachability
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.core.SyncState
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.data.repository.ExchangeRateRepository
import com.samourai.sentinel.data.repository.FeeRepository
import com.samourai.sentinel.data.repository.CollectionTxSyncResult
import com.samourai.sentinel.data.repository.PubSyncHealth
import com.samourai.sentinel.data.repository.SyncFailureKind
import com.samourai.sentinel.data.repository.TransactionsRepository
import com.samourai.sentinel.data.repository.syncFailureKind
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.tor.TorState
import com.samourai.sentinel.ui.utils.Preferences
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.util.MonetaryUtil
import com.samourai.sentinel.util.UtxoMetaUtil
import com.samourai.sentinel.util.figuresForActiveNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.bitcoinj.core.Coin
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import kotlin.reflect.KProperty

class HomeViewModel : ViewModel() {

    companion object {
        /**
         * How long a sync may run before we tell the user it's taking a while.
         *
         * This does NOT cancel anything - it only annotates the banner. Tor
         * bootstrapping plus onion circuit setup can legitimately exceed a minute,
         * so cancelling on a timer produced false failures.
         */
        private const val SLOW_SYNC_NOTICE_MS = 30_000L

        /** Auto rounds that fail totally (circuit not yet usable) get at
         *  most this many spaced retries before showing Failed. */
        private const val AUTO_SYNC_RETRIES = 2
        private const val AUTO_RETRY_DELAY_MS = 3_000L

        /**
         * #135: wall-clock budget for waiting out the boot climb before a
         * round fires. Covers the measured cold start (~50 s, 10-02) with
         * margin; on timeout the round defers instead of firing through a
         * proxy-less client, and observeTor() re-arms it at readiness.
         */
        private const val TOR_READY_TIMEOUT_MS = 90_000L
    }

    val repository: CollectionRepository by inject(CollectionRepository::class.java)
    private val exchangeRateRepository: ExchangeRateRepository by inject(ExchangeRateRepository::class.java)
    private val transactionsRepository: TransactionsRepository by inject(TransactionsRepository::class.java)
    private val feeRepository: FeeRepository by inject(FeeRepository::class.java)

    private val errorMessage: MutableLiveData<String> = MutableLiveData()
    private val balance: MutableLiveData<Long> = MutableLiveData()
    private val frozen: MutableLiveData<Long> = MutableLiveData()
    private var netWorkJobs: ArrayList<Job?> = arrayListOf()
    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java)

    /** Single source of truth for the sync lifecycle. */
    private val syncState: MutableLiveData<SyncState> = MutableLiveData(SyncState.Idle)

    fun syncState(): LiveData<SyncState> = syncState

    // #184: endpoint + Tor-toggle writes are indicator inputs. Pref
    // delegates notify listeners BEFORE the value lands (GenericPrefDelegate
    // notifies first, writes after), so pokes travel via postValue - by
    // main-loop dispatch time the write has landed and computeIndicator()
    // reads fresh prefs.
    private val endpointPoke = MutableLiveData<Unit>()

    /**
     * Coalescing guard (#47): at most ONE sync round in flight at a time -
     * whether a user-initiated fetchBalance round or an auto-armed
     * syncCollections round - plus a single trailing re-run if the collection
     * list changed mid-round. Overlapping rounds (each with its own
     * completed-counter, each posting its own terminal state) were the banner
     * flap, the backwards "n of m" counter, and the dojo call storm.
     */
    private var syncRound: Job? = null

    @Volatile
    private var syncRerunPending = false

    /**
     * Content signature (collection ids + pubkeys) of the last round's list.
     * ALL auto re-arms - MediatorLiveData emissions, trailing re-runs,
     * stragglers delivered just after round end - are skipped when the
     * signature is unchanged. Ids alone are too coarse: adding a pubkey to
     * an existing collection keeps the id but must re-sync (#47).
     */
    @Volatile
    private var lastRoundSignature: String? = null

    /**
     * #489: signature of the input list the round in flight was armed
     * with - the baseline the trailing re-run decision compares
     * against. Stamped at round start (both round kinds), and
     * deliberately NOT nulled on failure: lastRoundSignature's #287
     * null is what lets EXTERNAL arms retry a failed round, but it
     * also un-gated the trailing re-run - which the round itself
     * arms ~20x through its own per-pub collectionRepository.update()
     * emissions - so every failed round birthed the next one (idle
     * device leg 2026-11-08: a full round every ~16s, 8 in a row).
     */
    @Volatile
    private var roundStartSignature: String? = null

    /**
     * #489 follow-up. Set when a finished round clears
     * [lastRoundSignature]. The collectionsLiveData delivery of that
     * round's own writes then matches [roundStartSignature] and is
     * not an arm. Cleared when a round is admitted, and when a round
     * defers for Tor: that deferral never wrote the list, and the
     * next emission (or observeTor) is what re-arms it.
     */
    @Volatile
    private var blockOwnListEmission = false

    /** Bounded auto-retry budget; reset on success or user refresh. */
    private var autoRetryBudget = 0

    @Volatile
    private var autoRetryPending = false

    private fun launchSyncRound(collections: ArrayList<PubKeyCollection>) {
        // #175: choke point for every auto arm (observeTor ON re-arm,
        // trailing re-run, getCollections). No collections and no endpoint
        // means no work exists that needs Tor: Idle, never a WaitingForTor
        // banner and never an empty round fabricating Success (#174). The
        // signature stamp is left untouched so a later real round is not
        // signature-skipped.
        if (collections.isEmpty() && !prefsUtil.isAPIEndpointEnabled()) {
            if (syncState.value !is SyncState.Failed) {
                syncState.postValue(SyncState.Idle)
            }
            return
        }
        if (syncRound?.isActive == true) {
            // Drop the re-arm; the in-flight round is already fetching this
            // data. Re-run at most once when it ends so genuine collection
            // changes mid-round are still picked up.
            syncRerunPending = true
            Timber.i("sync round suppressed: round in flight, trailing re-run armed")
            return
        }
        val signature = SyncRerunPolicy.signatureOf(collections)
        if (lastRoundSignature != null && signature == lastRoundSignature) {
            Timber.i("sync round skipped: signature unchanged since last round")
            // #184: a skipped re-arm must not leave the climb's
            // WaitingForTor standing - no other emission is coming, and a
            // stale WaitingForTor is what pulsed the dot amber forever
            // after bootstrap on an unconfigured rig.
            if (syncState.value is SyncState.WaitingForTor) {
                syncState.postValue(SyncState.Idle)
            }
            return
        }
        lastRoundSignature = signature
        // #489: baseline for the trailing re-run decision.
        roundStartSignature = signature
        // A round that actually starts accepts later external arms.
        // The block is only for the round that just cleared the signature.
        blockOwnListEmission = false
        Timber.i("sync round begin: syncCollections collections=${collections.size}")
        syncRound = viewModelScope.launch(Dispatchers.IO) {
            val self = coroutineContext[Job]
            try {
                // #135: no fetch before full readiness (boot=100 AND the
                // SOCKS listener published). buildClient attaches the proxy
                // only from the LISTENERS event; firing earlier resolves the
                // .onion host via system DNS (38 UHE, capture 1010-133246,
                // all pre-T100). On timeout: un-stamp the signature so the
                // observeTor() ready re-arm is not signature-skipped.
                if (SentinelState.isTorRequired() &&
                    !SentinelTorManager.awaitTorReady(TOR_READY_TIMEOUT_MS)
                ) {
                    lastRoundSignature = null
                    // No fetch, so no own write is in flight. A later
                    // list emission must still be able to start the round
                    // if ON does not fire again.
                    blockOwnListEmission = false
                    syncState.postValue(
                        SyncState.WaitingForTor(
                            SentinelTorManager.getTorState().progressIndicator
                        )
                    )
                    return@launch
                }
                syncCollections(collections, self)
            } finally {
                // Identity check: a cancelled/replaced round must never clear
                // the guard state of its successor.
                if (syncRound === self) {
                    syncRound = null
                    if (syncRerunPending) {
                        syncRerunPending = false
                        maybeTrailingRerun()
                    }
                } else {
                    Timber.i("sync round end: superseded, cleanup skipped")
                }
            }
        }
    }

    /**
     * Surfaces a corrupt-payload condition so the UI can warn the user instead of
     * silently showing an empty wallet list.
     */
    fun payloadReadFailure(): LiveData<String?> = repository.readFailure()

    // #184: only these pref writes are dot-relevant.
    private val indicatorPrefs = setOf("apiEndPoint", "apiEndPointTor", "enableTor")

    // Qualified via Preferences, the declaring class - Kotlin resolves
    // nested classifiers only through their declaring class (SentinelState
    // precedent). Declared ABOVE init: Kotlin initializes members in
    // textual order and forward references are compile errors.
    private val prefsListener = object : Preferences.SharedPrefsListener {
        override fun onSharedPrefChanged(property: KProperty<*>) {
            if (property.name !in indicatorPrefs) return
            endpointPoke.postValue(Unit)
        }
    }

    /**
     * #186: true when a poke retired a standing Success on unpair; the
     * next arrive-direction poke (re-pair) spends it on a quiet round so
     * the dot climbs back to GREEN with no manual refresh. #188: round
     * tails on IO threads set it too when they suppress a terminal
     * Success (endpoint vanished mid-round) - hence @Volatile.
     */
    @Volatile
    private var staleSuccessRetired = false

    /**
     * #184: a standing non-retryable Failed is the "No server configured."
     * red; the moment an endpoint exists it is stale. A quiet auto round
     * retires it (empty wallet -> immediate Success -> GREEN; real wallet
     * -> honest terminal state, #47 coalescing guards stacking). Runs on
     * endpointPoke dispatch so the pref write has landed before reads.
     *
     * #411: the retryable flavor is just as obsolete once an endpoint
     * write lands. Pairing over a standing "Could not reach Dojo."
     * matched no arm before - the poke fired and the stale banner stood
     * until a manual pull-to-refresh cleared it. Any standing Failed is
     * now spent on a quiet round: the poke only fires on an endpoint pref
     * write, so a user-earned Failed on an unchanged setup keeps standing
     * until its Retry (#174).
     *
     * #186: the vanish direction. computeIndicator() is stateless and its
     * Success arm is evaluated BEFORE the no-endpoint RED arm, so a
     * standing Success from the paired era keeps the dot GREEN after
     * unpairing until some other input changes. Retire it to Idle here:
     * the Idle emission itself re-arms the mediator (syncState is a
     * source) and the honest no-endpoint RED arm wins at recompute.
     */
    private val endpointKick = Observer<Unit> {
        val sync = syncState.value
        // #188: the kick was silent through two device legs - name the
        // dispatch next time. Booleans and the state class only; never
        // endpoint material (#96).
        Timber.i(
            "endpointKick: endpointSet=%s torEndpointSet=%s sync=%s",
            prefsUtil.apiEndPoint != null,
            prefsUtil.apiEndPointTor != null,
            sync?.javaClass?.simpleName
        )
        when {
            // #411: any standing Failed - retryable or not; see KDoc.
            prefsUtil.isAPIEndpointEnabled() && sync is SyncState.Failed -> {
                fetchBalance(userInitiated = false)
            }
            !prefsUtil.isAPIEndpointEnabled() && sync is SyncState.Success -> {
                staleSuccessRetired = true
                syncState.postValue(SyncState.Idle)
            }
            prefsUtil.isAPIEndpointEnabled() && sync is SyncState.Idle && staleSuccessRetired -> {
                staleSuccessRetired = false
                fetchBalance(userInitiated = false)
            }
        }
    }

    /**
     * #301: re-derive the Home total when do-not-spend marks change. Marks
     * mutate outside the sync cycle (UTXOFragment action mode), so the
     * total previously went stale until the next collectionsLiveData
     * emission - the "values written at discrete moments" half of #301.
     * observeForever needs the main thread; ViewModel creation is main
     * (same contract as endpointPoke above). Removed in onCleared().
     */
    private val blockedMarksObserver = Observer<Unit> { updateBalance() }

    init {
        load()
        observeTor()
        // #184: pairing/unpairing and the Tor toggle re-render the dot.
        // observeForever needs the main thread; ViewModel creation is main.
        prefsUtil.addListener(prefsListener)
        endpointPoke.observeForever(endpointKick)
        // #301: do-not-spend marks changed - re-derive the total.
        UtxoMetaUtil.blockedChanges.observeForever(blockedMarksObserver)
    }

    override fun onCleared() {
        endpointPoke.removeObserver(endpointKick)
        UtxoMetaUtil.blockedChanges.removeObserver(blockedMarksObserver)
        prefsUtil.removeListener(prefsListener)
        DojoReachability.unsubscribe(reachabilityListener)
        super.onCleared()
    }

    /**
     * Home came back with a standing transport failure and Tor ready.
     * One quiet round. [QuietResync] refuses a round already in flight,
     * and this is not called from the failure post, so it cannot loop.
     */
    fun onHomeResumed() {
        maybeQuietResync()
    }

    private var lastReachability: DojoContact = DojoContact.Unknown

    private val reachabilityListener: (DojoContact) -> Unit = { next ->
        val previous = lastReachability
        lastReachability = next
        reachabilityPoke.postValue(next)
        if (becameReachable(previous, next)) {
            viewModelScope.launch { maybeQuietResync() }
        }
    }

    private val reachabilityPoke = MutableLiveData(DojoReachability.current)

    init {
        // After the listener exists. The first callback replays Unknown.
        DojoReachability.subscribe(reachabilityListener)
    }

    private fun maybeQuietResync() {
        val torReady = !SentinelState.isTorRequired() || SentinelTorManager.isTorReady()
        if (!QuietResync.shouldStart(syncState.value, torReady, syncRound?.isActive == true)) return
        fetchBalance(userInitiated = false)
    }

    private fun load() {
        viewModelScope.launch {
            try {
                repository.read()
            } catch (e: Exception) {
                Timber.e(e)
                // Do NOT report this as a generic network error; it means the
                // on-disk payload could not be decoded.
                syncState.postValue(
                    SyncState.Failed(
                        reason = "Could not load your collections.",
                        retryable = true
                    )
                )
            }
        }
    }

    /**
     * Reflects Tor bootstrap progress into the sync state so the indicator can
     * show real progress rather than an indefinite spinner.
     */
    private fun observeTor() {
        if (!SentinelState.isTorRequired()) return
        SentinelTorManager.getTorStateLiveData().observeForever { torState ->
            when (torState.state) {
                EnumTorState.STARTING -> {
                    // #175: bootstrap progress is only banner-worthy when
                    // something awaits it. An empty wallet with no endpoint
                    // stays quiet (device-witnessed: 3x WaitingForTor in
                    // exactly that regime, 2026-10-16 relaunch leg).
                    val workPending = repository.collectionsSnapshot().isNotEmpty() ||
                        prefsUtil.isAPIEndpointEnabled()
                    if (syncState.value !is SyncState.Syncing && workPending) {
                        syncState.postValue(SyncState.WaitingForTor(torState.progressIndicator))
                    }
                }
                EnumTorState.OFF -> {
                    if (syncState.value is SyncState.WaitingForTor) {
                        syncState.postValue(
                            SyncState.Failed("Tor is not connected.", retryable = true)
                        )
                    }
                }
                EnumTorState.ON -> {
                    // #135: re-arm rounds deferred during the climb. The
                    // readiness check must be the AWAIT poll, not a
                    // synchronous isTorReady(): the boot=100 STATE emission
                    // can reach the main thread before LISTENERS assigns the
                    // proxy (measured 1 ms apart, capture 1011 fresh-launch),
                    // and after boot=100 no further ON emissions ever come -
                    // a lost race would defer sync forever (observed: no
                    // syncCollections round began; sync only ran via the
                    // 18:08:57 resume trigger). awaitTorReady polls the
                    // volatile proxy field directly, so ordering cannot lose.
                    // Concurrent ON emissions collapse in launchSyncRound's
                    // coalescing + signature gates.
                    // viewModelScope: a bare launch here resolved to the
                    // deprecated scope-less CoroutineContext launch and
                    // failed the compile gate (HomeViewModel.kt:218).
                    viewModelScope.launch {
                        if (SentinelTorManager.awaitTorReady()) {
                            launchSyncRound(ArrayList(repository.collectionsSnapshot()))
                        }
                    }
                }
                EnumTorState.STOPPING -> Unit
            }
        }
    }

    fun getCollections(): LiveData<ArrayList<PubKeyCollection>> {
        val collectionsLiveData = repository.collectionsLiveData
        val resultLiveData = MediatorLiveData<ArrayList<PubKeyCollection>>()

        resultLiveData.addSource(collectionsLiveData) { collections ->
            // Publish cached collections IMMEDIATELY so the user sees their
            // wallets while any network work happens in the background.
            resultLiveData.value = collections
            // Header total is the same cached sum the rows already show.
            // updateBalance() otherwise waits for the first fetchUTXOS.
            updateBalance()

            // #175/#174: an empty wallet with no endpoint configured has
            // no work that needs Tor - no WaitingForTor banner, no round,
            // and no empty-round Success painting the indicator green.
            // A Failed the user earned (pull-to-refresh with nothing
            // configured) stands.
            if (collections.isEmpty() && !prefsUtil.isAPIEndpointEnabled()) {
                if (syncState.value !is SyncState.Failed) {
                    syncState.postValue(SyncState.Idle)
                }
                SentinelState.hasAppJustStarted = false
                return@addSource
            }

            // #135: full readiness, not the boot=5 ON emission. When not yet
            // ready the round is deferred and observeTor() re-arms it the
            // moment boot=100 AND the SOCKS listener are both published.
            val torReady = !SentinelState.isTorRequired() ||
                SentinelTorManager.isTorReady()

            if (!torReady) {
                // Previously the loading counter was incremented here even though
                // no fetch was started, which is what left the spinner running
                // forever on first launch after PIN entry.
                syncState.postValue(
                    SyncState.WaitingForTor(SentinelTorManager.getTorState().progressIndicator)
                )
                SentinelState.hasAppJustStarted = false
                return@addSource
            }

            // The round's own writes (per-pub balance, lastRefreshed)
            // are posted from the disk writer and often delivered here
            // after the round has ended and cleared lastRoundSignature.
            // Same ids and pubs are not an external arm. A real list
            // change still differs from roundStartSignature (#47).
            // Tor re-ready calls launchSyncRound directly, and
            // pull-to-refresh / Retry call fetchBalance.
            if (SyncRerunPolicy.isOwnListEmission(
                    blockOwnListEmission,
                    roundStartSignature,
                    collections,
                )
            ) {
                Timber.i("sync round skipped: own emission, list unchanged since round start")
                return@addSource
            }

            // MUST be Dispatchers.IO: fetchUTXOS/fetchFromServer touch Room, and
            // viewModelScope defaults to Dispatchers.Main, which makes Room throw
            // "Cannot access database on the main thread".
            launchSyncRound(collections)
        }
        return resultLiveData
    }

    /**
     * Fetches UTXOs for each collection, reporting determinate progress.
     *
     * There is deliberately NO timeout on the network work. Tor circuits can take
     * minutes to build on a poor connection, and cancelling a healthy-but-slow
     * sync produced a false "timed out" error. Instead a watchdog flips the state
     * to `Syncing(slow = true)` so the UI can explain the delay while the request
     * keeps running. The `finally` block still guarantees a terminal state.
     */
    private suspend fun syncCollections(
        collections: ArrayList<PubKeyCollection>,
        round: Job?
    ) = coroutineScope {
        // #429 row state is the only network match, and it is symmetric:
        // testnet collections drop out of a mainnet round, mainnet
        // collections drop out of a testnet round. Read at execute
        // time, same prefs the row bind uses.
        val inRound = collectionsForSyncRound(
            collections,
            activeIsTestnet = prefsUtil.testnet == true,
            dojoEnabled = SentinelState.isDojoEnabled(),
        )
        if (inRound.isEmpty()) {
            // #174: "nothing to sync" is a success only when a server
            // exists to have synced against. Unconfigured endpoint -> Idle;
            // Success here painted the indicator GREEN on a fresh install
            // with nothing configured (device-witnessed 2026-10-16).
            // A wallet whose every collection is off-network takes the
            // same ending: a no-op, never a failure. Off-network rows
            // are not attempted, so their stored data stays put.
            // Execute-time truth for the admit/execute race (endpoint
            // removed mid-round, #85 ground).
            if (prefsUtil.isAPIEndpointEnabled()) {
                syncState.postValue(SyncState.Success(System.currentTimeMillis()))
            } else if (syncState.value !is SyncState.Failed) {
                syncState.postValue(SyncState.Idle)
            }
            updateBalance()
            SentinelState.hasAppJustStarted = false
            return@coroutineScope
        }
        if (inRound.size != collections.size) {
            Timber.i(
                "sync round: skipping ${collections.size - inRound.size} off-network collections"
            )
        }

        var completed = 0
        // #475: per-round failure identity and proven-empty proof.
        // Indeterminate, a thrown fetch, and a completion-gate miss
        // all count. The terminal decision is shared with fetchBalance.
        val ledger = SyncRoundLedger()
        // Tracked locally because postValue() is asynchronous - reading
        // syncState.value in the finally block would still see "Syncing" and
        // clobber the real terminal state.
        var resolved = false
        syncState.postValue(SyncState.Syncing(done = 0, total = inRound.size))

        // Informational only: annotates the banner, never cancels the sync.
        val slowWatchdog = launch {
            delay(SLOW_SYNC_NOTICE_MS)
            syncState.postValue(
                SyncState.Syncing(done = completed, total = inRound.size, slow = true)
            )
        }

        try {
            var dojoDown = false
            inRound.forEach { collection ->
                if (dojoDown) {
                    // The onion already failed. Another wallet call would
                    // wait out the same connect. Count it and hide its coins.
                    markDojoDown(collection, ledger)
                } else try {
                    // #475: fetchFromServer, not fetchUTXOS - the auto
                    // round must land history, not just spendable coin.
                    // The /wallet body already carries the newest tx page
                    // and fetchUTXOS discarded it, which is the
                    // imported-backup repro's root cause. One round covers
                    // balances, fees, utxos and txs.
                    val result = transactionsRepository.fetchFromServer(collection.id)
                    ledger.record(collection.id, result)
                    if (result is CollectionTxSyncResult.Indeterminate) {
                        Timber.e(
                            "sync round: tx history indeterminate for ${collection.id}"
                        )
                        if (result.kind == SyncFailureKind.TRANSPORT) dojoDown = true
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ledger.recordThrown(collection.id, e)
                    if (syncFailureKind(e) == SyncFailureKind.TRANSPORT) dojoDown = true
                    Timber.e(e, "Failed to sync collection ${collection.id}")
                }
                completed++
                syncState.postValue(
                    SyncState.Syncing(
                        done = completed,
                        total = inRound.size,
                        slow = !slowWatchdog.isActive
                    )
                )
                updateBalance()
            }

            // #475 completion gate: no Success until every round-scoped
            // collection owns rows in the tx table, a proven-empty round,
            // or a counted failure (#226 emptyReconcileAllowed shape - a
            // genuinely empty watch-only wallet still goes green). Gate
            // misses degrade into the existing partial-Failed arm, so
            // silent drops (saveTx's findById null-return) become loud
            // incompletes instead of lying Successes. Off-network
            // collections are not in [inRound], so a missing history
            // there is not a miss.
            gateTxHistory(inRound, ledger)

            updateBalance()

            // #287/#499: launchSyncRound stamps lastRoundSignature before
            // the fetch. Any failure clears it so the next external arm
            // is not signature-skipped. The trailing re-run stays gated
            // on roundStartSignature (#490) and is not armed here.
            val decision = SyncRoundTerminal.decide(
                kind = SyncRoundTerminal.Kind.COLLECTIONS,
                failed = ledger.failed,
                total = inRound.size,
                endpointEnabled = prefsUtil.isAPIEndpointEnabled(),
                userInitiated = false,
                autoRetryBudget = autoRetryBudget,
                autoRetryLimit = AUTO_SYNC_RETRIES,
                standingFailed = syncState.value is SyncState.Failed,
                tally = ledger.tally(),
            )
            // Own writes must not use a cleared signature as a fresh arm.
            if (decision.clearLastRoundSignature) {
                lastRoundSignature = null
                blockOwnListEmission = true
            }
            decision.staleSuccessRetired?.let { staleSuccessRetired = it }
            val posted = decision.toSyncState(System.currentTimeMillis())
            check(posted != null) { "syncCollections does not auto-retry" }
            syncState.postValue(posted)
            resolved = true
        } finally {
            Timber.i("sync round end: syncCollections")
            slowWatchdog.cancel()
            // Guaranteed to run, so the UI can never be left mid-sync.
            SentinelState.hasAppJustStarted = false
            // #132: only the live round posts the terminal fallback - a
            // superseded round's Failed flashed over its successor's
            // Syncing ("Sync interrupted." on routine refresh).
            // round == null: caller had no identity - today's behavior.
            if (!resolved && (round == null || syncRound === round)) {
                // #287: an interrupted live round must not keep the
                // pre-stamped signature either. A superseded round
                // (syncRound !== round) does not touch it. The block
                // matches the clear: this round's writes are not the
                // retry.
                blockOwnListEmission = true
                lastRoundSignature = null
                syncState.postValue(SyncState.Failed("Sync interrupted.", retryable = true))
            }
        }
    }

    /**
     * #475: the round completion gate. A collection passes when the tx
     * table holds its rows, the round proved it empty, or its failure
     * was already counted. Collections that pass none of the three are
     * counted on [ledger] so the partial-Failed arm reports an honest
     * incomplete instead of a Success over a history that was never fetched.
     */
    private suspend fun gateTxHistory(
        collections: List<PubKeyCollection>,
        ledger: SyncRoundLedger,
    ) {
        val counts = HashMap<String, Int>(collections.size)
        collections.forEach { collection ->
            counts[collection.id] = transactionsRepository.countTxForCollection(collection.id)
        }
        ledger.applyGate(collections.map { it.id }, counts).forEach { id ->
            Timber.e("sync gate: no tx history for $id after a clean round")
        }
    }

    /**
     * Trailing re-run with id-diff discipline: the finished round already
     * synced exactly these collections if the id sets match, so its own
     * emissions are discarded. Only a genuinely changed list re-arms (#47).
     *
     * #489: the unchanged-list gate lives HERE now, on the round's START
     * signature - not on lastRoundSignature, which a failed round nulls
     * (#287, so external arms can still retry) and which therefore could
     * never stop this re-run while the round's own emission storm had it
     * armed. A null baseline fails open: fresh boot, or a round that
     * deferred before stamping (Tor climb, payload not yet loaded).
     */
    private fun maybeTrailingRerun() {
        val current = repository.collectionsLiveData.value ?: return
        if (!SyncRerunPolicy.shouldRerun(roundStartSignature, current)) {
            Timber.i("sync re-run skipped: list unchanged since round start")
            return
        }
        launchSyncRound(current)
    }

    fun getBalance(): LiveData<Long> {
        return balance
    }

    /** #301: frozen (do-not-spend) sum for the Home total's caption. */
    fun getFrozenBalance(): LiveData<Long> {
        return frozen
    }

    /**
     * #184: ONE shared mediator for the process. Recreated per call it could
     * never gain a source after the fact - a mid-session Tor toggle left
     * the dot blind to Tor for the rest of the process (the source set was
     * frozen at first menu preparation, and only if Tor was already
     * required then).
     */
    private val connectionIndicatorMediator: MediatorLiveData<ConnectionIndicator> by lazy {
        MediatorLiveData<ConnectionIndicator>().also { mediator ->
            val recompute: (TorState?) -> Unit = { tor ->
                mediator.value = computeIndicator(tor)
            }
            mediator.addSource(syncState) { recompute(null) }
            // Attached UNCONDITIONALLY: computeIndicator gates every Tor arm
            // on a fresh isTorRequired() read, so a mid-session toggle is
            // tracked without any re-wiring.
            mediator.addSource(SentinelTorManager.getTorStateLiveData()) { tor ->
                recompute(tor)
            }
            // #184: pairing/unpairing and the Tor toggle arrive as pokes;
            // recompute reads the prefs fresh at dispatch time.
            mediator.addSource(endpointPoke) { recompute(null) }
            mediator.addSource(reachabilityPoke) { recompute(null) }
        }
    }

    fun connectionIndicator(): LiveData<ConnectionIndicator> = connectionIndicatorMediator

    /**
     * Derives the toolbar traffic-light from Tor state + sync state +
     * endpoint configuration. The precedence lives in
     * [homeConnectionIndicator]; the controller's dedupe is untouched.
     *
     * The connection color is [DojoReachability]. A local sync failure
     * leaves the dot green once the Dojo has answered; the banner
     * keeps the sync result.
     */
    private fun computeIndicator(torEmission: TorState?): ConnectionIndicator {
        val torRequired = SentinelState.isTorRequired()
        // #99: the climb emission carries the authoritative snapshot (it
        // includes progressIndicator); the syncState and poke sources fall
        // back to a fresh manager read at recompute time.
        val tor = torEmission ?: SentinelTorManager.getTorState()
        val sync = syncState.value ?: SyncState.Idle
        return homeConnectionIndicator(
            sync = sync,
            torRequired = torRequired,
            torState = tor.state,
            torProgress = tor.progressIndicator,
            endpointEnabled = prefsUtil.isAPIEndpointEnabled(),
            reachability = DojoReachability.current,
        )
    }

    /** Remaining collections after the Dojo onion failed this round. */
    private fun markDojoDown(collection: PubKeyCollection, ledger: SyncRoundLedger) {
        ledger.recordKind(collection.id, SyncFailureKind.TRANSPORT)
        collection.pubs.forEach { pub ->
            PubSyncHealth.markFailed(pub.pubKey, SyncFailureKind.TRANSPORT)
        }
    }

    fun fetchBalance(userInitiated: Boolean = false) {
        if (prefsUtil.apiEndPoint == null) {
            if (userInitiated) {
                // A human pull-to-refresh keeps the loud, honest red -
                // the one correction path on an unconfigured wallet (#174
                // device leg, 2026-10-16).
                syncState.postValue(
                    SyncState.Failed("No server configured.", retryable = false)
                )
            } else if (syncState.value !is SyncState.Failed) {
                // #175: auto triggers (startup one-shot, auto-retry
                // relaunch) never shout about a state the user did not
                // ask about.
                syncState.postValue(SyncState.Idle)
            }
            return
        }

        if (userInitiated) {
            if (netWorkJobs.isNotEmpty()) {
                netWorkJobs.forEach { it?.cancel() }
                netWorkJobs.clear()
            }

            // A human refresh REPLACES any in-flight round (#47).
            syncRound?.cancel()
            syncRound = null
            syncRerunPending = false
            autoRetryBudget = 0
        } else if (syncRound?.isActive == true) {
            // Auto triggers never stack or replace: the in-flight round is
            // already fetching this data. Robust to any number of auto
            // trigger sources (#47).
            Timber.i("sync round skipped: auto fetchBalance while round in flight")
            return
        }

        val collections = ArrayList(repository.collectionsSnapshot())

        // MUST be Dispatchers.IO: fetchFromServer writes to Room.
        syncRound = viewModelScope.launch(Dispatchers.IO) {
            val self = coroutineContext[Job]
            Timber.i("sync round begin: fetchBalance userInitiated=$userInitiated collections=${collections.size}")
            // #135: no fetch (rates included) before full readiness - see
            // launchSyncRound. Deferred rounds are re-armed by observeTor().
            if (SentinelState.isTorRequired() &&
                !SentinelTorManager.awaitTorReady(TOR_READY_TIMEOUT_MS)
            ) {
                lastRoundSignature = null
                // Same as launchSyncRound: a deferral is recovered by
                // the next list emission or by observeTor.
                blockOwnListEmission = false
                syncState.postValue(
                    SyncState.WaitingForTor(SentinelTorManager.getTorState().progressIndicator)
                )
                return@launch
            }
            try {
                exchangeRateRepository.fetch()
            } catch (e: Exception) {
                Timber.e(e)
            }

            if (collections.isEmpty()) {
                // #475: the snapshot is empty because repository.read()
                // has not landed yet (#289), not because the wallet is
                // empty. The old Success here was the second lying
                // completion over an unread payload - the post-import
                // repro's exact shape. Defer instead: read()'s
                // collectionsLiveData emission re-arms the real round
                // through getCollections().
                if (!repository.isPayloadLoaded()) {
                    Timber.i("sync round deferred: payload not yet loaded")
                    return@launch
                }
                // #188: the head guard checked the endpoint at admit;
                // re-check at post time - it can vanish mid-round.
                if (prefsUtil.isAPIEndpointEnabled()) {
                    syncState.postValue(SyncState.Success(System.currentTimeMillis()))
                } else if (syncState.value !is SyncState.Failed) {
                    staleSuccessRetired = true
                    syncState.postValue(SyncState.Idle)
                }
                return@launch
            }

            // Signature stays the full list. The active network is not
            // part of it: a network switch relaunches the activity, and
            // an endpoint write arms fetchBalance without this gate.
            // What the round attempts is a separate decision.
            val inRound = collectionsForSyncRound(
                collections,
                activeIsTestnet = prefsUtil.testnet == true,
                dojoEnabled = SentinelState.isDojoEnabled(),
            )
            if (inRound.size != collections.size) {
                Timber.i(
                    "sync round: skipping ${collections.size - inRound.size} off-network collections"
                )
            }

            lastRoundSignature = SyncRerunPolicy.signatureOf(collections)
            // #489: baseline for the trailing re-run decision.
            roundStartSignature = lastRoundSignature
            blockOwnListEmission = false
            var completed = 0
            // #475: same outcome accounting as the auto round.
            // Only [inRound] is recorded, gated, or passed as M.
            val ledger = SyncRoundLedger()
            var resolved = false
            // No on-network collection: do not flash Syncing. The
            // terminal below sees failed=0 and total=0, which is a
            // success (or Idle), never a failure.
            val slowWatchdog: Job? = if (inRound.isEmpty()) {
                null
            } else {
                syncState.postValue(SyncState.Syncing(done = 0, total = inRound.size))
                // Informational only - no timeout, since Tor can legitimately be slow.
                launch {
                    delay(SLOW_SYNC_NOTICE_MS)
                    syncState.postValue(
                        SyncState.Syncing(done = completed, total = inRound.size, slow = true)
                    )
                }
            }

            try {
                var dojoDown = false
                inRound.forEach { collection ->
                    if (dojoDown) {
                        markDojoDown(collection, ledger)
                    } else try {
                        // #475: same outcome accounting as the auto
                        // round - feeds the completion gate below.
                        val result = transactionsRepository.fetchFromServer(collection)
                        ledger.record(collection.id, result)
                        if (result is CollectionTxSyncResult.Indeterminate) {
                            Timber.e(
                                "sync round: tx history indeterminate for ${collection.id}"
                            )
                            if (result.kind == SyncFailureKind.TRANSPORT) dojoDown = true
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ledger.recordThrown(collection.id, e)
                        if (syncFailureKind(e) == SyncFailureKind.TRANSPORT) dojoDown = true
                        Timber.e(e)
                    }
                    completed++
                    syncState.postValue(
                        SyncState.Syncing(
                            done = completed,
                            total = inRound.size,
                            slow = slowWatchdog?.isActive == false
                        )
                    )
                }

                // #475 completion gate - see gateTxHistory. Off-network
                // ids are not in [inRound], so they cannot miss the gate.
                gateTxHistory(inRound, ledger)

                try {
                    feeRepository.getDynamicFees()
                } catch (e: Exception) {
                    Timber.e(e)
                }

                updateBalance()

                // #499: same terminal discipline as syncCollections.
                // Partial failure is Failed, not Success, and clears the
                // external-arm signature. Total failure keeps the bounded
                // retry below. roundStartSignature is left stamped so the
                // #490 trailing re-run does not loop.
                val decision = SyncRoundTerminal.decide(
                    kind = SyncRoundTerminal.Kind.BALANCE,
                    failed = ledger.failed,
                    total = inRound.size,
                    endpointEnabled = prefsUtil.isAPIEndpointEnabled(),
                    userInitiated = userInitiated,
                    autoRetryBudget = autoRetryBudget,
                    autoRetryLimit = AUTO_SYNC_RETRIES,
                    standingFailed = syncState.value is SyncState.Failed,
                    tally = ledger.tally(),
                )
                autoRetryBudget = decision.autoRetryBudget
                if (decision.clearLastRoundSignature) {
                    lastRoundSignature = null
                    blockOwnListEmission = true
                }
                decision.staleSuccessRetired?.let { staleSuccessRetired = it }
                if (decision.armAutoRetry) {
                    // Tor state ON does not mean the SOCKS circuit is
                    // usable yet. Stay in Syncing (no Failed flash) and
                    // retry shortly instead of churning rounds (#47).
                    autoRetryPending = true
                    Timber.i(
                        "sync auto-retry armed: $autoRetryBudget/$AUTO_SYNC_RETRIES"
                    )
                } else {
                    if (decision.writeLastSynced) {
                        prefsUtil.lastSynced = System.currentTimeMillis()
                    }
                    decision.toSyncState(System.currentTimeMillis())?.let {
                        syncState.postValue(it)
                    }
                }
                resolved = true
            } finally {
                val roundIsSelf = syncRound === self
                if (roundIsSelf) {
                    syncRound = null
                    if (autoRetryPending) {
                        autoRetryPending = false
                        syncRerunPending = false
                        // Give the circuit a moment; the retry re-fetches
                        // everything, so a pending re-run is subsumed.
                        delay(AUTO_RETRY_DELAY_MS)
                        Timber.i("sync round end: fetchBalance, auto-retry relaunching")
                        fetchBalance(userInitiated = false)
                    } else if (syncRerunPending) {
                        syncRerunPending = false
                        maybeTrailingRerun()
                    } else {
                        Timber.i("sync round end: fetchBalance")
                    }
                } else {
                    Timber.i("sync round end: fetchBalance superseded, cleanup skipped")
                }
                slowWatchdog?.cancel()
                // postValue() is async, so syncState.value cannot be trusted here.
                // #132: only the live round posts the terminal fallback - a
                // superseded round's Failed flashed over its successor's
                // Syncing ("Sync interrupted." on routine refresh).
                if (!resolved && roundIsSelf) {
                    syncState.postValue(SyncState.Failed("Sync interrupted.", retryable = true))
                }
            }
        }
    }

    fun getErrorMessage(): LiveData<String> {
        return errorMessage
    }

    // One shared instance, so callers reading .value see what observers see.
    private val fiatBalance: MediatorLiveData<String> by lazy {
        MediatorLiveData<String>().apply {
            addSource(exchangeRateRepository.getRateLive()) {
                value = getFiatBalance(balance.value, it)
            }
            addSource(balance) {
                value = getFiatBalance(it, exchangeRateRepository.getRateLive().value)
            }
        }
    }

    fun getFiatBalance(): LiveData<String> = fiatBalance

    private fun getFiatBalance(balance: Long?, rate: ExchangeRateRepository.Rate?): String {
        // Missing rate is blank. Callers hide the line instead of painting a fake amount.
        if (prefsUtil.fiatDisabled == true) return ""
        if (rate == null || rate.rate <= 0L) return ""
        balance ?: return ""
        return try {
            val fiatRate = MonetaryUtil.getInstance().getFiatFormat(prefsUtil.selectedCurrency)
                .format((balance / 1e8) * rate.rate)
            "$fiatRate ${rate.currency}"
        } catch (e: Exception) {
            ""
        }
    }


    fun getBTCDisplayAmount(value: Long): String? {
        return Coin.valueOf(value).toPlainString()
    }


    private fun updateBalance() {
        val collections = repository.collectionsLiveData.value ?: return

        try {
            // #301: one derivation for every surface (SpendableBalance.kt);
            // this inline subtraction was the stale-until-sync half.
            // #429: the active-network filter sits INSIDE the derivation
            // (decision 1) - off-network collections leave the header
            // total while their rows keep cached balances, greyed.
            // The pref is read directly, matching the adapter bind
            // truth: SentinelState lags a flip by one pref write
            // (listeners fire before the value lands).
            val figures = figuresForActiveNetwork(collections, prefsUtil.testnet == true)
            balance.postValue(figures.spendable)
            frozen.postValue(figures.frozen)
        } catch (e: Exception) {
            Timber.e(e)
        }
    }
}
