package com.samourai.sentinel.ui.dojo

import com.samourai.sentinel.ui.dojo.CommunityDojoRepository.Failure

/**
 * Time limits and the automatic retry for Dojo Bay fetches, kept out of the
 * sheet so the rules are unit-tested.
 *
 *  - Every attempt gets [NORMAL_TIMEOUT_SECONDS], the same as Tor's own
 *    default SOCKS timeout. Tor's SocksTimeout is left at its default.
 *  - One automatic retry follows a failed attempt whichever side gave up:
 *    the app's own timeout, or Tor failing the stream (SOCKS error, "TTL
 *    expired", connection refused or reset by the proxy). The retry gets
 *    fresh SOCKS credentials and so a new Tor circuit ([TorIsolatedSocks]):
 *    right after a Tor start, a first onion connection can sit in a stalled
 *    circuit, and a new circuit usually answers within seconds where more
 *    time on the old one does not.
 *  - An answer from Dojo Bay (HTTP error, unreadable or oversized body) is
 *    not retried, and neither is Tor not being available at all.
 */
object CommunityDojoFetchPolicy {

    /** Every attempt's limit: Dojo Bay probes every listed node before answering (#135). */
    const val NORMAL_TIMEOUT_SECONDS = 120L

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

    /** Worst case from the first request to the final message: one try, the pause, one retry. */
    const val WORST_CASE_SECONDS =
        NORMAL_TIMEOUT_SECONDS + AUTO_RETRY_DELAY_MS / 1000 + NORMAL_TIMEOUT_SECONDS
}
