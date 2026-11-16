package com.samourai.sentinel.ui.webview

import com.samourai.sentinel.tor.EnumTorState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

/**
 * One Network-screen explorer check.
 *
 * The Tor SOCKS generation is part of the attempt. A probe that started
 * against a previous session (or before Tor was ready) is cancelled when
 * the live port is published, and a new one runs. Refreshes while that
 * same session is already in flight do not start another check. A tap
 * while the row is checking, unconfirmed, or unreachable does.
 *
 * The whole attempt is bounded by [PROBE_TIMEOUT_MS]. A timeout or any
 * other non-answer is [ExplorerProbeOutcome.UNCONFIRMED]. A refusal, a
 * SOCKS host-unreachable, or a non-2xx on every path is
 * [ExplorerProbeOutcome.UNREACHABLE]. Leaving the screen, or a Tor
 * session change, does not store the attempt that was in flight.
 *
 * While the screen is started and the row is not green, one retry waits
 * [AUTO_RETRY_DELAYS_MS] (10s, then 30s) and then stops. A probe that is
 * already running is left alone. A new SOCKS generation clears the count.
 */
class ExplorerProbeCoordinator(
    private val scope: CoroutineScope,
    private val repository: ExplorerRepository,
    private val probe: suspend (baseUrl: String, endpoint: String) -> ProbeResult,
    private val onChanged: () -> Unit = {},
    private val timeoutMs: Long = PROBE_TIMEOUT_MS,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val elapsedNow: () -> Long = { System.nanoTime() },
) {
    var outcome: ExplorerProbeOutcome = ExplorerProbeOutcome.PENDING
        private set

    private var screenVisible = false
    private var autoRetries = 0
    private var session: Session? = null
    private var attempt: Attempt? = null
    private var inFlight: Flight? = null
    private var job: Job? = null
    private var retryJob: Job? = null
    private var generation: Int = 0
    private var checkStartedAtMs: Long? = null

    /** A visit. The retry count starts over, and [refresh] may open a socket. */
    fun onScreenStart() {
        screenVisible = true
        autoRetries = 0
        cancelRetry()
    }

    /** The screen is gone. Drop the socket and the waiting retry. */
    fun onScreenStop() {
        screenVisible = false
        checkStartedAtMs = null
        cancel()
    }

    /** Milliseconds since this check started. Zero when the row is not checking. */
    fun checkingElapsedMs(): Long {
        if (outcome != ExplorerProbeOutcome.PENDING) return 0L
        val started = checkStartedAtMs ?: return 0L
        return (now() - started).coerceAtLeast(0L)
    }

    fun refresh(request: ExplorerProbeRequest) {
        val resolution = request.resolution
        val base = ExplorerBinding.probeBase(resolution)
        val key = explorerProbeKey(resolution)
        val ready = request.ready
        val endpoint = request.socksEndpoint?.takeIf { it.isNotBlank() }
        val sessionNow = if (ready && endpoint != null) {
            Session(request.socksGeneration, endpoint)
        } else {
            null
        }
        val previous = session
        // The first look at an already-running Tor session is not a
        // change. Dropping the cached check here would flash Checking…
        // every time the screen opened. A later generation or port is.
        val torChanged = previous != null && previous != sessionNow
        if (torChanged) {
            autoRetries = 0
            cancelRetry()
            inFlight?.let { flight ->
                ExplorerProbeLog.abort(
                    flight.base,
                    if (sessionNow == null) "tor-not-ready" else "socks-changed",
                    endpoint ?: "none",
                )
            }
            cancelInFlight()
            repository.forgetProbe()
        } else if (previous == null && sessionNow != null && cacheBelongsToOtherSession(sessionNow)) {
            repository.forgetProbe()
        }
        session = sessionNow
        if (!ready || key == null || base == null || endpoint == null) {
            attempt = null
            if (key == null || base == null) cancelInFlight()
            outcome = if (
                resolution is ExplorerBinding.ExplorerResolution.Bound &&
                ready &&
                base == null
            ) {
                ExplorerProbeOutcome.UNREACHABLE
            } else {
                ExplorerProbeOutcome.PENDING
            }
            if (outcome != ExplorerProbeOutcome.PENDING) checkStartedAtMs = null
            return
        }
        attempt = Attempt(key, base, endpoint)
        if (!screenVisible) {
            paintRemembered(key)
            return
        }
        if (torChanged) {
            markChecking()
            start(key, base, endpoint, logMiss = true)
            return
        }
        val record = repository.rememberedProbe()
        val decision = ExplorerProbeMemory.decide(record, key, now())
        when (decision.plan) {
            ExplorerProbeMemory.Plan.USE_FRESH -> {
                if (inFlight != null) cancelInFlight()
                outcome = decision.outcome
                checkStartedAtMs = null
                val age = if (record != null && record.key == key) now() - record.atMillis else 0L
                ExplorerProbeLog.cacheHit(base, decision.outcome.name, age)
            }
            ExplorerProbeMemory.Plan.REPROBE_KEEPING_LAST -> {
                outcome = decision.outcome
                checkStartedAtMs = null
                startIfIdle(key, base, endpoint)
            }
            ExplorerProbeMemory.Plan.PROBE_UNKNOWN -> {
                val flight = inFlight
                if (flight != null && flight.key == key && flight.endpoint == endpoint) return
                markChecking()
                startIfIdle(key, base, endpoint)
            }
        }
        considerAutoRetry()
    }

    /**
     * Tap on the row. Starts one new attempt when the row is checking,
     * unconfirmed, or unreachable. A tap in any other state does nothing,
     * and a refresh does not call this.
     */
    fun retry(request: ExplorerProbeRequest) {
        val health = health(request).health
        if (!explorerRowRetryable(health)) return
        if (!request.ready) return
        val key = explorerProbeKey(request.resolution) ?: return
        val base = ExplorerBinding.probeBase(request.resolution) ?: return
        val endpoint = request.socksEndpoint?.takeIf { it.isNotBlank() } ?: return
        repository.forgetProbe()
        markChecking(resetClock = true)
        session = Session(request.socksGeneration, endpoint)
        attempt = Attempt(key, base, endpoint)
        cancelRetry()
        cancelInFlight()
        start(key, base, endpoint, logMiss = true)
        onChanged()
    }

    fun cancel() {
        cancelRetry()
        cancelInFlight()
    }

    fun health(request: ExplorerProbeRequest): ExplorerHealthState {
        val torReady = request.ready
        val inProgress = request.torState == EnumTorState.STARTING ||
            (request.torState == EnumTorState.ON && !torReady)
        return explorerHealth(
            resolution = request.resolution,
            torReady = torReady,
            torInProgress = inProgress,
            proxyAvailable = !request.socksEndpoint.isNullOrBlank(),
            probe = outcome,
        )
    }

    private fun paintRemembered(key: String) {
        val decision = ExplorerProbeMemory.decide(repository.rememberedProbe(), key, now())
        when (decision.plan) {
            ExplorerProbeMemory.Plan.USE_FRESH,
            ExplorerProbeMemory.Plan.REPROBE_KEEPING_LAST -> {
                outcome = decision.outcome
                checkStartedAtMs = null
            }
            ExplorerProbeMemory.Plan.PROBE_UNKNOWN -> markChecking()
        }
    }

    private fun markChecking(resetClock: Boolean = false) {
        if (resetClock || outcome != ExplorerProbeOutcome.PENDING || checkStartedAtMs == null) {
            checkStartedAtMs = now()
        }
        outcome = ExplorerProbeOutcome.PENDING
    }

    private fun startIfIdle(key: String, base: String, endpoint: String) {
        val flight = inFlight
        if (flight != null && flight.key == key && flight.endpoint == endpoint) return
        cancelRetry()
        cancelInFlight()
        start(key, base, endpoint, logMiss = true)
    }

    private fun start(
        key: String,
        base: String,
        endpoint: String,
        logMiss: Boolean,
        fromRetry: Boolean = false,
    ) {
        if (!fromRetry) cancelRetry()
        val gen = generation
        val socksGeneration = session?.generation ?: ExplorerProbeMemory.NO_SESSION
        inFlight = Flight(key, base, endpoint)
        checkStartedAtMs = now()
        if (logMiss) ExplorerProbeLog.cacheMiss(base)
        val startedNs = elapsedNow()
        job = scope.launch {
            val result = try {
                withTimeout(timeoutMs) { probe(base, endpoint) }
            } catch (t: TimeoutCancellationException) {
                // Fixed token. The runtime name follows R8's rename of this
                // kotlinx class, and the release line would say error.
                ProbeResult.unconfirmed(probeFailureLabel(t))
            } catch (t: CancellationException) {
                throw t
            }
            if (gen != generation) return@launch
            inFlight = null
            job = null
            val stored = store(result, key, socksGeneration, endpoint)
            val elapsed = elapsedMs(startedNs)
            if (stored) {
                ExplorerProbeLog.result(base, outcome.name, result.detail, elapsed)
            }
            ExplorerProbeLog.done(base, if (stored) outcome.name else result.kind.name, elapsed)
            if (stored) considerAutoRetry()
            onChanged()
        }
    }

    private fun store(
        result: ProbeResult,
        key: String,
        socksGeneration: Int,
        endpoint: String,
    ): Boolean {
        val stored = when (result.kind) {
            ProbeResult.Kind.REACHABLE -> ExplorerProbeOutcome.REACHABLE
            ProbeResult.Kind.UNREACHABLE -> ExplorerProbeOutcome.UNREACHABLE
            ProbeResult.Kind.UNCONFIRMED -> ExplorerProbeOutcome.UNCONFIRMED
            ProbeResult.Kind.NOT_READY -> null
        } ?: return false
        outcome = stored
        checkStartedAtMs = null
        repository.rememberProbe(key, stored, now(), socksGeneration, endpoint)
        return true
    }

    /**
     * After a non-green result, wait and try again. Does nothing while a
     * probe is running, while one wait is already queued, or after
     * [MAX_AUTO_RETRIES] tries this visit.
     */
    private fun considerAutoRetry() {
        if (!screenVisible) return
        if (outcome != ExplorerProbeOutcome.UNREACHABLE &&
            outcome != ExplorerProbeOutcome.UNCONFIRMED
        ) {
            if (outcome == ExplorerProbeOutcome.REACHABLE) cancelRetry()
            return
        }
        if (inFlight != null) return
        if (retryJob?.isActive == true) return
        if (autoRetries >= MAX_AUTO_RETRIES) return
        val delayMs = AUTO_RETRY_DELAYS_MS[autoRetries]
        retryJob = scope.launch {
            delay(delayMs)
            val mine = coroutineContext[Job]
            if (retryJob === mine) retryJob = null
            if (!isActive || !screenVisible || inFlight != null) return@launch
            if (autoRetries >= MAX_AUTO_RETRIES) return@launch
            if (outcome != ExplorerProbeOutcome.UNREACHABLE &&
                outcome != ExplorerProbeOutcome.UNCONFIRMED
            ) {
                return@launch
            }
            val current = attempt ?: return@launch
            autoRetries += 1
            repository.forgetProbe()
            markChecking(resetClock = true)
            // Null the job before start. An immediate dispatcher runs the
            // probe inside start and may schedule the next wait before
            // this coroutine returns. Cancelling here would cancel that
            // wait's caller, which is this coroutine.
            start(current.key, current.base, current.endpoint, logMiss = true, fromRetry = true)
            onChanged()
        }
    }

    private fun cancelRetry() {
        retryJob?.cancel()
        retryJob = null
    }

    private fun cancelInFlight() {
        generation += 1
        inFlight = null
        job?.cancel()
        job = null
    }

    private fun cacheBelongsToOtherSession(sessionNow: Session): Boolean {
        val cached = repository.rememberedProbe() ?: return false
        return cached.socksGeneration != sessionNow.generation ||
            cached.socksEndpoint != sessionNow.endpoint
    }

    private fun elapsedMs(startedNs: Long): Long =
        ((elapsedNow() - startedNs).coerceAtLeast(0L)) / 1_000_000L

    private data class Session(val generation: Int, val endpoint: String)

    private data class Attempt(val key: String, val base: String, val endpoint: String)

    private data class Flight(val key: String, val base: String, val endpoint: String)

    companion object {
        const val PROBE_TIMEOUT_MS = 60_000L
        const val MAX_AUTO_RETRIES = 2
        val AUTO_RETRY_DELAYS_MS = longArrayOf(10_000L, 30_000L)
    }
}

data class ExplorerProbeRequest(
    val resolution: ExplorerBinding.ExplorerResolution,
    val torState: EnumTorState,
    val torProgress: Int,
    val socksEndpoint: String?,
    val socksGeneration: Int,
) {
    val ready: Boolean
        get() = torState == EnumTorState.ON &&
            torProgress == 100 &&
            !socksEndpoint.isNullOrBlank()
}

internal fun explorerProbeKey(resolution: ExplorerBinding.ExplorerResolution): String? {
    val bound = resolution as? ExplorerBinding.ExplorerResolution.Bound ?: return null
    if (bound.baseUrl.isBlank()) return null
    return "${bound.kind}:${bound.baseUrl}"
}
