package com.samourai.sentinel.ui.utxos

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.samourai.sentinel.R
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.db.dao.UtxoDao
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.data.db.entity.UtxoLabel
import com.samourai.sentinel.data.repository.LabelRepository
import com.samourai.sentinel.data.whirlpool.CoinLabel
import com.samourai.sentinel.ui.collectionDetails.transactions.LabelEdit
import com.samourai.sentinel.databinding.ActivityUtxoDetailsBinding
import com.samourai.sentinel.ui.SentinelActivity
import com.samourai.sentinel.ui.webview.ExplorerBinding
import com.samourai.sentinel.ui.webview.ExplorerOpener
import com.samourai.sentinel.util.UtxoMetaUtil
import org.koin.java.KoinJavaComponent.bind
import org.koin.java.KoinJavaComponent.inject
import android.widget.EditText
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.DecimalFormat

class UtxoDetailsActivity : SentinelActivity() {

    private val repository: CollectionRepository by inject(CollectionRepository::class.java)
    private var collection: PubKeyCollection? = null
    private lateinit var binding: ActivityUtxoDetailsBinding
    private var idx: String? = null
    private var address: String? = null
    private var amount: String? = null
    private var blocked: Boolean? = null
    private val utxoDao: UtxoDao by inject(UtxoDao::class.java)
    private val labelRepository: LabelRepository by inject(LabelRepository::class.java)
    private var currentLabelText: String? = null
    private var storedLabel: String? = null
    private var storedOrigin: String? = null
    private var labelRow: UtxoLabel? = null
    private var coinPubKey: String = ""
    private var coinMarked = false
    private var watchesCoinLabel = false
    val df = DecimalFormat("#")


    override fun onCreateAdmitted(savedInstanceState: Bundle?) {
        binding = ActivityUtxoDetailsBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)
        setSupportActionBar(findViewById(R.id.toolbar))
        binding.toolbarUtxoActivity.inflateMenu(R.menu.utxo_details_menu)
        binding.toolbarUtxoActivity.menu.findItem(R.id.utxo_details_menu_action_more_options)?.isVisible = false
        binding.toolbarUtxoActivity.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.utxo_details_view_in_explorer) {
                val txid = idx?.substringBefore(':').orEmpty()
                ExplorerOpener(this).open(ExplorerBinding.ExplorerPage.TX, txid)
                true
            } else {
                false
            }
        }
        window.statusBarColor = ContextCompat.getColor(this, R.color.mpm_black)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.grey_homeActivity)
        binding.toolbarUtxoActivity.setBackgroundColor(ContextCompat.getColor(this, R.color.mpm_black))
        val addressTextView = binding.utxoDetailsAddress
        val amountTextView = binding.utxoDetailsAmount
        val statusTextView = binding.utxoDetailsSpendableStatus
        val hashTextView = binding.utxoDetailsHash
        val df = DecimalFormat("#")

        df.minimumIntegerDigits = 1
        df.minimumFractionDigits = 8
        df.maximumFractionDigits = 8

        if (intent.extras != null && intent.extras!!.containsKey("idx")) {
            idx = intent.extras!!.getString("idx")
        } else {
            finish()
        }

        utxoDao.getUTXObyIdx(idx!!).observe(this@UtxoDetailsActivity) {
            // The UTXO can disappear (spent / resync) while this screen is
            // open; an empty emission previously crashed on it[0].
            if (it.isNullOrEmpty()) {
                finish()
                return@observe
            }
            address = it[0].addr
            coinPubKey = it[0].pubKey
            amountTextView.text = (df.format(it[0].value?.div(1e8))).toString() + " BTC"
            addressTextView.text = it[0].addr
            if (watchesCoinLabel) renderCoinLabel()
        }
        hashTextView.setText(idx)

        // ---- Label display & editing (network-scoped) ----
        val parts = idx!!.split(":")
        val txid = parts.getOrNull(0)
        val vout = parts.getOrNull(1)?.toIntOrNull()
        if (txid != null && vout != null) {
            watchesCoinLabel = true
            labelRepository.observe(txid, vout).observe(this@UtxoDetailsActivity) { labelEntity ->
                // Coin labels stay visible regardless of Whirlpool
                // auto-labels. The setting hides transaction labels only.
                labelRow = labelEntity
                storedLabel = labelEntity?.label
                storedOrigin = labelEntity?.origin
                renderCoinLabel()
            }
            binding.utxoDetailsLabel.setOnClickListener {
                showLabelDialog(txid, vout)
            }
        } else {
            binding.utxoDetailsLabel.text = idx
        }

        if (isBlocked()) {
            statusTextView.text = getText(R.string.blocked)
        } else {
            statusTextView.text = getText(R.string.spendable)
        }

        addressTextView.setOnClickListener { event: View? ->
            MaterialAlertDialogBuilder(this@UtxoDetailsActivity)
                .setTitle(R.string.app_name)
                .setMessage(R.string.receive_address_to_clipboard)
                .setCancelable(false)
                .setPositiveButton(R.string.yes) { dialog, whichButton ->
                    val clipboard =
                        this@UtxoDetailsActivity.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    var clip: ClipData? = null
                    clip = ClipData.newPlainText("address", address)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(
                        this@UtxoDetailsActivity,
                        R.string.copied_to_clipboard,
                        Toast.LENGTH_SHORT
                    )
                        .show()
                }.setNegativeButton(R.string.no) { dialog, whichButton -> }.show()
        }

        hashTextView.setOnClickListener { view: View? ->
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.app_name)
                .setMessage(R.string.txid_to_clipboard)
                .setCancelable(false)
                .setPositiveButton(R.string.yes) { dialog, whichButton ->
                    val clipboard =
                        this@UtxoDetailsActivity.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    val clip: ClipData
                    clip = ClipData.newPlainText("tx id", idx)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(
                        this@UtxoDetailsActivity,
                        R.string.copied_to_clipboard,
                        Toast.LENGTH_SHORT
                    )
                        .show()
                }.setNegativeButton(R.string.no) { dialog, whichButton -> }.show()
        }
    }

    /**
     * The row on screen is the composed label. A Bad Bank coin with
     * no user part still reads "Bad Bank", from the flag or from the
     * account on the coin's pub.
     */
    private fun renderCoinLabel() {
        coinMarked = CoinLabel.isMarked(labelRow?.badbank ?: 0, coinPubKey)
        val shown = CoinLabel.display(labelRow?.label, coinMarked)?.takeIf { it.isNotBlank() }
        currentLabelText = shown
        if (!::binding.isInitialized) return
        binding.utxoDetailsLabel.text = shown ?: getString(R.string.utxo_label_empty_hint)
    }

    private fun showLabelDialog(txid: String, vout: Int) {
        val marked = coinMarked
        val prefill = if (marked) CoinLabel.userPart(storedLabel) else (currentLabelText ?: "")
        val input = EditText(this).apply {
            hint = if (marked) {
                getString(R.string.utxo_label_badbank_hint)
            } else {
                getString(R.string.utxo_label_input_hint)
            }
            setText(prefill)
            setSingleLine()
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.utxo_label_dialog_title))
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString()
                // Unchanged text must not replace an auto origin with
                // a manual one. That stops phase-1 Bad Bank propagation
                // on a coin that is not yet flagged. A Bad Bank coin
                // compares user parts, so "Bad Bank" or the composed
                // suffix over an unchanged user part does not write.
                if (LabelEdit.isNoOp(text, storedLabel, storedOrigin, currentLabelText, marked)) {
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    labelRepository.setLabel(txid, vout, text, markBadbank = marked)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun isBlocked(): Boolean {
        val hash = idx!!.split(":")[0]
        val outN = idx!!.split(":")[1]
        return UtxoMetaUtil.has(hash, outN.toInt())
    }

}
