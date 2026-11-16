package com.samourai.sentinel.ui.webview

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.samourai.sentinel.R
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.Tx
import com.samourai.sentinel.databinding.ActivityExplorerWebViewBinding
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.ui.dojo.PayNymPage
import com.samourai.sentinel.ui.utils.showFloatingSnackBar
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress


class ExplorerWebViewActivity : AppCompatActivity() {

    lateinit var client: WebViewClient
    var tx: Tx? = null
    var url = ""
    lateinit var binding: ActivityExplorerWebViewBinding
    private var blockedDialog: AlertDialog? = null
    private var pageLoaded = false

    /**
     * True once the Tor proxy override has been applied for this
     * screen. Stays true. A direct load is refused after that, so a
     * Tor session cannot fall back onto the clear path.
     */
    @Volatile
    private var torSession = false

    /**
     * True once this screen has loaded without the proxy. Stays true.
     * An onion host is not fetched later from this WebView, even if
     * Tor is started and the proxy is applied afterwards.
     */
    @Volatile
    private var directSession = false

    /**
     * A PayNym page ("View on PayNym" in Community Dojos): an onion, so Tor
     * is always required, and every block explains with the link to copy.
     */
    private var payNym = false
    private val explorerRepository: ExplorerRepository by inject(ExplorerRepository::class.java)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExplorerWebViewBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)

        setSupportActionBar(binding.toolBar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val incoming = intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }
        val explicitTitle = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }
        payNym = intent.getStringExtra(EXTRA_PAGE) == PayNymPage.PAGE_KIND
        val selected = if (payNym) null else SentinelState.selectedTx
        if (payNym) {
            // Only a link built from a signer's +name; anything else closes.
            if (PayNymPage.nymOf(incoming) == null) {
                finish()
                return
            }
            title = PayNymPage.TITLE
        } else if (incoming != null && explicitTitle != null) {
            title = explicitTitle
            if (selected != null && selected.hash.split("-")[0] == explicitTitle) {
                tx = selected
            }
        } else if (selected != null) {
            tx = selected
            title = tx!!.hash.split("-")[0]
        } else if (incoming != null) {
            title = explicitTitle.orEmpty()
        } else {
            finish()
            return
        }

        binding.webView.setBackgroundColor(0)

        if (WebViewFeature.isFeatureSupported(WebViewFeature.START_SAFE_BROWSING)) {
            WebViewCompat.startSafeBrowsing(applicationContext) { }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(binding.webView.settings, WebSettingsCompat.FORCE_DARK_ON)
        }
        if (!payNym && incoming == null && tx != null &&
            explorerRepository.pageUrl(ExplorerBinding.ExplorerPage.TX, tx!!.hash.split("-")[0]) == null
        ) {
            // Opened with no URL and nothing bound. Ask here; a confirmed
            // third party comes back through EXTRA_URL and the Tor gate.
            val txid = tx!!.hash.split("-")[0]
            ExplorerOpener(this, launch = { target, _ ->
                intent.putExtra(EXTRA_URL, target)
                beginRouting()
            }).open(ExplorerBinding.ExplorerPage.TX, txid)
            return
        }
        beginRouting()
    }

    /**
     * The URL this screen will load first, when one is already known.
     * The opener passes it in. A bare transaction falls back to the
     * bound explorer. Later main-frame loads, redirects, and
     * subresources are checked again in the WebView client. Any
     * `.onion` host, not only mempool.onion, needs Tor for that check.
     */
    private fun loadTarget(): String? {
        val incoming = intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }
        if (incoming != null) return incoming
        val id = tx?.hash?.split("-")?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return explorerRepository.pageUrl(ExplorerBinding.ExplorerPage.TX, id)
    }

    /** Wallet Tor rule, plus any `.onion` host. */
    private fun torRequiredForThisPage(): Boolean =
        payNym || ExplorerBinding.lookupRequiresTor(loadTarget(), SentinelState.isTorRequired())

    /**
     * #472: Tor-required is part of every load decision. #241 already
     * fails closed on STARTING / ON-before-listener; this adds the OFF
     * and unsupported-proxy arms, which used to load direct even when
     * a paired Dojo or the Tor toggle requires Tor.
     */
    private fun beginRouting() {
        val decision = explorerLoadDecision(
            proxyOverrideSupported = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE),
            torReady = SentinelTorManager.isTorReady(),
            torOff = SentinelTorManager.getTorState().state == EnumTorState.OFF,
            torRequired = torRequiredForThisPage(),
        )
        when (decision) {
            ExplorerLoadDecision.LOAD_VIA_PROXY -> setProxy()
            // Tor optional and disabled: direct is the expected mode.
            ExplorerLoadDecision.LOAD_DIRECT -> markDirectAndLoad()
            // STARTING / ON-before-listener: defer, show the same progress
            // the page load uses - fetch nothing. The Tor observer's ON arm
            // completes the load once the listener is live.
            ExplorerLoadDecision.WAIT_FOR_TOR -> binding.progressWeb.visibility = View.VISIBLE
            ExplorerLoadDecision.BLOCK_TOR_OFF -> showBlocked(
                getString(R.string.explorer_tor_required_title),
                getString(torOffMessage()),
                allowWait = true
            )
            ExplorerLoadDecision.BLOCK_PROXY_UNSUPPORTED -> showBlocked(
                getString(R.string.explorer_tor_required_title),
                getString(unsupportedMessage()),
                allowWait = false
            )
            // Tor optional: going direct is the user's explicit choice.
            ExplorerLoadDecision.OFFER_DIRECT_UNSUPPORTED ->
                this.showFloatingSnackBar(
                    binding.webView,
                    text = getString(R.string.explorer_proxy_unsupported_optional),
                    actionText = getString(R.string.explorer_continue_without_tor),
                    actionClick = { markDirectAndLoad() }
                )
        }
    }

    /**
     * #472: the explorer will not fetch anything. Explains why and offers
     * a safe exit; never a direct-load fallback. With [allowWait] the
     * screen stays open and the Tor observer loads through the proxy if
     * Tor becomes ready.
     */
    private fun torOffMessage(): Int =
        if (payNym) R.string.paynym_tor_required_off else R.string.explorer_tor_required_off

    private fun unsupportedMessage(): Int =
        if (payNym) R.string.paynym_tor_required_unsupported else R.string.explorer_tor_required_unsupported

    private fun showBlocked(title: String, message: String, allowWait: Boolean) {
        if (isFinishing || isDestroyed) return
        binding.progressWeb.visibility = View.INVISIBLE
        blockedDialog?.dismiss()
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(R.string.explorer_close) { _, _ -> finish() }
        if (allowWait) {
            builder.setNegativeButton(R.string.explorer_wait_for_tor) { _, _ ->
                binding.progressWeb.visibility = View.VISIBLE
            }
        }
        // A PayNym page can still be opened elsewhere over Tor: copying
        // the link is the fallback. Nothing is loaded here.
        val payNymLink = if (payNym) PayNymPage.urlFor(PayNymPage.nymOf(intent.getStringExtra(EXTRA_URL))) else null
        if (payNymLink != null) {
            builder.setNeutralButton(R.string.paynym_copy_link) { _, _ ->
                copyText(payNymLink, R.string.paynym_link_copied)
                finish()
            }
        }
        blockedDialog = builder.show()
    }

    private fun setProxy() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            // Only reachable if support changed under us; never go direct.
            showBlocked(
                getString(R.string.explorer_tor_required_title),
                getString(unsupportedMessage()),
                allowWait = false
            )
            return
        }
        // #241: null must never reach the rule - callers gate on
        // isTorReady(); this re-reads and fails closed.
        val proxy = SentinelTorManager.getProxy() ?: run {
            Timber.w("Explorer proxy deferred: SOCKS listener not yet published (#241)")
            return
        }
        // androidx.webkit expects "socks://host:port" - the old
        // "SOCKS:/" + address() built "SOCKS:///127.0.0.1:9050" from
        // InetSocketAddress's leading slash (and "SOCKS:/null" on the
        // boot=5 window). Build the rule from the parsed fields.
        val address = proxy.address() as? InetSocketAddress ?: run {
            // #472: was a silent return - a spinner forever.
            proxyFailed(IllegalStateException("Tor proxy has no socket address"))
            return
        }
        // #252 regression fix: .hostName resolves. Android drops the
        // constructor's host string once it resolves, so the getter
        // falls through to a reverse-DNS lookup - a network call on
        // the main thread, NetworkOnMainThreadException at launch.
        // hostString never resolves and exists since API 24 (= minSdk).
        try {
            val proxyConfig = ProxyConfig.Builder()
                .addProxyRule("socks://${address.hostString}:${address.port}")
                .build()
            // #472: the old call passed load() as the Executor and an
            // empty listener. Now the listener loads, on Main, only once
            // the override is applied.
            ProxyController.getInstance().setProxyOverride(
                proxyConfig,
                ContextCompat.getMainExecutor(this)
            ) {
                if (isFinishing || isDestroyed) return@setProxyOverride
                blockedDialog?.dismiss()
                blockedDialog = null
                markProxyAndLoad()
            }
        } catch (e: Exception) {
            // #472: an invalid rule or an unsupported override throws;
            // fail closed with an explanation instead of a silent spinner.
            proxyFailed(e)
        }
    }

    private fun proxyFailed(cause: Exception) {
        Timber.e(cause, "Explorer proxy override failed (#472)")
        showBlocked(
            getString(R.string.explorer_tor_route_failed_title),
            getString(if (payNym) R.string.paynym_tor_route_failed else R.string.explorer_tor_route_failed),
            allowWait = false
        )
    }



    /**
     * Direct is the route only when this screen has never applied the
     * proxy. After [markProxyAndLoad], this returns without loading:
     * a Tor session does not fall back to a clear connection.
     */
    private fun markDirectAndLoad() {
        if (torSession) return
        directSession = true
        load()
    }

    /** The proxy override is in place. A session that already loaded direct stays direct. */
    private fun markProxyAndLoad() {
        torSession = true
        load()
    }

    /** Same dialog as the first-load block. No direct-load button. */
    private fun explainOnionBlocked() {
        val unsupported = !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
        showBlocked(
            getString(R.string.explorer_tor_required_title),
            getString(if (unsupported) unsupportedMessage() else torOffMessage()),
            allowWait = !unsupported,
        )
    }

    private fun followupFor(request: WebResourceRequest): ExplorerFollowup =
        explorerFollowup(
            url = request.url?.toString(),
            host = request.url?.host,
            mainFrame = request.isForMainFrame || request.isRedirect,
            torSession = torSession,
            directSession = directSession,
            torReady = SentinelTorManager.isTorReady(),
        )

    /**
     * Local answer for an onion request this WebView must not make.
     * The host is not looked up. The body is empty.
     */
    private fun blockedOnionResponse(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "utf-8",
            403,
            "Blocked",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(ByteArray(0)),
        )

    @SuppressLint("SetJavaScriptEnabled")
    private fun load() {
        binding.webView.settings.builtInZoomControls = true
        binding.webView.settings.javaScriptEnabled = true
        binding.webView.settings.domStorageEnabled = true

        binding.webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.progressWeb.visibility = View.VISIBLE
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                Timber.i("onPageCommitVisible: ")
                binding.progressWeb.visibility = View.INVISIBLE
            }

            /**
             * Main-frame navigations and redirects. [WebResourceRequest.getUrl]
             * is the target, including when [WebResourceRequest.isRedirect]
             * is set. Returning true cancels the load, so the onion host
             * is not resolved. [loadUrl] does not come through here; the
             * first URL was already gated.
             */
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                return when (followupFor(request)) {
                    ExplorerFollowup.ALLOW -> false
                    ExplorerFollowup.BLOCK_AND_EXPLAIN -> {
                        explainOnionBlocked()
                        true
                    }
                    ExplorerFollowup.BLOCK_SILENT -> true
                }
            }

            /**
             * Subresources, and any main-frame request that still reaches
             * the loader (a redirect the override did not see). A blocked
             * onion is answered here and never handed to the stack.
             */
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val decision = followupFor(request)
                if (decision == ExplorerFollowup.ALLOW) return null
                if (decision == ExplorerFollowup.BLOCK_AND_EXPLAIN) {
                    runOnUiThread { explainOnionBlocked() }
                }
                return blockedOnionResponse()
            }
        }
        // #521: with no WebChromeClient, Chromium's default client
        // writes every page console message to logcat as an
        // I/chromium [INFO:CONSOLE] line - the source field is the
        // full page URL: the paired explorer's onion host and the
        // viewed txid, on release builds, never through Timber
        // (the release tree records throwables only). Returning
        // true marks the message handled, so Chromium writes
        // nothing. Nothing is re-logged here: no URL, host, or
        // txid reaches any line - the ExplorerProbeLog discipline.
        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                return true
            }
        }
        val target = intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }
            ?: tx?.let {
                explorerRepository.pageUrl(ExplorerBinding.ExplorerPage.TX, it.hash.split("-")[0])
            }
        if (target.isNullOrBlank()) {
            binding.progressWeb.visibility = View.INVISIBLE
            return
        }
        url = target
        pageLoaded = true
        binding.webView.loadUrl(url)

    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.web_explorer, menu)
        // A PayNym page has no transaction to copy.
        if (payNym) menu.findItem(R.id.menu_web_copy_tx)?.isVisible = false
        SentinelTorManager.getTorStateLiveData().observe(this) {
            menu.findItem(R.id.menu_web_tor).icon =
                ContextCompat.getDrawable(applicationContext, R.drawable.ic_tor_on)
            val icon = menu.findItem(R.id.menu_web_tor).icon
            when (it.state) {
                EnumTorState.STARTING -> {
                    icon?.setTint(ContextCompat.getColor(applicationContext, R.color.md_amber_300))
                }
                EnumTorState.ON -> {
                    icon?.setTint(ContextCompat.getColor(applicationContext, R.color.md_green_600))
                    // #241: ON fires at boot=5; only route through the proxy
                    // at full readiness. Until then this screen fetches
                    // nothing - the next state emission (listener published,
                    // boot=100) completes the load through the proxy.
                    // #472: unsupported devices were decided in onCreate.
                    if (SentinelTorManager.isTorReady() &&
                        WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
                    ) {
                        setProxy()
                    }
                }
                EnumTorState.OFF -> {
                    menu.findItem(R.id.menu_web_tor).icon =
                        ContextCompat.getDrawable(applicationContext, R.drawable.ic_tor_disabled)
                    menu.findItem(R.id.menu_web_tor).icon?.setTint(
                        ContextCompat.getColor(
                            applicationContext,
                            R.color.md_grey_400
                        )
                    )
                    // STARTING waits. If Tor then stops before any load,
                    // explain it. Opening while already off showed this
                    // dialog in onCreate; do not stack a second one.
                    val stopped = explorerWhenTorStopsBeforeLoad(
                        torRequired = torRequiredForThisPage(),
                        pageLoaded = pageLoaded,
                    )
                    if (stopped == ExplorerLoadDecision.BLOCK_TOR_OFF &&
                        blockedDialog?.isShowing != true
                    ) {
                        showBlocked(
                            getString(R.string.explorer_tor_required_title),
                            getString(torOffMessage()),
                            allowWait = true,
                        )
                    }
                }
                else -> {
                }
            }
        }
        return super.onCreateOptionsMenu(menu)
    }


    override fun onDestroy() {
        blockedDialog?.dismiss()
        blockedDialog = null
        binding.webView.stopLoading()
        binding.webView.destroy()
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
            }
            R.id.menu_web_copy_tx -> {
                val value = tx?.hash?.split("-")?.firstOrNull()
                    ?: intent.getStringExtra(EXTRA_TITLE)
                value?.let { copyText(it) }
            }
            R.id.menu_web_copy_url -> {
                binding.webView.url?.let { copyText(it) }
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    private fun copyText(string: String, toast: Int = R.string.copied_to_clipboard) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
        val clipData = ClipData
                .newPlainText("", string)
        if (cm != null) {
            cm.setPrimaryClip(clipData)
            Toast.makeText(applicationContext, getString(toast), Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        /**
         * When present, load this URL. Callers resolve it through
         * [ExplorerRepository] first, including a one-shot third-party
         * page the user just confirmed. Absent, the activity resolves
         * the selected transaction itself.
         */
        const val EXTRA_URL = "com.samourai.sentinel.ui.webview.EXTRA_URL"
        const val EXTRA_TITLE = "com.samourai.sentinel.ui.webview.EXTRA_TITLE"

        /**
         * What kind of page [EXTRA_URL] is, when it isn't an explorer page:
         * [PayNymPage.PAGE_KIND] for a signer's PayNym page.
         */
        const val EXTRA_PAGE = "com.samourai.sentinel.ui.webview.EXTRA_PAGE"
    }
}