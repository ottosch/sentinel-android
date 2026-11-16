package com.samourai.sentinel.ui.webview

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.CheckBox
import androidx.webkit.WebViewFeature
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.samourai.sentinel.R
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.txfacts.TxFactsResolver
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koin.java.KoinJavaComponent.get

/**
 * Opens a tx or address on the bound explorer. A load to any party
 * that is not the Dojo's own onion explorer - either mempool choice,
 * a Custom clearnet URL, or the external analysis site - is shown
 * only after the one advisory (SR-23). The paired Dojo's onion
 * explorer and a Custom onion URL go straight there. An unbound
 * lookup stays on this screen until the user asks.
 *
 * "Don't show again" is per option and remembers the route. A worse
 * route (Tor no longer carrying the load) asks again. The Tor or IP
 * sentence is the same decision the WebView will use for that URL.
 */
class ExplorerOpener(
    private val context: Context,
    private val repository: ExplorerRepository = get(ExplorerRepository::class.java),
    private val launch: (url: String, title: String?) -> Unit = { url, title ->
        startExplorer(context, url, title)
    },
) {

    fun open(page: ExplorerBinding.ExplorerPage, id: String, offerAnalysis: Boolean = false) {
        val clean = id.trim()
        if (clean.isEmpty() || !canShowDialog()) return
        when (val resolution = repository.resolution()) {
            is ExplorerBinding.ExplorerResolution.Unbound ->
                showUnbound(page, clean, offerAnalysis)
            is ExplorerBinding.ExplorerResolution.Bound ->
                openBound(resolution, page, clean)
        }
    }

    /**
     * Privacy row: open the external analysis page for this tx.
     * Always advisory-gated, including when a paired explorer is
     * bound. The page is a third party and its script fetches the
     * transaction from the public API inside this same WebView.
     */
    fun openExternalAnalysis(txid: String) {
        val clean = txid.trim()
        if (clean.isEmpty() || !canShowDialog()) return
        val url = ExplorerRepository.AM_I_EXPOSED_TX_PREFIX + clean
        confirmAnalysis(url) {
            launch(url, clean)
        }
    }

    /**
     * Settings, or an unbound "use the clearnet site" tap. [url] is
     * the page that will load; when it is absent the clearnet base
     * for the active network stands in, which is the same host.
     */
    fun confirmThirdParty(
        party: String = TxFactsResolver.MEMPOOL_DISPLAY_NAME,
        url: String? = null,
        onContinue: () -> Unit,
    ) {
        val target = url ?: TxFactsResolver.mempoolClearnetBase(repository.facts().appIsTestnet)
        confirmOption(ExplorerOptions.Id.MEMPOOL_SPACE, target, party, onContinue)
    }

    /**
     * Blocking confirm for one third-party option. A paired explorer
     * and an onion Custom URL continue with no dialog.
     */
    fun confirmOption(
        id: ExplorerOptions.Id,
        url: String,
        onContinue: () -> Unit,
    ) {
        val party = when (id) {
            ExplorerOptions.Id.MEMPOOL_ONION,
            ExplorerOptions.Id.MEMPOOL_SPACE -> TxFactsResolver.MEMPOOL_DISPLAY_NAME
            // #524: the raw Custom template carries a literal {txid}
            // placeholder. Name the host of the page that will load.
            else -> partyLabel(url)
        }
        confirmOption(id, url, party, onContinue)
    }

    private fun confirmOption(
        id: ExplorerOptions.Id,
        url: String,
        party: String,
        onContinue: () -> Unit,
    ) {
        if (!canShowDialog()) return
        val key = ExplorerOptions.advisoryKey(id, url) ?: run {
            onContinue()
            return
        }
        val name = TxFactsResolver.MEMPOOL_DISPLAY_NAME
        val base = when (id) {
            ExplorerOptions.Id.MEMPOOL_ONION ->
                context.getString(R.string.explorer_mempool_onion_base, name)
            ExplorerOptions.Id.MEMPOOL_SPACE ->
                context.getString(R.string.explorer_mempool_space_base, name)
            else -> context.getString(R.string.explorer_third_party_base)
        }
        val tor = when (id) {
            ExplorerOptions.Id.MEMPOOL_SPACE ->
                context.getString(R.string.explorer_mempool_space_tor, name)
            ExplorerOptions.Id.CUSTOM ->
                context.getString(R.string.explorer_custom_clearnet_tor)
            else -> context.getString(R.string.explorer_third_party_tor)
        }
        val decision = currentLoadDecision(url)
        val message = ExplorerAdvisory.message(
            party = party,
            decision = decision,
            base = base,
            tor = tor,
            direct = context.getString(R.string.explorer_third_party_direct),
            unsent = context.getString(R.string.explorer_third_party_unsent),
            guidance = thirdPartyGuidance(),
        )
        showAdvisory(message, key, decision, onContinue)
    }

    /**
     * #524: the guidance line names the private alternative. With a
     * paired Dojo explorer bound, that alternative is one Settings
     * switch away, not a pairing hunt.
     */
    private fun thirdPartyGuidance(): String {
        val paired = (repository.resolution() as? ExplorerBinding.ExplorerResolution.Bound)
            ?.kind == ExplorerBinding.ExplorerKind.PAIRED
        return context.getString(
            if (paired) R.string.explorer_third_party_guidance_paired
            else R.string.explorer_third_party_guidance,
        )
    }

    /** #524: see [partyLabelOf]. */
    private fun partyLabel(url: String): String = partyLabelOf(url)

    private fun confirmAnalysis(url: String, onContinue: () -> Unit) {
        if (!canShowDialog()) return
        val paired = (repository.resolution() as? ExplorerBinding.ExplorerResolution.Bound)
            ?.kind == ExplorerBinding.ExplorerKind.PAIRED
        val base = if (paired) R.string.explorer_analysis_base_paired else R.string.explorer_analysis_base
        val decision = currentLoadDecision(url)
        val message = ExplorerAdvisory.analysisMessage(
            site = ExplorerRepository.AM_I_EXPOSED_NAME,
            api = TxFactsResolver.MEMPOOL_DISPLAY_NAME,
            decision = decision,
            base = context.getString(base),
            tor = context.getString(R.string.explorer_third_party_tor),
            direct = context.getString(R.string.explorer_analysis_direct),
            unsent = context.getString(R.string.explorer_third_party_unsent),
        )
        showAdvisory(message, ExplorerRepository.AM_I_EXPOSED_NAME, decision, onContinue)
    }

    private fun showAdvisory(
        message: String,
        key: String,
        decision: ExplorerLoadDecision,
        onContinue: () -> Unit,
    ) {
        if (!canShowDialog()) return
        if (repository.advisorySuppressed(key, decision)) {
            onContinue()
            return
        }
        val privacy = ExplorerOptions.privacyOf(decision)
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        val check = CheckBox(context).apply {
            text = context.getString(R.string.explorer_advisory_dont_show)
            setPadding(pad, pad / 2, pad, 0)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.explorer_third_party_title)
            .setMessage(message)
            .setView(check)
            .setPositiveButton(R.string.explorer_third_party_continue) { _, _ ->
                if (check.isChecked) repository.rememberAdvisory(key, privacy)
                onContinue()
            }
            .setNegativeButton(R.string.explorer_close, null)
            .show()
    }

    private fun currentLoadDecision(url: String?): ExplorerLoadDecision = explorerLoadDecision(
        proxyOverrideSupported = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE),
        torReady = SentinelTorManager.isTorReady(),
        torOff = SentinelTorManager.getTorState().state == EnumTorState.OFF,
        torRequired = ExplorerBinding.lookupRequiresTor(url, SentinelState.isTorRequired()),
    )

    private fun openBound(
        resolution: ExplorerBinding.ExplorerResolution.Bound,
        page: ExplorerBinding.ExplorerPage,
        id: String,
    ) {
        val url = ExplorerBinding.pageUrl(resolution, page, id) ?: return
        // The final URL decides, not the saved template: a Custom
        // template with {txid} placeholders does not parse as a URI,
        // but the substituted page URL does.
        if (!ExplorerBinding.requiresAdvisory(resolution, url)) {
            launch(url, id)
            return
        }
        val option = when (resolution.kind) {
            ExplorerBinding.ExplorerKind.MEMPOOL_ONION -> ExplorerOptions.Id.MEMPOOL_ONION
            ExplorerBinding.ExplorerKind.MEMPOOL -> ExplorerOptions.Id.MEMPOOL_SPACE
            ExplorerBinding.ExplorerKind.CUSTOM -> ExplorerOptions.Id.CUSTOM
            ExplorerBinding.ExplorerKind.PAIRED -> {
                launch(url, id)
                return
            }
        }
        confirmOption(option, url) { launch(url, id) }
    }

    private fun showUnbound(
        page: ExplorerBinding.ExplorerPage,
        id: String,
        offerAnalysis: Boolean,
    ) {
        val mempool = ExplorerBinding.resolve(
            repository.facts().copy(savedSelection = TxFactsResolver.MEMPOOL_DISPLAY_NAME)
        )
        val mempoolUrl = ExplorerBinding.pageUrl(mempool, page, id)
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.explorer_unbound_title)
            .setMessage(R.string.explorer_unbound_message)
            .setNegativeButton(R.string.explorer_close, null)
        if (mempoolUrl != null) {
            builder.setPositiveButton(
                context.getString(
                    R.string.explorer_use_third_party,
                    TxFactsResolver.MEMPOOL_DISPLAY_NAME,
                )
            ) { _, _ ->
                confirmThirdParty(url = mempoolUrl) { launch(mempoolUrl, id) }
            }
        }
        if (offerAnalysis && page == ExplorerBinding.ExplorerPage.TX) {
            builder.setNeutralButton(
                context.getString(
                    R.string.explorer_analyze_externally,
                    ExplorerRepository.AM_I_EXPOSED_NAME,
                )
            ) { _, _ ->
                openExternalAnalysis(id)
            }
        }
        builder.show()
    }

    private fun canShowDialog(): Boolean {
        val activity = context as? Activity ?: return true
        return !activity.isFinishing && !activity.isDestroyed
    }

    companion object {

        /** #524: a host longer than this is truncated, never raw. */
        private const val HOST_LABEL_MAX = 40

        /**
         * The same screen for a page that isn't an explorer page, such as a
         * signer's PayNym page ([kind] goes in
         * [ExplorerWebViewActivity.EXTRA_PAGE]). It loads under the screen's
         * own Tor rules; the caller builds [url].
         */
        fun pageIntent(context: Context, url: String, title: String, kind: String): Intent =
            Intent(context, ExplorerWebViewActivity::class.java)
                .putExtra(ExplorerWebViewActivity.EXTRA_URL, url)
                .putExtra(ExplorerWebViewActivity.EXTRA_TITLE, title)
                .putExtra(ExplorerWebViewActivity.EXTRA_PAGE, kind)
                .apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

        fun startExplorer(context: Context, url: String, title: String?) {
            val intent = Intent(context, ExplorerWebViewActivity::class.java)
                .putExtra(ExplorerWebViewActivity.EXTRA_URL, url)
            if (!title.isNullOrBlank()) {
                intent.putExtra(ExplorerWebViewActivity.EXTRA_TITLE, title)
            }
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        /** #524: a long host shortens like an onion label does. */
        private const val HOST_LABEL_KEEP = 14
        private const val HOST_LABEL_TAIL = 6

        /**
         * #524: the third party a dialog names. The host is the one
         * Chromium will load. Placeholders are stood in first, `\`
         * ends the authority the way `/` does, and userinfo is the
         * part before the last `@`. An onion host shortens the same
         * way as the Network row. A URL that does not parse is the
         * truncated URL, never a host guessed from the raw authority,
         * and the placeholder still does not reach the user.
         */
        internal fun partyLabelOf(url: String): String {
            val trimmed = url.trim()
            val host = browserHost(ExplorerOptions.parsable(trimmed))
            if (!host.isNullOrEmpty()) {
                ExplorerBinding.shortenOnion("http://$host/")?.let { return it }
                if (host.length <= HOST_LABEL_MAX) return host
                return host.take(HOST_LABEL_KEEP) + "…" + host.takeLast(HOST_LABEL_TAIL)
            }
            return truncatedRaw(trimmed)
        }

        /**
         * Host Chromium loads, or null when [substituted] does not
         * parse. OkHttp follows the same authority rules: `\` is a
         * delimiter, and the host is after the last `@`. A
         * scheme-less template is read as http so its host can
         * still be named.
         */
        private fun browserHost(substituted: String): String? {
            // Chromium deletes tab and newline before parsing, so
            // `https:/\t/evil.example` loads evil.example. Do not
            // prepend a scheme onto a string that already has one:
            // that labeled this URL "https".
            val stripped = buildString(substituted.length) {
                for (c in substituted) {
                    if (c == '\t' || c == '\n' || c == '\r') continue
                    append(c)
                }
            }
            stripped.toHttpUrlOrNull()?.host?.takeIf { it.isNotEmpty() }?.let { return it }
            if (':' in stripped) return null
            return "http://$stripped".toHttpUrlOrNull()?.host?.takeIf { it.isNotEmpty() }
        }

        /** The URL itself, shortened. Not a host taken from a failed parse. */
        private fun truncatedRaw(url: String): String {
            val shown = ExplorerOptions.parsable(url)
            if (shown.length > ExplorerBinding.LABEL_KEEP_WHOLE) {
                return shown.take(HOST_LABEL_KEEP) + "…" + shown.takeLast(HOST_LABEL_TAIL)
            }
            return shown
        }
    }
}
