package com.invertedx.hummingbird

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * #71 measurement only. Counts and timings for `adb logcat -s QRPerf`.
 * Not a scan-speed change.
 *
 * The analyze() path only increments integers. A line is written every
 * [FRAME_LOG_INTERVAL] entries and on session start, stop, and UR complete.
 * Lines carry counters only: no frame bytes, payloads, or other user data.
 */
object QrPerf {
    const val TAG = "QRPerf"

    private const val FRAME_LOG_INTERVAL = 100

    private val sessionId = AtomicInteger(0)
    private val attempts = AtomicInteger(0)
    private val decoded = AtomicInteger(0)
    private val accepted = AtomicInteger(0)
    private val expected = AtomicInteger(0)
    private val percent = AtomicInteger(0)

    @Volatile
    private var sessionStartElapsedMs: Long = 0L

    @Volatile
    private var completeLogged: Boolean = false

    internal fun onSessionStart() {
        sessionId.incrementAndGet()
        attempts.set(0)
        decoded.set(0)
        accepted.set(0)
        expected.set(0)
        percent.set(0)
        completeLogged = false
        sessionStartElapsedMs = SystemClock.elapsedRealtime()
        log("start")
    }

    /**
     * One analyze() entry. Returns true on the periodic boundary so the
     * caller can log after this frame's decode result is recorded.
     */
    internal fun noteAttempt(): Boolean {
        val n = attempts.incrementAndGet()
        return n % FRAME_LOG_INTERVAL == 0
    }

    internal fun noteDecoded() {
        decoded.incrementAndGet()
    }

    /**
     * Latest UR transmission gauge. [processedParts] is the decoder's
     * accepted (non-duplicate) part count. Stores only; milestones log.
     */
    fun noteTransmission(expectedParts: Int, processedParts: Int, progress: Double) {
        expected.set(expectedParts)
        accepted.set(processedParts)
        percent.set((progress * 100.0).roundToInt())
    }

    internal fun logPeriodic() {
        log("frames")
    }

    internal fun onSessionComplete() {
        if (completeLogged) return
        completeLogged = true
        log("complete")
    }

    internal fun onSessionStop() {
        log("stop")
    }

    private fun log(event: String) {
        val elapsed = SystemClock.elapsedRealtime() - sessionStartElapsedMs
        Log.i(
            TAG,
            "session=${sessionId.get()} event=$event attempts=${attempts.get()} decoded=${decoded.get()} accepted=${accepted.get()} expected=${expected.get()} pct=${percent.get()} elapsedMs=$elapsed"
        )
    }
}
