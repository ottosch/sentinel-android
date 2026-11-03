package com.samourai.sentinel.ui.collectionDetails.send

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.PopupMenu
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.viewModelScope
import androidx.transition.TransitionManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.transition.MaterialContainerTransform
import com.samourai.sentinel.R
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.repository.ExchangeRateRepository
import com.samourai.sentinel.data.repository.FeeRepository
import com.samourai.sentinel.data.repository.collapsedNormalPerVb
import com.samourai.sentinel.databinding.FragmentSpendBinding
import com.samourai.sentinel.send.SuggestedFee
import com.samourai.sentinel.ui.SentinelActivity
import com.samourai.sentinel.ui.broadcast.BroadcastTx
import com.samourai.sentinel.ui.fragments.AddNewPubKeyBottomSheet
import com.samourai.sentinel.ui.utils.AndroidUtil
import com.samourai.sentinel.ui.utils.PermissionResult
import com.samourai.sentinel.ui.utils.permissionResultOf
import com.samourai.sentinel.ui.utils.hideKeyboard
import com.samourai.sentinel.ui.views.codeScanner.CameraFragmentBottomSheet
import android.os.SystemClock
import com.samourai.sentinel.ui.utils.shouldAdmitScan
import com.samourai.sentinel.ui.utils.showFloatingSnackBar
import com.samourai.sentinel.ui.utils.startDocumentPickerForResult
import com.samourai.sentinel.util.FormatsUtil
import com.samourai.sentinel.util.MonetaryUtil
import com.samourai.sentinel.util.figuresFor
import com.samourai.wallet.util.FormatsUtilGeneric
import com.samourai.wallet.util.XPUB
import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.registry.RegistryType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bouncycastle.util.encoders.Hex
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import java.io.File
import java.math.BigInteger
import java.text.DecimalFormat
import java.util.*

class SendFragment : Fragment() {

    private var isBTCEditing = false
    private var isFiatEditing = false
    private var amount: Double = 0.0
    private var rate: ExchangeRateRepository.Rate = ExchangeRateRepository.Rate("", 0)
    private val exchangeRateRepository: ExchangeRateRepository by inject(ExchangeRateRepository::class.java)
    private var address: String = ""
    private val feeRepository: FeeRepository by inject(FeeRepository::class.java)
    private var feeLow: Long = 0L
    private var feeMed: Long = 0L
    private var feeHigh: Long = 0L
    var mCollection: PubKeyCollection? = null
    private val decimalFormat = DecimalFormat("##.00")
    // #77: the typed fee rate (and freshness note) on the fee selector.
    private var customFeeControl: CustomFeeControl? = null
    private var qrCodeString: String? = null
    private val decimalFormatBTC = DecimalFormat("#")
    private var _fragmentSpendBinding: FragmentSpendBinding? = null
    private val fragmentSpendBinding get() = _fragmentSpendBinding!!
    private var indexPubSelector = -1
    val viewModel: SendViewModel by viewModels()
    private var availableBalance: Long = 0L
    // #190: the account's spendable figure; availableBalance is this, or the
    // pinned coins' total while a manual selection is in force.
    private var accountSpendable: Long = 0L

    // #350: the shared scan-gate state, the #247/#248 shape - payload and
    // clock stamp of the last admitted decode (valid or not). The sheet's
    // decode callback re-fires while the same QR sits in frame; the gate
    // keeps a repeat inside the window out of the listeners below.
    private var lastDecodePayload: String? = null
    private var lastDecodeAtMs = 0L

    private val HARDENED = 2147483648
    private val PREMIX_ACC = 2147483645L

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        _fragmentSpendBinding = FragmentSpendBinding.inflate(inflater, container, false)
        val view = fragmentSpendBinding.root
        return view
    }

    override fun onResume() {
        if (indexPubSelector != -1 && indexPubSelector != 0)
            setBalance(mCollection!!.pubs[indexPubSelector-1])
        else
            setBalance(mCollection!!.pubs[findFirstNonAddressPubkey()-1])
        super.onResume()
    }

    fun setIndexPubSelector(index: Int) {
        indexPubSelector = index
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        parentFragmentManager.setFragmentResultListener(REQUEST_SCAN_PAYMENT, viewLifecycleOwner) { _, bundle ->
            val decoded = bundle.getString(CameraFragmentBottomSheet.RESULT_TEXT) ?: return@setFragmentResultListener

            // #350: the #247/#248 scan gate at the consumer. The sheet's
            // decode callback re-fires while the same QR sits in frame, and the
            // scan-time advisory below would toast on every frame without it.
            val now = SystemClock.elapsedRealtime()
            if (!shouldAdmitScan(lastDecodePayload, lastDecodeAtMs, decoded, now)) {
                return@setFragmentResultListener
            }
            lastDecodePayload = decoded
            lastDecodeAtMs = now
            if (FormatsUtilGeneric.getInstance().isBitcoinUri(decoded)) {
                // #350: the amount can be absent (bech32 fallback, no ?amount=) -
                // null-safe above. The verdict is on the extracted address.
                val scannedAddress = FormatsUtilGeneric.getInstance().getBitcoinAddress(decoded)
                fragmentSpendBinding.btcAddress.setText(scannedAddress)
                adviseScannedPayment(scannedAddress)
                val satsAmount = FormatsUtilGeneric.getInstance().getBitcoinAmount(decoded)?.toDoubleOrNull() ?: 0.0
                fragmentSpendBinding.btcEditText.setText(MonetaryUtil.getInstance().getBTCDecimalFormat(satsAmount.toLong()))
            } else if (decoded.length < 100) {
                fragmentSpendBinding.btcAddress.setText(decoded)
                adviseScannedPayment(decoded)
            }
            (parentFragmentManager.findFragmentByTag(TAG_SCAN) as? CameraFragmentBottomSheet)?.dismiss()
        }
        parentFragmentManager.setFragmentResultListener(REQUEST_SCAN_ADDRESS, viewLifecycleOwner) { _, bundle ->
            val decoded = bundle.getString(CameraFragmentBottomSheet.RESULT_TEXT) ?: return@setFragmentResultListener

            // #350: the #247/#248 scan gate at the consumer. The sheet's
            // decode callback re-fires while the same QR sits in frame, and the
            // scan-time advisory below would toast on every frame without it.
            val now = SystemClock.elapsedRealtime()
            if (!shouldAdmitScan(lastDecodePayload, lastDecodeAtMs, decoded, now)) {
                return@setFragmentResultListener
            }
            lastDecodePayload = decoded
            lastDecodeAtMs = now
            if (decoded.length < 100) {
                fragmentSpendBinding.btcAddress.setText(decoded)
                adviseScannedPayment(decoded)
            }
            (parentFragmentManager.findFragmentByTag(TAG_SCAN) as? CameraFragmentBottomSheet)?.dismiss()
        }

        decimalFormatBTC.minimumIntegerDigits = 1
        decimalFormatBTC.minimumFractionDigits = 8
        decimalFormatBTC.maximumFractionDigits = 8

        exchangeRateRepository.getRateLive().observe(this.viewLifecycleOwner) {
            rate = it
            DecimalFormat.getNumberInstance().currency = Currency.getInstance(rate.currency)
        }

        fragmentSpendBinding.fragmentComposeTx.totalBTC.setOnClickListener {
            viewModel.setAmount(availableBalance.div(1e8))
            setBtcEdit(decimalFormatBTC.format(availableBalance.div(1e8)).toString())
            val fiatRate = availableBalance.div(1e8).times(rate.rate)
            setFiatEdit(DecimalFormat.getNumberInstance().format(fiatRate))
        }

        fragmentSpendBinding.fragmentComposeTx.textView12.setOnClickListener {
            viewModel.setAmount(availableBalance.div(1e8))
            setBtcEdit(decimalFormatBTC.format(availableBalance.div(1e8)).toString())
            val fiatRate = availableBalance.div(1e8).times(rate.rate)
            setFiatEdit(DecimalFormat.getNumberInstance().format(fiatRate))
        }

        watchAddressAndAmount()

        fragmentSpendBinding.toEditText.setEndIconOnClickListener {
            val string = AndroidUtil.getClipBoardString(requireContext())
            fragmentSpendBinding.btcAddress.setText(string)
        }

        fragmentSpendBinding.composeBtn.setOnClickListener {
            containerTransform(fragmentSpendBinding.fragmentBroadcastTx.unsignedTxView, fragmentSpendBinding.composeBtn)
        }

        setBalance(mCollection!!.pubs[findFirstNonAddressPubkey()-1])

        if (isAdded) {
            setPubKeySelector()
            if (indexPubSelector != -1) {
                setDropDownPub(indexPubSelector)
            }
        }


        setUpFee()

        fragmentSpendBinding.fragmentBroadcastTx.broadCastTransactionBtn.setOnClickListener {
            startActivity(Intent(context, BroadcastTx::class.java))
        }

        viewModel.psbtLive.observe(viewLifecycleOwner) {
            generateQRCode(it)
            qrCodeString = it
            fragmentSpendBinding.fragmentBroadcastTx.psbtText.text = it
            fragmentSpendBinding.fragmentBroadcastTx.psbtText.movementMethod = ScrollingMovementMethod()
        }

        viewModel.validSpend.observe(viewLifecycleOwner) {
            fragmentSpendBinding.composeBtn.isEnabled = it
            fragmentSpendBinding.composeBtn.alpha = if (it) 1f else 0.6f
        }

        // #294: say why the button is disabled, on the field responsible.
        viewModel.spendBlocker.observe(viewLifecycleOwner) { showSpendBlocker(it) }

        setUpCoinControl()

        fragmentSpendBinding.fragmentBroadcastTx.psbtCopyBtn.setOnClickListener {
            val psbt = viewModel.psbtLive.value
            if (psbt?.length == 0) {
                return@setOnClickListener
            }
            val cm = context?.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
            val clipData = ClipData
                    .newPlainText("PSBT", psbt)
            if (cm != null) {
                cm.setPrimaryClip(clipData)
                Toast.makeText(context, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT)
                        .show()
            }
        }

        viewModel.minerFee.observe(viewLifecycleOwner) {
            fragmentSpendBinding.fragmentComposeTx.feeSelector.totalMinerFee.text = it
        }

        fragmentSpendBinding.fragmentBroadcastTx.psbtShareBtn.setOnClickListener { view1 ->
            val popup = PopupMenu(requireContext(), view1)
            popup.menuInflater.inflate(R.menu.send_share_menu, popup.menu)
            popup.setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.menu_share_as_text -> {
                        val psbtString = viewModel.psbtLive.value
                        val sendIntent: Intent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, psbtString)
                            type = "text/plain"
                        }
                        val shareIntent = Intent.createChooser(sendIntent, null)
                        startActivity(shareIntent)
                    }
                    R.id.menu_save_as_psbt -> {
                        sharePSBTFile()
                    }
                    R.id.menu_share_psbt -> {
                        val psbtBytes: ByteArray? = viewModel.getPsbtBytes()
                        val psbtFile = File(requireContext().cacheDir, "PSBT_transaction.psbt")
                        psbtFile.writeBytes(psbtBytes!!)

                        val uri = FileProvider.getUriForFile(
                            requireContext(),
                            "${requireContext().packageName}.provider",
                            psbtFile
                        )

                        val sendIntent: Intent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_STREAM, uri)
                            type = "application/octet-stream"
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }

                        val shareIntent = Intent.createChooser(sendIntent, null)
                        startActivity(shareIntent)
                    }
                    R.id.menu_save_as_text_file -> {
                        saveTxtFile()
                    }
                }
                true
            }
            popup.show()
        }

        fragmentSpendBinding.composeBtn.setOnClickListener {
            it.hideKeyboard()
            if (viewModel.makeTx()) {
                requireActivity().window.statusBarColor = ContextCompat.getColor(requireContext(), R.color.v3_accent)
                containerTransform(fragmentSpendBinding.fragmentBroadcastTx.unsignedTxView, fragmentSpendBinding.composeBtn)
            }
        }

        setUpToolbar()
    }

    /**
     * #350: the scan-time verdict - the moment of scan says something.
     * The #334 inline field errors still land afterwards off the
     * watcher, and manual typing keeps its own toast; what was missing
     * is the advisory AT scan: invalid payloads named at the moment of
     * scan, and a well-formed address for the OTHER network named too -
     * FormatsUtil.isValidBitcoinAddress decodes bech32 without comparing
     * the HRP to the active network, so a mainnet bc1 scans as valid on
     * a testnet rig and the watcher stays silent (#294 classifier here).
     */
    private fun adviseScannedPayment(scanned: String?) {
        if (scanned == null || scanned.isBlank() ||
            !FormatsUtil.isValidBitcoinAddress(scanned)
        ) {
            Toast.makeText(requireContext(), getString(R.string.send_error_invalid_address), Toast.LENGTH_LONG).show()
            return
        }
        if (viewModel.isWrongNetworkAddress(scanned)) {
            Toast.makeText(requireContext(), getString(R.string.send_error_wrong_network), Toast.LENGTH_LONG).show()
        }
    }

    private fun setUpToolbar() {
        fragmentSpendBinding.fragmentBroadcastTx.unsignedTxToolbar.setNavigationOnClickListener {
            containerTransform(fragmentSpendBinding.composeBtn, fragmentSpendBinding.fragmentBroadcastTx.unsignedTxView)
        }
        fragmentSpendBinding.sendAppBar.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.mpm_black))
        (activity as SentinelActivity).window.navigationBarColor = ContextCompat.getColor(requireContext(), R.color.grey_homeActivity)
        fragmentSpendBinding.sendAppBar.setNavigationOnClickListener {
            requireActivity().onBackPressed()
        }

        fragmentSpendBinding.sendAppBar.setOnMenuItemClickListener { menu ->
            if (menu.itemId == R.id.action_scan_qr) {
                if (!AndroidUtil.isPermissionGranted(
                        Manifest.permission.CAMERA,
                        requireContext()
                    )
                ) {
                    this.askCameraPermission()
                } else {
                    CameraFragmentBottomSheet.newInstance(REQUEST_SCAN_PAYMENT)
                        .show(parentFragmentManager, TAG_SCAN)
                }
            }
            true
        }
    }

    protected fun askCameraPermission() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(resources.getString(R.string.permission_alert_dialog_title_camera))
            .setMessage(resources.getString(R.string.permission_dialog_message_camera))
            .setNegativeButton(resources.getString(R.string.no)) { _, _ ->
                val bottomSheetFragment = AddNewPubKeyBottomSheet()
                bottomSheetFragment.show(childFragmentManager, bottomSheetFragment.tag)
            }
            .setPositiveButton(resources.getString(R.string.yes)) { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    requestPermissions(arrayOf(Manifest.permission.CAMERA),
                        SentinelActivity.CAMERA_PERMISSION
                    )
                }
            }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        // NOTE: grantResults can be EMPTY when the dialog is cancelled - never index it directly.
        if (requestCode != SentinelActivity.CAMERA_PERMISSION) return
        when (permissionResultOf(grantResults)) {
            PermissionResult.GRANTED -> showAddressScanner()
            PermissionResult.DENIED -> {
                Toast.makeText(context, "Permission denied", Toast.LENGTH_LONG).show()
                showAddressScanner()
            }
            PermissionResult.CANCELLED -> Unit
        }
    }

    private fun showAddressScanner() {
        // Guard against the fragment being detached while the permission dialog was up.
        if (!isAdded) return
        CameraFragmentBottomSheet.newInstance(REQUEST_SCAN_ADDRESS)
            .show(parentFragmentManager, TAG_SCAN)
    }


    fun getPsbtBytes() {
        viewModel.getPsbtBytes()
    }

    private fun sharePSBTFile() {
        viewModel.getPsbtBytes() ?: return
        val fileUUID = UUID.randomUUID()
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_TITLE, "${fileUUID}.psbt")
        }
        startDocumentPickerForResult(intent, REQ_CODE_SAVE_PSBT)
    }

    private fun saveTxtFile() {
        viewModel.psbtLive.value ?: return
        val fileUUID = UUID.randomUUID()
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain" // Set the MIME type for text files
            putExtra(Intent.EXTRA_TITLE, "${fileUUID}.txt")
        }
        startDocumentPickerForResult(intent, REQ_CODE_SAVE_TXT)
    }

    /**
     * #361: the two save doors' results, consumed by the fragment that
     * launched them - the #362 fragment-side shape. Content is sourced
     * from the viewModel at result time, as the old activity handlers did.
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CODE_SAVE_PSBT && resultCode == Activity.RESULT_OK) {
            data?.data?.also { uri ->
                val fileContent = viewModel.getPsbtBytes()
                val outputStream = requireContext().contentResolver.openOutputStream(uri)
                outputStream?.write(fileContent)
                outputStream?.close()
                view?.let { parent ->
                    requireActivity().showFloatingSnackBar(parent, text = "File saved to ${uri.path}")
                }
            }
        }
        else if (requestCode == REQ_CODE_SAVE_TXT && resultCode == Activity.RESULT_OK) {
            data?.data?.also { uri ->
                val fileContent = viewModel.psbtLive.value
                val outputStream = requireContext().contentResolver.openOutputStream(uri)
                outputStream?.write(fileContent?.toByteArray())
                outputStream?.close()
                view?.let { parent ->
                    requireActivity().showFloatingSnackBar(parent, text = "Txt file saved to ${uri.path}")
                }
            }
        }
    }

    /**
     * #294: puts the reason composing is blocked on the field that caused it.
     *
     * An empty address or amount carries no message - the user has not
     * finished typing and does not need telling. Everything else names the
     * problem: a malformed address, an address for the other network, or an
     * amount beyond the spendable balance.
     */
    private fun showSpendBlocker(blocker: SendViewModel.SpendBlocker) {
        val addressError = when (blocker) {
            SendViewModel.SpendBlocker.INVALID_ADDRESS ->
                getString(R.string.send_error_invalid_address)
            SendViewModel.SpendBlocker.WRONG_NETWORK ->
                getString(R.string.send_error_wrong_network)
            else -> null
        }
        val amountError = when (blocker) {
            SendViewModel.SpendBlocker.NO_FUNDS ->
                getString(R.string.send_error_no_funds)
            SendViewModel.SpendBlocker.INSUFFICIENT_FUNDS ->
                getString(R.string.send_error_insufficient_funds)
            SendViewModel.SpendBlocker.CANNOT_COMPOSE ->
                getString(R.string.send_error_cannot_compose)
            SendViewModel.SpendBlocker.SELECTION_INSUFFICIENT ->
                getString(R.string.send_error_selection_insufficient)
            SendViewModel.SpendBlocker.SELECTION_BLOCKED ->
                getString(R.string.send_error_selection_blocked)
            SendViewModel.SpendBlocker.SELECTION_MISSING ->
                getString(R.string.send_error_selection_missing)
            else -> null
        }
        fragmentSpendBinding.toEditText.error = addressError
        fragmentSpendBinding.btcEditTextLayout.error = amountError
    }

    /**
     * #190: the coin control affordance. "Automatic" stays the default; the
     * picker pins an exact input set into the ViewModel, and this row shows
     * which mode the next compose will use. While coins are pinned, the
     * available figure (and the tap-to-spend-all on it) is their total, so
     * "spend all" spends exactly the pinned coins.
     */
    private fun setUpCoinControl() {
        val compose = fragmentSpendBinding.fragmentComposeTx
        val openPicker = View.OnClickListener {
            if (childFragmentManager.findFragmentByTag(CoinPickerBottomSheet.TAG) == null) {
                CoinPickerBottomSheet().show(childFragmentManager, CoinPickerBottomSheet.TAG)
            }
        }
        compose.coinControlBtn.setOnClickListener(openPicker)
        compose.coinControlRow.setOnClickListener(openPicker)

        viewModel.pinnedTotal.observe(viewLifecycleOwner) { renderCoinControl() }
        viewModel.pinnedOutpoints.observe(viewLifecycleOwner) { renderCoinControl() }
    }

    private fun renderCoinControl() {
        if (_fragmentSpendBinding == null) return
        val pinned = viewModel.pinnedOutpoints.value ?: emptySet()
        val pinnedTotal = viewModel.pinnedTotal.value
        fragmentSpendBinding.fragmentComposeTx.coinControlMode.text =
            if (pinned.isEmpty()) {
                getString(R.string.coin_picker_mode_auto)
            } else {
                getString(
                    R.string.coin_picker_mode_pinned,
                    pinned.size,
                    MonetaryUtil.getInstance().getBTCDecimalFormat(pinnedTotal ?: 0L)
                )
            }
        showAvailable()
    }

    private fun showAvailable() {
        val pinnedTotal = viewModel.pinnedTotal.value
        val pinned = !viewModel.pinnedOutpoints.value.isNullOrEmpty()
        availableBalance = if (pinned && pinnedTotal != null) pinnedTotal else accountSpendable
        fragmentSpendBinding.fragmentComposeTx.totalBTC.text =
            decimalFormatBTC.format(availableBalance.div(1e8)).toString() + " BTC"
    }

    private fun containerTransform(enter: View, leaving: View) {
        val transform = MaterialContainerTransform()
        transform.scrimColor = Color.TRANSPARENT
        transform.drawingViewId = R.id.sendRootLayout

        val transition: MaterialContainerTransform = transform
        transition.startView = leaving
        transition.duration = 400
        transition.endView = enter

        transition.addTarget(enter)
        TransitionManager.beginDelayedTransition(view as ViewGroup, transition)
        enter.visibility = VISIBLE
        leaving.visibility = GONE
        if (leaving.id == fragmentSpendBinding.fragmentBroadcastTx.unsignedTxView.id) {
            requireActivity().window.statusBarColor =
                    ContextCompat.getColor(requireContext(), android.R.color.transparent)
        } else {
            requireActivity().window.statusBarColor = ContextCompat.getColor(requireContext(), R.color.v3_accent)
        }
    }

    private fun watchAddressAndAmount() {
        fragmentSpendBinding.fiatEditTextLayout.hint = exchangeRateRepository.getRateLive().value?.currency
        fragmentSpendBinding.fiatEditText.addTextChangedListener { onFiatValueChange(it.toString()) }
        fragmentSpendBinding.btcEditText.addTextChangedListener { onBtcValueChanged(it.toString()) }
        fragmentSpendBinding.btcAddress.addTextChangedListener {
            if (it.toString().isNotEmpty()) {
                address = it.toString().trim()
                if (FormatsUtil.isValidBitcoinAddress(address)) {
                    viewModel.setDestinationAddress(address)
                } else {
                    Toast.makeText(requireContext(), "Invalid address", Toast.LENGTH_SHORT).show()
                    fragmentSpendBinding.composeBtn.alpha = 0.6f
                    fragmentSpendBinding.composeBtn.isEnabled = false
                }
            } else {
                viewModel.setDestinationAddress("")
            }
        }
    }

    private fun onBtcValueChanged(btcString: String) {
        if (isBTCEditing) {
            return
        }
        try {
            if (btcString.isEmpty()) {
                setBtcEdit("")
                setFiatEdit("")
            }
            if (btcString.split(".").size > 1 && btcString.split(".")[1].length > 8) {
                setBtcEdit(btcString.dropLast(btcString.split(".")[1].length-8))
                return
            }
            val btc: Double = btcString.toDouble()
            amount = btc
            if (btc > 21000000.0) {
                setFiatEdit("")
                setBtcEdit("")
                return
            }
            if (amount <= 0) {
                fragmentSpendBinding.composeBtn.alpha = 0.6f
                fragmentSpendBinding.composeBtn.isEnabled = false
                return
            }
            val fiatRate = btc.times(rate.rate)
            setFiatEdit(DecimalFormat.getNumberInstance().format(fiatRate))
            viewModel.setAmount(amount)
            print("Setting amount: " + amount)
        } catch (Ex: Exception) {

        }
    }

    private fun onFiatValueChange(fiatString: String) {
        val ungroupedFiat = fiatString.replace((DecimalFormat.getInstance() as DecimalFormat).decimalFormatSymbols.groupingSeparator.toString(), "")
        if (isFiatEditing) {
            return
        }
        if (ungroupedFiat.contains(".") && ungroupedFiat.split(".")[1].length > 2) {
            setFiatEdit(ungroupedFiat.dropLast(1))
            return
        }
        try {
            if (ungroupedFiat.isEmpty()) {
                setBtcEdit("")
                setFiatEdit("")
                fragmentSpendBinding.composeBtn.alpha = 0.6f
                fragmentSpendBinding.composeBtn.isEnabled = false
            }
            val fiat: Double = ungroupedFiat.toDouble()
            val btcRate = (1 / rate.rate.toFloat()) * fiat.toFloat()
            if (btcRate > 21000000.0) {
                setBtcEdit("")
                setFiatEdit("")
                return
            }

            if (ungroupedFiat.get(ungroupedFiat.lastIndex).equals('.'))
                setFiatEdit(DecimalFormat.getNumberInstance().format(fiat) + ".")
            else
                setFiatEdit(DecimalFormat.getNumberInstance().format(fiat))

            setBtcEdit(MonetaryUtil.getInstance().formatToBtc((btcRate * 1e8).toLong()))
            val btc: Double = MonetaryUtil.getInstance().formatToBtc((btcRate * 1e8).toLong()).toDouble()
            amount = btc
            viewModel.setAmount(amount)
            print("Setting amount: " + amount)
        } catch (Ex: Exception) {

        }
    }

    private fun setBtcEdit(value: String) {
        isBTCEditing = true
        fragmentSpendBinding.btcEditText.setText(value)
        fragmentSpendBinding.btcEditText.text?.length?.let { fragmentSpendBinding.btcEditText.setSelection(it) }
        isBTCEditing = false
    }

    private fun setFiatEdit(value: String) {
        isFiatEditing = true
        fragmentSpendBinding.fiatEditText.setText(value)
        fragmentSpendBinding.fiatEditText.text?.length?.let { fragmentSpendBinding.fiatEditText.setSelection(it) }
        isFiatEditing = false
    }


    fun onBackPressed(): Boolean {
        if (fragmentSpendBinding.fragmentBroadcastTx.unsignedTxView.visibility == VISIBLE) {
            containerTransform(fragmentSpendBinding.composeBtn, fragmentSpendBinding.fragmentBroadcastTx.unsignedTxView)
            return false
        }
        Timber.i("onBackPressed: ")
        return true
    }

    fun setCollection(mCollection: PubKeyCollection) {
        this.mCollection = mCollection
    }

    fun setDropDownPub(index: Int) {
        decimalFormatBTC.minimumIntegerDigits = 1
        decimalFormatBTC.minimumFractionDigits = 8
        decimalFormatBTC.maximumFractionDigits = 8

        if (mCollection == null || index <= 0 || !isAdded) {
            indexPubSelector = index
            return
        }

        val newIndex = if (mCollection!!.pubs[index-1].type != AddressTypes.ADDRESS) index else findFirstNonAddressPubkey()

        mCollection?.let { pubKeySelector ->
            val items = pubKeySelector.pubs.filter {
                isPubkeyAddressOrPremix(it)
            }.map { it.label }.toMutableList()

            val adapter: ArrayAdapter<String> = ArrayAdapter(
                requireContext(),
                R.layout.dropdown_menu_popup_item, items
            )
            fragmentSpendBinding.fragmentComposeTx.pubKeySelector.inputType = InputType.TYPE_NULL
            fragmentSpendBinding.fragmentComposeTx.pubKeySelector.threshold = items.size
            fragmentSpendBinding.fragmentComposeTx.pubKeySelector.setAdapter(adapter)
            val selectPubKeyModel = pubKeySelector.pubs[newIndex-1]
            viewModel.setPublicKey(selectPubKeyModel, viewLifecycleOwner, mCollection!!)
            setBalance(mCollection!!.pubs[newIndex-1])
            fragmentSpendBinding.fragmentComposeTx.pubKeySelector.setText(mCollection!!.pubs.get(newIndex-1).label, false)

            view?.isEnabled = true
            view?.alpha = 1f
            fragmentSpendBinding.composeBtn.isEnabled = true
            }
    }

    private fun isPubkeyAddressOrPremix(it: PubKeyModel): Boolean {
        //Also has to filter if collection is imported via Samourai Wallet pairing menu

        if (mCollection!!.isImportFromWallet) {
            if (it.label.equals("Deposit BIP49") || it.label.equals("Deposit BIP44"))
                return false
            if (it.label.equals("Deposit BIP84"))
                it.label = "Deposit"
        }

        if (it.type == AddressTypes.ADDRESS)
            return false

        val xpub = XPUB(it.pubKey)
        xpub.decode()
        val account = xpub.child + HARDENED
        if (account == PREMIX_ACC)
            return false

        return true
    }

    private fun findFirstNonAddressPubkey(): Int {
        //Pubkey must also NOT be Premix
        mCollection!!.pubs.forEach {
            if (it.type != AddressTypes.ADDRESS) {
                val xpub = XPUB(it.pubKey)
                xpub.decode()
                val account = xpub.child + HARDENED
                if (account != PREMIX_ACC)
                    return mCollection!!.pubs.indexOf(it)+1
            }
        }
        return 1
    }

    private fun setPubKeySelector() {
        decimalFormatBTC.minimumIntegerDigits = 1
        decimalFormatBTC.minimumFractionDigits = 8
        decimalFormatBTC.maximumFractionDigits = 8

        if (mCollection == null) {
            return
        }
        mCollection?.let { pubKeySelector ->
            val items = pubKeySelector.pubs.filter { isPubkeyAddressOrPremix(it) }.map { it.label }.toMutableList()

            val adapter: ArrayAdapter<String> = ArrayAdapter(
                    requireContext(),
                    R.layout.dropdown_menu_popup_item, items
            )
            fragmentSpendBinding.fragmentComposeTx.pubKeySelector.inputType = InputType.TYPE_NULL
            fragmentSpendBinding.fragmentComposeTx.pubKeySelector.threshold = items.size
            fragmentSpendBinding.fragmentComposeTx.pubKeySelector.setAdapter(adapter)
            fragmentSpendBinding.fragmentComposeTx.pubKeySelector.onItemClickListener = AdapterView.OnItemClickListener { _, _, index, _ ->
                val selectPubKeyModel = getPubKeyModelByLabel(items[index])
                viewModel.setPublicKey(selectPubKeyModel, viewLifecycleOwner, mCollection!!)

                setBalance(getPubKeyModelByLabel(items[index]))

                view?.isEnabled = true
                view?.alpha = 1f
                fragmentSpendBinding.composeBtn.isEnabled = true
            }
            if (items.size == 0) {
                return
            }
            fragmentSpendBinding.fragmentComposeTx.pubKeySelector.setText(items.first(), false)
            viewModel.setPublicKey(getPubKeyModelByLabel(items[0]), viewLifecycleOwner,
                mCollection!!
            )
        }
    }

    private fun getPubKeyModelByLabel(label: String): PubKeyModel {
        mCollection?.pubs?.forEach {
            if (it.label == label)
                return it
        }
        return mCollection!!.pubs[0]
    }

    /**
     * #301: the compose screen's available figure from the one
     * derivation (SpendableBalance.kt) - previously two more inline
     * subtractions. The spend LOGIC side (SendViewModel.spendableSats
     * and the compose input filter) already excluded blocked coins and
     * is untouched; this is the displayed figure only.
     */
    private fun setBalance(pub: PubKeyModel) {

        if (mCollection!!.isImportFromWallet && pub.label == "Deposit") {
            // #301: order-independent sum - base's two lists used two
            // different keychain orders (84/44/49 vs 84/49/44).
            val depositPubs = listOf(
                getPubKeyModelByLabel("Deposit BIP84"),
                getPubKeyModelByLabel("Deposit BIP49"),
                getPubKeyModelByLabel("Deposit BIP44")
            )
            val figures = figuresFor(depositPubs)
            accountSpendable = figures.spendable
        } else {
            val figures = figuresFor(pub)
            accountSpendable = figures.spendable
        }
        showAvailable()
    }

    private fun setUpFee() {
        val multiplier = FEE_SLIDER_MULTIPLIER
//        FEE_TYPE = PrefsUtil.getInstance(this).getValue(PrefsUtil.CURRENT_FEE_TYPE, SendActivity.FEE_NORMAL)
        feeLow = feeRepository.getLowFee().defaultPerKB.toLong() / 1000L
        feeMed = feeRepository.getNormalFee().defaultPerKB.toLong() / 1000L
        feeHigh = feeRepository.getHighFee().defaultPerKB.toLong() / 1000L

        val high = feeHigh / 2 + feeHigh
        val feeHighSliderValue = (high * multiplier)
        val feeMedSliderValue = (feeMed * multiplier)
        val valueTo = (feeHighSliderValue - multiplier).toFloat()
        fragmentSpendBinding.fragmentComposeTx.feeSelector.feeSlider.stepSize = 1F
        // #348: a high tier at or near 0 collapses the fallback ceiling
        // to 0 or below, which the slider rejects outright (valueFrom 1
        // must stay below valueTo). Floor the ceiling so every payload
        // yields a usable range; the duplicate valueTo write is folded
        // into this one.
        val sliderCeiling =
            (if (valueTo <= 0) feeHighSliderValue.toFloat() else valueTo).coerceAtLeast(2F)
        fragmentSpendBinding.fragmentComposeTx.feeSelector.feeSlider.valueTo = sliderCeiling
        fragmentSpendBinding.fragmentComposeTx.feeSelector.feeSlider.valueFrom = 1F
        fragmentSpendBinding.fragmentComposeTx.feeSelector.feeSlider.setLabelFormatter { i: Float ->
            val value = (i + multiplier) / multiplier
            val formatted = "${decimalFormat.format(value)} sat/vB"
            fragmentSpendBinding.fragmentComposeTx.feeSelector.selectedFeeRate.text = formatted
            formatted
        }
        if (feeLow == feeMed && feeMed == feeHigh) {
            feeLow = (feeMed.toDouble() * 0.85).toLong()
            feeHigh = (feeMed.toDouble() * 1.15).toLong()
            val loSf = SuggestedFee()
            loSf.defaultPerKB = BigInteger.valueOf(feeLow * 1000L)
            feeRepository.setLowFee(loSf)
            val hiSf = SuggestedFee()
            hiSf.defaultPerKB = BigInteger.valueOf(feeHigh * 1000L)
            feeRepository.setHighFee(hiSf)
        } else if (feeLow == feeMed || feeMed == feeHigh) {
            // #305: the old check compared a tier to itself, so it always
            // ran, and the stored per-KB value was High. Normal is the average.
            feeMed = collapsedNormalPerVb(feeLow, feeMed, feeHigh)
            val miSf = SuggestedFee()
            miSf.defaultPerKB = BigInteger.valueOf(feeMed * 1000L)
            feeRepository.setNormalFee(miSf)
        }
        if (feeLow < 1L) {
            feeLow = 1L
            val loSf = SuggestedFee()
            loSf.defaultPerKB = BigInteger.valueOf(feeLow * 1000L)
            feeRepository.setLowFee(loSf)
        }
        if (feeMed < 1L) {
            feeMed = 1L
            val miSf = SuggestedFee()
            miSf.defaultPerKB = BigInteger.valueOf(feeMed * 1000L)
            feeRepository.setNormalFee(miSf)
        }
        if (feeHigh < 1L) {
            feeHigh = 1L
            val hiSf = SuggestedFee()
            hiSf.defaultPerKB = BigInteger.valueOf(feeHigh * 1000L)
            feeRepository.setHighFee(hiSf)
        }
        fragmentSpendBinding.fragmentComposeTx.feeSelector.selectedFeeRateLayman.text = getString(R.string.normal)
        feeRepository.sanitizeFee()
        fragmentSpendBinding.fragmentComposeTx.feeSelector.selectedFeeRate.text = ("$feeMed sat/vB")
        // #348: ingestion rejects non-monotonic tiers, and this clamp is
        // the backstop: the payload {2:1,6:21,24:588077} produced a value
        // of 200001 above valueTo 10000 and killed the process at first
        // layout. No payload shape may crash the fee selector.
        fragmentSpendBinding.fragmentComposeTx.feeSelector.feeSlider.value =
            (feeMedSliderValue - multiplier + 1).toFloat().coerceIn(1F, sliderCeiling)
        setFeeLabels()
        viewModel.setFee(((fragmentSpendBinding.fragmentComposeTx.feeSelector.feeSlider.value + multiplier) / multiplier)*1000)
        val nbBlocks = 6
        fragmentSpendBinding.fragmentComposeTx.feeSelector.feeSlider.addOnChangeListener { slider, sliderVal, fromUser ->
            // #77: a typed rate owns the fee while Custom is on.
            if (customFeeControl?.active == true) return@addOnChangeListener
            val value = (sliderVal + multiplier) / multiplier
            showEstimatedBlocks(CustomFee.estimatedBlocks(value, feeLow, feeMed, feeHigh))
            setFeeLabels()
            viewModel.setFee(value * 1000)
        }
        fragmentSpendBinding.fragmentComposeTx.feeSelector.estBlockTime.text = "$nbBlocks blocks"
        setUpCustomFee()
    }

    private fun showEstimatedBlocks(blocks: Int) {
        fragmentSpendBinding.fragmentComposeTx.feeSelector.estBlockTime.text =
            CustomFeeControl.blocksText(blocks)
    }

    /**
     * #77: a typed fee rate and the estimate-freshness note (CustomFeeControl,
     * shared with Sweep). The slider stops at 1.5 x the High estimate, which
     * can be far below what a congested mempool needs - above all when the
     * estimates are stale or defaults (no Dojo, #76). While the field holds
     * no usable rate, composing is blocked rather than left on the previous
     * rate.
     */
    private fun setUpCustomFee() {
        val feeSelector = fragmentSpendBinding.fragmentComposeTx.feeSelector
        customFeeControl = CustomFeeControl(
            feeSelector = feeSelector,
            wholeOnly = false,
            tiers = { Triple(feeLow, feeMed, feeHigh) },
            sliderRate = { sliderRate() },
            onRate = { perKb -> viewModel.setFee(perKb) },
            onUnusable = { viewModel.setFeeInvalid() },
            onBackToSlider = {
                val value = sliderRate()
                feeSelector.selectedFeeRate.text = "${decimalFormat.format(value)} sat/vB"
                setFeeLabels()
                showEstimatedBlocks(CustomFee.estimatedBlocks(value, feeLow, feeMed, feeHigh))
                viewModel.setFee(value * 1000)
            },
        ).also {
            it.attach()
            it.showFreshness(feeRepository.freshness())
        }
        // A sync round that lands while Send is open refreshes the note.
        feeRepository.getFees().observe(viewLifecycleOwner) {
            customFeeControl?.showFreshness(feeRepository.freshness())
        }
    }

    /** The slider's rate in sat/vB, as its change listener computes it. */
    private fun sliderRate(): Float =
        (fragmentSpendBinding.fragmentComposeTx.feeSelector.feeSlider.value + FEE_SLIDER_MULTIPLIER) /
            FEE_SLIDER_MULTIPLIER

    private fun setFeeLabels() {
        if (fragmentSpendBinding.fragmentComposeTx.feeSelector.feeSlider.valueTo <= 0) {
            return
        }
        val sliderValue: Float = fragmentSpendBinding.fragmentComposeTx.feeSelector.feeSlider.value / fragmentSpendBinding.fragmentComposeTx.feeSelector.feeSlider.valueTo
        val sliderInPercentage = sliderValue * 100
        if (sliderInPercentage < 33) {
            fragmentSpendBinding.fragmentComposeTx.feeSelector.selectedFeeRateLayman.setText(R.string.low)
        } else if (sliderInPercentage > 33 && sliderInPercentage < 66) {
            fragmentSpendBinding.fragmentComposeTx.feeSelector.selectedFeeRateLayman.setText(R.string.normal)
        } else if (sliderInPercentage > 66) {
            fragmentSpendBinding.fragmentComposeTx.feeSelector.selectedFeeRateLayman.setText(R.string.urgent)
        }
    }

    private fun generateQRCode(uri: String) {
        viewModel.viewModelScope.launch(Dispatchers.Default) {
            withContext(Dispatchers.Main) {
                fragmentSpendBinding.fragmentBroadcastTx.psbtQRCode.maxFragmentLength = 40
                fragmentSpendBinding.fragmentBroadcastTx.psbtQRCode.fps = 5
                fragmentSpendBinding.fragmentBroadcastTx.psbtQRCode.setContent(UR.fromBytes(RegistryType.CRYPTO_PSBT.type,Hex.decode(uri)));
            }
        }
    }

    companion object {
        private const val REQUEST_SCAN_PAYMENT = "send_scan_payment"
        private const val REQUEST_SCAN_ADDRESS = "send_scan_address"
        private const val TAG_SCAN = "scan_address"
        private const val REQ_CODE_SAVE_PSBT = 1
        private const val REQ_CODE_SAVE_TXT = 2
        // The fee slider's scale: slider value = (sat/vB - 1) * this.
        private const val FEE_SLIDER_MULTIPLIER = 10000
    }
}
