package com.samourai.sentinel.ui.webview

import com.samourai.sentinel.tor.EnumTorState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import timber.log.Timber
import java.io.File

/**
 * The row stays on Checking… when a probe captured a SOCKS port from
 * before Tor was ready and a later refresh treated that attempt as
 * still in flight. These cases drive the coordinator with a virtual
 * clock: no real sleep, no Tor daemon.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExplorerProbeCoordinatorTest {

    private val onionHost = "abcd" + "e".repeat(48) + "wxyz"
    private val base = "http://$onionHost.onion"
    private val resolution = ExplorerBinding.ExplorerResolution.Bound(
        kind = ExplorerBinding.ExplorerKind.PAIRED,
        baseUrl = base,
        displayUrl = base,
    )

    @Test
    fun `a coordinator timeout is amber and hides the operator line`() = runTest {
        val repo = ExplorerRepository()
        val lines = mutableListOf<String>()
        val tree = capturingTree(lines)
        Timber.plant(tree)
        val coord = coordinator(repo) { _, _ ->
            kotlinx.coroutines.suspendCancellableCoroutine { }
        }
        try {
            val request = ready(endpoint = "127.0.0.1:9050", generation = 1)
            coord.refresh(request)
            advanceTimeBy(ExplorerProbeCoordinator.PROBE_TIMEOUT_MS)
            runCurrent()
            assertEquals(ExplorerProbeOutcome.UNCONFIRMED, coord.outcome)
            assertEquals(ExplorerProbeOutcome.UNCONFIRMED, repo.rememberedProbe()?.outcome)
            val health = coord.health(request)
            assertEquals(ExplorerHealth.UNCONFIRMED, health.health)
            assertFalse(explorerOperatorVisible(health.health, "SG Directory Dojo"))
            assertEquals("Tap to retry", explorerDetailText(health.health, "SG Directory Dojo"))
            val kept = lines.map { it.substringAfter(" ") }.filter {
                releaseKeepsExplorerProbe(android.util.Log.INFO, ExplorerProbeLog.TAG, it, null)
            }
            assertEquals(1, kept.size)
            assertTrue(RELEASE_RESULT.matches(kept[0]))
            assertTrue(kept[0].startsWith("result outcome=UNCONFIRMED status=TimeoutCancellationException elapsedMs="))
            assertFalse(kept[0].contains("host="))
            assertFalse(kept[0].contains("onion"))
            assertFalse(kept[0].contains(onionHost))
            assertFalse(kept[0].contains("127.0.0.1"))
            assertTrue(lines.any { it.contains("done ") })
            assertTrue(kept.none { it.contains("done ") })
        } finally {
            Timber.uproot(tree)
            coord.cancel()
            advanceUntilIdle()
        }
    }

    @Test
    fun `success stores REACHABLE and the row is green with the operator`() = runTest {
        val repo = ExplorerRepository()
        var calls = 0
        val coord = coordinator(repo) { _, _ ->
            calls += 1
            ProbeResult.Reachable
        }
        coord.refresh(ready(endpoint = "127.0.0.1:9050", generation = 1))
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(ExplorerProbeOutcome.REACHABLE, coord.outcome)
        assertEquals(ExplorerProbeOutcome.REACHABLE, repo.rememberedProbe()?.outcome)
        val health = coord.health(ready(endpoint = "127.0.0.1:9050", generation = 1))
        assertEquals(ExplorerHealth.BOUND, health.health)
        assertTrue(explorerOperatorVisible(health.health, "SG Directory Dojo"))
        assertEquals("SG Directory Dojo", explorerDetailText(health.health, "SG Directory Dojo"))
        assertFalse(explorerOperatorVisible(ExplorerHealth.CHECKING, "SG Directory Dojo"))
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, calls)
    }

    @Test
    fun `refreshes while a probe is in flight do not start another`() = runTest {
        val calls = mutableListOf<String>()
        val gate = CompletableDeferred<ProbeResult>()
        val coord = coordinator(ExplorerRepository()) { _, endpoint ->
            calls += endpoint
            gate.await()
        }
        try {
            val request = ready(endpoint = "127.0.0.1:9050", generation = 1)
            coord.refresh(request)
            coord.refresh(request)
            coord.refresh(request)
            runCurrent()
            assertEquals(listOf("127.0.0.1:9050"), calls)
            gate.complete(ProbeResult.Reachable)
            runCurrent()
            assertEquals(ExplorerProbeOutcome.REACHABLE, coord.outcome)
        } finally {
            coord.cancel()
            advanceUntilIdle()
        }
    }

    @Test
    fun `a tap while checking starts one new probe and a tap while green does not`() = runTest {
        val calls = mutableListOf<Int>()
        var release = CompletableDeferred<ProbeResult>()
        val coord = coordinator(ExplorerRepository()) { _, _ ->
            calls += 1
            release.await()
        }
        try {
            val request = ready(endpoint = "127.0.0.1:9050", generation = 1)
            coord.refresh(request)
            runCurrent()
            assertEquals(1, calls.size)
            coord.retry(request)
            runCurrent()
            assertEquals(2, calls.size)
            release.complete(ProbeResult.Reachable)
            runCurrent()
            val afterGreen = calls.size
            coord.retry(request)
            runCurrent()
            assertEquals(afterGreen, calls.size)
            assertEquals(ExplorerHealth.BOUND, coord.health(request).health)
        } finally {
            if (!release.isCompleted) release.complete(ProbeResult.Unreachable)
            coord.cancel()
            advanceUntilIdle()
        }
    }

    @Test
    fun `probe started under a stale tor port re-runs when tor is ready and resolves`() = runTest {
        val calls = mutableListOf<String>()
        val gates = mutableMapOf<String, CompletableDeferred<ProbeResult>>()
        val lines = mutableListOf<String>()
        val tree = capturingTree(lines)
        Timber.plant(tree)
        val repo = ExplorerRepository()
        val coord = coordinator(repo) { _, endpoint ->
            calls += endpoint
            gates.getOrPut(endpoint) { CompletableDeferred() }.await()
        }
        try {
            // Stale session: boot looks complete and a SOCKS port is set,
            // but it is the previous daemon's port. The probe hangs on it.
            coord.refresh(ready(endpoint = "127.0.0.1:1111", generation = 1))
            runCurrent()
            assertEquals(listOf("127.0.0.1:1111"), calls)
            assertEquals(ExplorerProbeOutcome.PENDING, coord.outcome)
            assertNull(repo.rememberedProbe())

            // The live port is published. Same explorer, new Tor session.
            coord.refresh(ready(endpoint = "127.0.0.1:2222", generation = 2))
            runCurrent()
            assertEquals(listOf("127.0.0.1:1111", "127.0.0.1:2222"), calls)
            gates.getValue("127.0.0.1:2222").complete(ProbeResult.Reachable)
            runCurrent()

            assertEquals(ExplorerProbeOutcome.REACHABLE, coord.outcome)
            assertEquals(ExplorerHealth.BOUND, coord.health(ready("127.0.0.1:2222", 2)).health)
            assertTrue(lines.any { it.contains("abort") && it.contains("socks-changed") })
            assertTrue(lines.any { it.contains("done") && it.contains("outcome=REACHABLE") })
            assertFalse(lines.joinToString("\n").contains(onionHost))
        } finally {
            Timber.uproot(tree)
            coord.cancel()
            advanceUntilIdle()
        }
    }

    @Test
    fun `a probe does not start until tor is ready and then resolves`() = runTest {
        val calls = mutableListOf<String>()
        val gate = CompletableDeferred<ProbeResult>()
        val coord = coordinator(ExplorerRepository()) { _, endpoint ->
            calls += endpoint
            gate.await()
        }
        try {
            coord.refresh(notReady(progress = 40, endpoint = null, generation = 0))
            coord.refresh(notReady(progress = 100, endpoint = null, generation = 0))
            advanceUntilIdle()
            assertTrue(calls.isEmpty())
            assertEquals(
                ExplorerHealth.TOR_STARTING,
                coord.health(notReady(progress = 100, endpoint = null, generation = 0)).health,
            )

            coord.refresh(ready(endpoint = "127.0.0.1:2222", generation = 3))
            runCurrent()
            assertEquals(listOf("127.0.0.1:2222"), calls)
            gate.complete(ProbeResult.Reachable)
            runCurrent()
            assertEquals(ExplorerHealth.BOUND, coord.health(ready("127.0.0.1:2222", 3)).health)
        } finally {
            coord.cancel()
            advanceUntilIdle()
        }
    }

    @Test
    fun `unreachable can be retried from the row`() = runTest {
        var calls = 0
        val repo = ExplorerRepository()
        val coord = coordinator(repo) { _, _ ->
            calls += 1
            if (calls == 1) ProbeResult.unreachable("ConnectException") else ProbeResult.Reachable
        }
        val request = ready(endpoint = "127.0.0.1:9050", generation = 4)
        coord.refresh(request)
        runCurrent()
        assertEquals(ExplorerProbeOutcome.UNREACHABLE, coord.outcome)
        assertEquals(ExplorerHealth.UNREACHABLE, coord.health(request).health)
        assertFalse(explorerOperatorVisible(coord.health(request).health, "SG Directory Dojo"))
        assertEquals("Tap to retry", explorerDetailText(coord.health(request).health, "SG Directory Dojo"))
        coord.retry(request)
        runCurrent()
        assertEquals(2, calls)
        assertEquals(ExplorerHealth.BOUND, coord.health(request).health)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, calls)
    }

    @Test
    fun `timeout is amber, a refusal is red, and a 2xx is green`() = runTest {
        val timeout = finished { _, _ -> ProbeResult.unconfirmed("SocketTimeoutException") }
        assertEquals(ExplorerProbeOutcome.UNCONFIRMED, timeout.outcome)
        assertEquals(ExplorerHealth.UNCONFIRMED, timeout.health(ready("127.0.0.1:9050", 1)).health)
        assertEquals("Tap to retry", explorerDetailText(ExplorerHealth.UNCONFIRMED, "SG Directory Dojo"))

        val refused = finished { _, _ -> ProbeResult.unreachable("ConnectException") }
        assertEquals(ExplorerProbeOutcome.UNREACHABLE, refused.outcome)
        assertEquals(ExplorerHealth.UNREACHABLE, refused.health(ready("127.0.0.1:9050", 1)).health)

        val ok = finished { _, _ -> ProbeResult.reachable("200") }
        assertEquals(ExplorerProbeOutcome.REACHABLE, ok.outcome)
        assertEquals(ExplorerHealth.BOUND, ok.health(ready("127.0.0.1:9050", 1)).health)
        assertTrue(explorerOperatorVisible(ok.health(ready("127.0.0.1:9050", 1)).health, "SG Directory Dojo"))
    }

    @Test
    fun `a non-green row retries after 10s then 30s and then stops`() = runTest {
        var calls = 0
        val coord = coordinator { _, _ ->
            calls += 1
            ProbeResult.unreachable("ConnectException")
        }
        assertEquals(2, ExplorerProbeCoordinator.MAX_AUTO_RETRIES)
        assertEquals(10_000L, ExplorerProbeCoordinator.AUTO_RETRY_DELAYS_MS[0])
        assertEquals(30_000L, ExplorerProbeCoordinator.AUTO_RETRY_DELAYS_MS[1])
        coord.refresh(ready("127.0.0.1:9050", 1))
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(9_999)
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, calls)
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(2, calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, calls)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(3, calls)
    }

    @Test
    fun `an auto-retry does not start while a probe is running`() = runTest {
        val gate = CompletableDeferred<ProbeResult>()
        var calls = 0
        val coord = coordinator { _, _ ->
            calls += 1
            gate.await()
        }
        try {
            val request = ready("127.0.0.1:9050", 1)
            coord.refresh(request)
            coord.refresh(request)
            runCurrent()
            advanceTimeBy(40_000)
            runCurrent()
            coord.refresh(request)
            runCurrent()
            assertEquals(1, calls)
            assertEquals(ExplorerProbeOutcome.PENDING, coord.outcome)
        } finally {
            if (!gate.isCompleted) gate.complete(ProbeResult.Unreachable)
            coord.cancel()
            advanceUntilIdle()
        }
    }

    @Test
    fun `inline completion still schedules the next retry`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val eager = CoroutineScope(dispatcher)
        var calls = 0
        val coord = ExplorerProbeCoordinator(
            scope = eager,
            repository = ExplorerRepository(),
            probe = { _, _ ->
                calls += 1
                ProbeResult.unconfirmed("SocketTimeoutException")
            },
        )
        try {
            coord.onScreenStart()
            coord.refresh(ready("127.0.0.1:9050", 1))
            assertEquals(1, calls)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(2, calls)
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(3, calls)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(3, calls)
        } finally {
            coord.cancel()
            eager.cancel()
        }
    }

    @Test
    fun `onStop cancels the in-flight probe and does not store it`() = runTest {
        val gate = CompletableDeferred<ProbeResult>()
        val repo = ExplorerRepository()
        var calls = 0
        val coord = coordinator(repo) { _, _ ->
            calls += 1
            gate.await()
        }
        try {
            val request = ready("127.0.0.1:9050", 1)
            coord.refresh(request)
            runCurrent()
            assertEquals(1, calls)
            assertEquals(ExplorerProbeOutcome.PENDING, coord.outcome)
            coord.onScreenStop()
            gate.complete(ProbeResult.Reachable)
            advanceUntilIdle()
            assertNull(repo.rememberedProbe())
            assertEquals(ExplorerProbeOutcome.PENDING, coord.outcome)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, calls)
        } finally {
            if (!gate.isCompleted) gate.complete(ProbeResult.Unreachable)
            coord.cancel()
            advanceUntilIdle()
        }
    }

    @Test
    fun `onStop cancels a scheduled retry`() = runTest {
        var calls = 0
        val coord = coordinator { _, _ ->
            calls += 1
            ProbeResult.Unreachable
        }
        coord.refresh(ready("127.0.0.1:9050", 1))
        runCurrent()
        assertEquals(1, calls)
        coord.onScreenStop()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, calls)
    }

    @Test
    fun `a new Tor generation resets the auto-retry count`() = runTest {
        var calls = 0
        val coord = coordinator { _, _ ->
            calls += 1
            ProbeResult.unreachable("ConnectException")
        }
        coord.refresh(ready("127.0.0.1:9050", 1))
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(3, calls)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(3, calls)

        coord.refresh(ready("127.0.0.1:9051", 2))
        runCurrent()
        assertEquals(4, calls)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(5, calls)
    }

    @Test
    fun `refresh while stopped does not open a probe`() = runTest {
        var calls = 0
        val coord = coordinator(started = false) { _, _ ->
            calls += 1
            ProbeResult.Reachable
        }
        val request = ready("127.0.0.1:9050", 1)
        coord.refresh(request)
        advanceUntilIdle()
        assertEquals(0, calls)
        assertEquals(ExplorerProbeOutcome.PENDING, coord.outcome)
        coord.onScreenStart()
        coord.refresh(request)
        runCurrent()
        assertEquals(1, calls)
        assertEquals(ExplorerProbeOutcome.REACHABLE, coord.outcome)
    }

    @Test
    fun `a stale timeout is probed again and a green result of the same age is kept`() = runTest {
        val t0 = 1_700_000_000_000L
        var now = t0
        val repo = ExplorerRepository()
        val endpoint = "127.0.0.1:9050"
        val request = ready(endpoint, 1)
        var calls = 0
        val first = coordinator(repo, now = { now }) { _, _ ->
            calls += 1
            ProbeResult.unconfirmed("SocketTimeoutException")
        }
        first.refresh(request)
        runCurrent()
        assertEquals(1, calls)
        assertEquals(ExplorerProbeOutcome.UNCONFIRMED, first.outcome)

        now = t0 + ExplorerProbeMemory.UNCONFIRMED_FRESH_FOR_MS
        var later = 0
        val atEdge = coordinator(repo, now = { now }) { _, _ ->
            later += 1
            ProbeResult.Unreachable
        }
        atEdge.refresh(request)
        runCurrent()
        assertEquals(0, later)

        now = t0 + ExplorerProbeMemory.UNCONFIRMED_FRESH_FOR_MS + 1
        val stale = coordinator(repo, now = { now }) { _, _ ->
            later += 1
            ProbeResult.unreachable("ConnectException")
        }
        stale.refresh(request)
        runCurrent()
        assertEquals(1, later)
        assertEquals(ExplorerProbeOutcome.UNREACHABLE, stale.outcome)

        repo.rememberProbe(
            "PAIRED:$base",
            ExplorerProbeOutcome.REACHABLE,
            now - (ExplorerProbeMemory.UNCONFIRMED_FRESH_FOR_MS + 1),
            1,
            endpoint,
        )
        var greenCalls = 0
        val green = coordinator(repo, now = { now }) { _, _ ->
            greenCalls += 1
            ProbeResult.Unreachable
        }
        green.refresh(request)
        runCurrent()
        assertEquals(0, greenCalls)
        assertEquals(ExplorerProbeOutcome.REACHABLE, green.outcome)
    }

    @Test
    fun `the checking counter follows the probe clock`() = runTest {
        var now = 5_000L
        val gate = CompletableDeferred<ProbeResult>()
        val coord = coordinator(now = { now }) { _, _ -> gate.await() }
        try {
            coord.refresh(ready("127.0.0.1:9050", 1))
            runCurrent()
            assertEquals(0L, coord.checkingElapsedMs())
            assertEquals("Checking… 0s", explorerCheckingLabel(coord.checkingElapsedMs()))
            now = 17_400L
            assertEquals(12_400L, coord.checkingElapsedMs())
            assertEquals("Checking… 12s", explorerCheckingLabel(coord.checkingElapsedMs()))
            gate.complete(ProbeResult.Reachable)
            runCurrent()
            assertEquals(0L, coord.checkingElapsedMs())
        } finally {
            if (!gate.isCompleted) gate.complete(ProbeResult.Unreachable)
            coord.cancel()
            advanceUntilIdle()
        }
    }

    @Test
    fun `the screen follows the socks epoch and the row tap retries`() {
        val activity = source("java/com/samourai/sentinel/ui/settings/NetworkActivity.kt")
        assertTrue(activity.contains("getSocksEpochLiveData"))
        assertTrue(activity.contains("onExplorerRowTapped"))
        assertTrue(activity.contains("network_explorer_layout"))
        assertTrue(activity.contains("ExplorerHealth.CHECKING"))
        assertTrue(activity.contains("ExplorerHealth.UNREACHABLE"))
        assertTrue(activity.contains("ExplorerHealth.UNCONFIRMED"))
        assertTrue(activity.contains("explorer_status_unconfirmed"))
        assertTrue(activity.contains("explorer_status_tap_to_retry"))
        assertTrue(activity.contains("explorer_status_checking_elapsed"))
        assertTrue(activity.contains("explorer_row_retry_description"))
        assertTrue(activity.contains("explorerRowRetryable"))
        assertTrue(activity.contains("delay(1_000)"))
        assertFalse(activity.contains("network_explorer_retry"))
        val onStart = activity.substringAfter("override fun onStart()")
            .substringBefore("override fun onResume()")
        assertTrue(onStart.contains("onScreenStart"))
        val onStop = activity.substringAfter("override fun onStop()")
            .substringBefore("override fun onDestroy()")
        assertTrue(onStop.contains("onScreenStop"))
        assertTrue(onStop.contains("stopCheckingTicker"))
        val row = source("res/layout/activity_network.xml")
            .substringAfter("network_explorer_layout")
            .substringBefore("network_explorer_status_icon")
        assertTrue(row.contains("selectableItemBackground"))
        val manager = source("java/com/samourai/sentinel/tor/SentinelTorManager.kt")
        val start = manager.substringAfter("private fun startInternal")
            .substringBefore("fun stop()")
        assertTrue(start.contains("setProxy(null)"))
        val stop = manager.substringAfter("fun stop()")
            .substringBefore("fun newIdentity")
        assertTrue(stop.contains("setProxy(null)"))
        val listeners = manager.substringAfter("RuntimeEvent.LISTENERS")
            .substringBefore("config {")
        assertTrue(listeners.contains("setProxy(next)"))
        assertFalse(listeners.contains("proxy ="))
    }

    @Test
    fun `a fresh check for this tor session is shown and a previous session is probed again`() = runTest {
        val fixed = 1_700_000_000_000L
        val repo = ExplorerRepository()
        val endpoint = "127.0.0.1:9050"
        repo.rememberProbe("PAIRED:$base", ExplorerProbeOutcome.REACHABLE, fixed, 5, endpoint)
        var calls = 0
        val same = ready(endpoint = endpoint, generation = 5)
        val first = coordinator(repo, now = { fixed }) { _, _ ->
            calls += 1
            ProbeResult.Unreachable
        }
        first.refresh(same)
        runCurrent()
        assertEquals(0, calls)
        assertEquals(ExplorerProbeOutcome.REACHABLE, first.outcome)
        assertEquals(ExplorerHealth.BOUND, first.health(same).health)
        assertTrue(explorerOperatorVisible(first.health(same).health, "SG Directory Dojo"))

        val reopened = coordinator(repo, now = { fixed }) { _, _ ->
            calls += 1
            ProbeResult.Unreachable
        }
        reopened.refresh(same)
        runCurrent()
        assertEquals(0, calls)
        assertEquals(ExplorerHealth.BOUND, reopened.health(same).health)

        val moved = ready(endpoint = "127.0.0.1:9051", generation = 6)
        reopened.refresh(moved)
        runCurrent()
        assertEquals(1, calls)
        assertEquals(ExplorerProbeOutcome.UNREACHABLE, reopened.outcome)
        assertEquals(6, repo.rememberedProbe()?.socksGeneration)
        assertEquals("127.0.0.1:9051", repo.rememberedProbe()?.socksEndpoint)
        assertFalse(explorerOperatorVisible(reopened.health(moved).health, "SG Directory Dojo"))
    }

    private fun TestScope.coordinator(
        repo: ExplorerRepository = ExplorerRepository(),
        now: () -> Long = { System.currentTimeMillis() },
        started: Boolean = true,
        probe: suspend (String, String) -> ProbeResult,
    ): ExplorerProbeCoordinator {
        val coord = ExplorerProbeCoordinator(
            scope = this,
            repository = repo,
            probe = probe,
            now = now,
        )
        if (started) coord.onScreenStart()
        return coord
    }

    private fun TestScope.finished(
        probe: suspend (String, String) -> ProbeResult,
    ): ExplorerProbeCoordinator {
        val coord = coordinator(probe = probe)
        coord.refresh(ready("127.0.0.1:9050", 1))
        runCurrent()
        return coord
    }

    private fun ready(endpoint: String, generation: Int) = ExplorerProbeRequest(
        resolution = resolution,
        torState = EnumTorState.ON,
        torProgress = 100,
        socksEndpoint = endpoint,
        socksGeneration = generation,
    )

    private fun notReady(progress: Int, endpoint: String?, generation: Int) = ExplorerProbeRequest(
        resolution = resolution,
        torState = if (progress > 0) EnumTorState.ON else EnumTorState.STARTING,
        torProgress = progress,
        socksEndpoint = endpoint,
        socksGeneration = generation,
    )

    private fun capturingTree(lines: MutableList<String>): Timber.Tree =
        object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                lines += "${tag.orEmpty()} $message"
            }
        }

    private fun source(path: String): String {
        val file = listOf(File("src/main/$path"), File("app/src/main/$path")).first { it.isFile }
        return file.readText()
    }
}
