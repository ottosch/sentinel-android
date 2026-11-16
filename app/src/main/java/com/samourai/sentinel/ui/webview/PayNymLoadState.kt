package com.samourai.sentinel.ui.webview

/**
 * Where a PayNym page is in the explorer WebView screen: waiting for Tor,
 * loading through the Tor proxy, shown, or failed. A failed load gets the
 * app's own "Couldn't reach PayNym over Tor" screen instead of Chromium's
 * error page, which prints the page's URL.
 */
enum class PayNymLoadPhase {
    /** Nothing asked yet, or a block dialog is up (Tor off, no proxy support). */
    IDLE,

    /** Tor is starting: nothing is fetched until it is ready ([ExplorerLoadDecision.WAIT_FOR_TOR]). */
    WAITING_FOR_TOR,

    /** The page was handed to the WebView through the Tor proxy. */
    LOADING,

    /** The page drew. */
    LOADED,

    /** Tor didn't get ready in time, or the main frame failed or timed out. */
    FAILED,
}

enum class PayNymLoadEvent {
    /** Routing decided to wait for Tor. */
    WAIT_FOR_TOR,

    /** The page was handed to the WebView, through the Tor proxy. */
    LOAD_STARTED,

    /** The WebView drew the page ([android.webkit.WebViewClient.onPageCommitVisible]). */
    PAGE_SHOWN,

    /** The main frame failed: a network error or an HTTP error status. */
    MAIN_FRAME_ERROR,

    /** The phase's time limit ([PayNymLoad.limitFor]) ran out. */
    TIMED_OUT,

    /** A block dialog took over (Tor off, or no proxy support). */
    BLOCKED,
}

object PayNymLoad {

    /** How long the screen waits for Tor to be ready before giving up. */
    const val TOR_WAIT_LIMIT_MS = 90_000L

    /** How long a page load through Tor may take before giving up. */
    const val PAGE_LOAD_LIMIT_MS = 60_000L

    /** The phase after [event] in [phase]. */
    fun next(phase: PayNymLoadPhase, event: PayNymLoadEvent): PayNymLoadPhase = when (event) {
        PayNymLoadEvent.WAIT_FOR_TOR -> PayNymLoadPhase.WAITING_FOR_TOR
        PayNymLoadEvent.LOAD_STARTED -> PayNymLoadPhase.LOADING
        // Chromium may still draw its error page after a failure: stay failed.
        PayNymLoadEvent.PAGE_SHOWN ->
            if (phase == PayNymLoadPhase.LOADING) PayNymLoadPhase.LOADED else phase
        PayNymLoadEvent.MAIN_FRAME_ERROR ->
            if (phase == PayNymLoadPhase.LOADING || phase == PayNymLoadPhase.LOADED) PayNymLoadPhase.FAILED else phase
        PayNymLoadEvent.TIMED_OUT ->
            if (phase == PayNymLoadPhase.WAITING_FOR_TOR || phase == PayNymLoadPhase.LOADING) PayNymLoadPhase.FAILED else phase
        PayNymLoadEvent.BLOCKED -> PayNymLoadPhase.IDLE
    }

    /** The time limit while in [phase], or null for none. */
    fun limitFor(phase: PayNymLoadPhase): Long? = when (phase) {
        PayNymLoadPhase.WAITING_FOR_TOR -> TOR_WAIT_LIMIT_MS
        PayNymLoadPhase.LOADING -> PAGE_LOAD_LIMIT_MS
        else -> null
    }

    /**
     * Whether a WebView error report fails the page: only for the main
     * frame (a missing image or script doesn't), never for a request the
     * app itself refused ([blockedByApp]: that one gets the Tor block
     * dialog), and for HTTP only a 4xx or 5xx [httpStatus]. A null
     * [httpStatus] is a network error (timeout, connection refused, ...).
     */
    fun failsPage(isForMainFrame: Boolean, httpStatus: Int?, blockedByApp: Boolean): Boolean {
        if (!isForMainFrame || blockedByApp) return false
        return httpStatus == null || httpStatus >= 400
    }
}
