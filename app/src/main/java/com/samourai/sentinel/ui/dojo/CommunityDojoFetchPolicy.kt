package com.samourai.sentinel.ui.dojo

import com.samourai.sentinel.ui.dojo.CommunityDojoRepository.Failure

/**
 * Time limits and the automatic retry for Dojo Bay fetches, kept out of the
 * sheet so the rules are unit-tested.
 *
 * Right after Tor (re)starts, it reports 100 % from its cached state before
 * it has a working path to onion services. The first connection to the
 * Dojo Bay onion then often waits ~80 s inside Tor, and sometimes more than
 * 120 s, before anything reaches the server. So:
 *  - the first fetch after a Tor start gets [FIRST_AFTER_TOR_START_TIMEOUT_SECONDS];
 *    every other attempt keeps [NORMAL_TIMEOUT_SECONDS];
 *  - Tor's own SOCKS timeout is set above that (see
 *    [com.samourai.sentinel.tor.SentinelTorManager.SOCKS_TIMEOUT_SECONDS]), so
 *    Tor does not give up on the stream before the app does;
 *  - one automatic retry follows a failed attempt whichever side gave up:
 *    the app's own timeout, or Tor failing the stream (SOCKS error, "TTL
 *    expired", connection refused or reset by the proxy). An answer from
 *    Dojo Bay (HTTP error, unreadable or oversized body) is not retried,
 *    and neither is Tor not being available at all.
 *
 * "A Tor start" is a new SOCKS session
 * ([com.samourai.sentinel.tor.SentinelTorManager.socksGeneration]): the
 * longer limit is used by the first attempt on that session that runs to
 * an end (an answer or a failure). An attempt cancelled by closing the
 * sheet leaves it for the next one.
 */
object CommunityDojoFetchPolicy {

    /** The usual limit: Dojo Bay probes every listed node before answering (#135). */
    const val NORMAL_TIMEOUT_SECONDS = 120L

    /**
     * The first fetch after a Tor start: room for Tor's first-connection
     * stall (~80 s, seen above 120 s) plus Dojo Bay's own probing.
     */
    const val FIRST_AFTER_TOR_START_TIMEOUT_SECONDS = 240L

    /** Exactly one automatic retry per fetch. */
    const val AUTO_RETRIES = 1

    const val AUTO_RETRY_DELAY_MS = 3_000L

    /** True for failures where Dojo Bay never answered: the app's timeout, or Tor failing the stream. */
    fun isRetryable(failure: Failure): Boolean =
        failure == Failure.TIMEOUT || failure == Failure.UNREACHABLE

    /**
     * True when the fetch that ended with [failure] really made its automatic
     * retry: decided from the number of attempts made, not inferred from the
     * failure type.
     */
    fun failedAfterRetry(failure: Failure, attempts: Int): Boolean =
        isRetryable(failure) && attempts > AUTO_RETRIES

    /** [attempt] is 1-based: attempt 1 may be retried once, attempt 2 never. */
    fun shouldRetry(failure: Failure, attempt: Int): Boolean =
        attempt <= AUTO_RETRIES && isRetryable(failure)

    /**
     * The limit for [attempt] on Tor session [socksGeneration], given the
     * session that already had its long first fetch ([settledGeneration],
     * null if none yet). Only attempt 1 can get the longer limit: the
     * automatic retry is a normal attempt.
     */
    fun timeoutSeconds(attempt: Int, socksGeneration: Int, settledGeneration: Int?): Long =
        if (attempt == 1 && socksGeneration != settledGeneration) FIRST_AFTER_TOR_START_TIMEOUT_SECONDS
        else NORMAL_TIMEOUT_SECONDS

    /** Worst case from the first request to the final message: long first try, the pause, one normal retry. */
    const val WORST_CASE_SECONDS =
        FIRST_AFTER_TOR_START_TIMEOUT_SECONDS + AUTO_RETRY_DELAY_MS / 1000 + NORMAL_TIMEOUT_SECONDS

    @Volatile
    private var settledGeneration: Int? = null

    /** The limit for [attempt] on the current Tor session. */
    fun timeoutFor(attempt: Int, socksGeneration: Int): Long =
        timeoutSeconds(attempt, socksGeneration, settledGeneration)

    /** An attempt on [socksGeneration] ran to an end: later attempts on that session use the normal limit. */
    fun settle(socksGeneration: Int) {
        settledGeneration = socksGeneration
    }

    /** For tests. */
    internal fun resetForTest() {
        settledGeneration = null
    }
}
