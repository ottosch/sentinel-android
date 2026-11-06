package com.samourai.sentinel.ui.fragments

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.samourai.sentinel.R
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.data.repository.ExchangeRateRepository
import com.samourai.sentinel.data.repository.FeeRepository
import com.samourai.sentinel.data.repository.TransactionsRepository
import com.samourai.sentinel.databinding.FragmentBottomsheetViewPagerBinding
import com.samourai.sentinel.databinding.LayoutLoadingBottomBinding
import com.samourai.sentinel.ui.home.HomeViewModel
import com.samourai.sentinel.ui.utils.PairingWordsAsset
import com.samourai.sentinel.ui.utils.enablePasteAction
import com.samourai.sentinel.ui.utils.enableRevealToggle
import com.samourai.sentinel.ui.views.GenericBottomSheet
import com.samourai.sentinel.util.ExportImportUtil
import com.samourai.sentinel.util.PairingWords
import com.samourai.sentinel.util.pairingImportUserMessage
import com.samourai.sentinel.util.readSamouraiPairingPayloadTrying
import com.samourai.sentinel.util.apiScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent
import timber.log.Timber
import java.util.concurrent.CancellationException


class WalletPairingFragment : GenericBottomSheet {

    /** #216: restore uses the no-arg constructor. The payload is in arguments. */
    constructor() : super()

    constructor(payload: String, secure: Boolean = false) : super(secure) {
        arguments = (arguments ?: Bundle()).apply { putString(ARG_PAYLOAD, payload) }
    }

    private val payload: String
        get() = arguments?.getString(ARG_PAYLOAD).orEmpty()

    private var _binding: FragmentBottomsheetViewPagerBinding? = null
    private val binding get() = _binding!!
    private val passwordFragment = PasswordFragment()
    private val loadingFragment = LoadingFragment()
    private val successFragment = SuccessFragment()
    private val exchangeRateRepository: ExchangeRateRepository by KoinJavaComponent.inject(
        ExchangeRateRepository::class.java
    )
    private val repository: CollectionRepository by KoinJavaComponent.inject(CollectionRepository::class.java)
    private val transactionsRepository: TransactionsRepository by KoinJavaComponent.inject(
        TransactionsRepository::class.java
    )
    private var netWorkJobs: ArrayList<Job?> = arrayListOf()
    private val feeRepository: FeeRepository by KoinJavaComponent.inject(FeeRepository::class.java)


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBottomsheetViewPagerBinding.inflate(inflater, container, false)
        val view = binding.root
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setUpViewPager()

        livePassword().setPayload(payload.trim())
        livePassword().setOnSelectListener { collection, note ->
            liveLoading().setCollection(collection)
            liveSuccess().setNote(note)
            binding.pager.currentItem = 1
        }
        liveLoading().setSuccessCheck {
            apiScope.launch {
                try {
                    exchangeRateRepository.fetch()
                    repository.collectionsSnapshot().forEach {

                        val job = apiScope.launch {
                            try {
                                transactionsRepository.fetchFromServer(it)
                            } catch (e: Exception) {
                                Timber.e(e)
                                throw  CancellationException(e.message)
                            }
                        }
                        netWorkJobs.add(job)
                    }
                    val job = apiScope.launch {
                        try {
                            feeRepository.getDynamicFees()
                        } catch (e: Exception) {
                            Timber.e(e)
                            throw  CancellationException(e.message)
                        }
                    }
                    netWorkJobs.add(job)

                    if (netWorkJobs.isNotEmpty()) {
                        //Save last sync time to prefs
                        netWorkJobs[netWorkJobs.lastIndex]?.let {
                            it.invokeOnCompletion { error ->
                                if (error != null) {
                                    Timber.e(error)
                                    return@invokeOnCompletion
                                }
                            }
                        }
                    }
                } catch (ex: Exception) {
                    Timber.e(ex)
                }
                delay(3000)
            }.invokeOnCompletion { binding.pager.currentItem = 2 }
        }
    }

    private fun setUpViewPager() {

        val item = arrayListOf<Fragment>()
        item.add(passwordFragment)
        item.add(loadingFragment)
        item.add(successFragment)

        binding.pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int {
                return item.size
            }

            override fun createFragment(position: Int): Fragment {
                return item[position]
            }

        }
        binding.pager.isUserInputEnabled = false
    }

    /** #216: ViewPager2 restores pages under f0/f1. Those are not these fields. */
    private fun livePassword(): PasswordFragment =
        childFragmentManager.findFragmentByTag("f0") as? PasswordFragment ?: passwordFragment

    private fun liveLoading(): LoadingFragment =
        childFragmentManager.findFragmentByTag("f1") as? LoadingFragment ?: loadingFragment

    private fun liveSuccess(): SuccessFragment =
        childFragmentManager.findFragmentByTag("f2") as? SuccessFragment ?: successFragment

    companion object {
        private const val ARG_PAYLOAD = "wallet_pairing_arg_payload"
    }
}

/**
 * #282: the one-time pairing password page.
 *
 * The password is three words of the EFF large wordlist joined with "-"
 * (see [PairingWords]). The field can be revealed, pasted into and
 * autocompleted; spaces and capitals are turned into the canonical form
 * as the user types; a word that isn't on the list is named inline, and
 * Decrypt is enabled only for exactly three list words. Decryption (AES +
 * PBKDF2) runs off the main thread behind a progress bar. A wrong
 * password, a payload that is not JSON, an unrecognised shape, the
 * legacy wallet export, a newer Ashigaru version, and a payload with
 * no readable keys each keep their own sentence on the field. A
 * partial read says which slots were skipped. The typed text is kept.
 *
 * If the wordlist can't be read, none of the word checks apply and
 * Decrypt is enabled for any non-blank text, so a missing asset can't
 * lock anyone out. The password is never logged.
 */
class PasswordFragment : Fragment() {

    private lateinit var payload: String
    private var onSelect: (PubKeyCollection, String?) -> Unit = { _, _ -> }

    private var words: PairingWords? = null
    private var busy = false
    private var statusMessage: String? = null
    /** Set while this class rewrites the field, so its watcher ignores that edit. */
    private var shaping = false

    private var layout: TextInputLayout? = null
    private var input: TextInputEditText? = null
    private var suggestions: ChipGroup? = null
    private var progress: ProgressBar? = null
    private var decryptBtn: MaterialButton? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.layout_bottom_sheet, container)
        view.findViewById<TextView>(R.id.dialogTitle).text = getString(R.string.pairing_password_title)
        val inputContent = inflater.inflate(R.layout.content_pairing_password, null)
        val content = view.findViewById<FrameLayout>(R.id.contentContainer)
        content.addView(inputContent)
        return view
    }

    fun setOnSelectListener(callback: (PubKeyCollection, String?) -> Unit = { _, _ -> }) {
        this.onSelect = callback
    }

    fun setPayload(payload:String) {
        this.payload = payload
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        layout = view.findViewById(R.id.pairingPasswordLayout)
        input = view.findViewById(R.id.pairingPasswordInput)
        suggestions = view.findViewById(R.id.pairingPasswordSuggestions)
        progress = view.findViewById(R.id.pairingPasswordProgress)
        decryptBtn = view.findViewById(R.id.pairingPasswordDecryptBtn)
        val input = input ?: return

        // No length cap (#282): the old 34-char LengthFilter dropped input
        // silently; the word check below is the guard instead.
        input.filters = arrayOf()
        // Eye icon: hidden/shown also switches PASSWORD/VISIBLE_PASSWORD.
        layout?.enableRevealToggle(input)
        layout?.enablePasteAction(input)

        input.addTextChangedListener(object : TextWatcher {
            private var inserted = false

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                inserted = after > count
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (shaping || s == null) return
                statusMessage = null
                val list = words
                // Only on typing or pasting: a deletion is left exactly as
                // the user made it, or a removed "-" would come straight back.
                if (list != null && inserted) {
                    val current = s.toString()
                    val shaped = list.autoSeparate(current)
                    if (shaped != current) replaceText(shaped)
                }
                refresh()
            }
        })
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE && decryptBtn?.isEnabled == true) {
                decrypt()
                true
            } else {
                false
            }
        }
        decryptBtn?.setOnClickListener { decrypt() }

        refresh()
        viewLifecycleOwner.lifecycleScope.launch {
            words = PairingWordsAsset.load(requireContext())
            refresh()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        layout = null
        input = null
        suggestions = null
        progress = null
        decryptBtn = null
    }

    private fun replaceText(text: String) {
        val input = input ?: return
        shaping = true
        input.setText(text)
        input.setSelection(text.length)
        shaping = false
    }

    /** Re-derives the error, the suggestions and the button from the field. */
    private fun refresh() {
        val text = input?.text?.toString().orEmpty()
        val list = words
        val analysis = list?.analyze(text)

        layout?.error = when {
            statusMessage != null -> statusMessage
            analysis?.invalid != null -> getString(R.string.pairing_password_not_a_word, analysis.invalid)
            analysis?.tooMany == true -> getString(R.string.pairing_password_too_many)
            else -> null
        }

        val group = suggestions
        if (group != null) {
            group.removeAllViews()
            val options = if (list == null || busy) emptyList() else list.suggestions(text)
            for (word in options) {
                group.addView(Chip(group.context).apply {
                    this.text = word
                    isCheckable = false
                    setOnClickListener {
                        replaceText(list!!.complete(input?.text?.toString().orEmpty(), word))
                        refresh()
                    }
                })
            }
            group.visibility = if (options.isEmpty()) View.GONE else View.VISIBLE
        }

        val ready = if (analysis != null) analysis.isComplete else text.isNotBlank()
        decryptBtn?.isEnabled = ready && !busy
        decryptBtn?.alpha = if (ready && !busy) 1f else 0.6f
    }

    private fun setBusy(value: Boolean) {
        busy = value
        progress?.visibility = if (value) View.VISIBLE else View.GONE
        input?.isEnabled = !value
        layout?.isEnabled = !value
        refresh()
    }

    private fun decrypt() {
        if (busy) return
        val typed = input?.text?.toString().orEmpty()
        // #431: canonical() also completes words glued with no separators at
        // all ("abacuszoomabdomen" -> "abacus-zoom-abdomen"), which static
        // normalize() cannot reshape. The typed text stays as the last resort
        // and only matters when the wordlist could not be read.
        val candidates = listOfNotNull(
            words?.canonical(typed),
            PairingWords.normalize(typed),
            typed
        )
            .filter { it.isNotEmpty() }
            .distinct()
        if (candidates.isEmpty()) return
        val payload = payload
        statusMessage = null
        setBusy(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val outcome = withContext(Dispatchers.Default) {
                readSamouraiPairingPayloadTrying(payload, candidates)
            }
            val collection = outcome.collection
            val message = pairingImportUserMessage(requireContext(), outcome)
            if (collection == null) {
                statusMessage = message ?: getString(R.string.pairing_import_unrecognised)
                setBusy(false)
            } else {
                statusMessage = null
                setBusy(false)
                onSelect(collection, message)
            }
        }
    }
}

class LoadingFragment : Fragment() {

    private var _binding: LayoutLoadingBottomBinding? = null
    private val binding get() = _binding!!
    private lateinit var collection: PubKeyCollection
    private val repository: CollectionRepository by KoinJavaComponent.inject(CollectionRepository::class.java)
    private val homeViewModel: HomeViewModel by viewModels()
    private var onSelect: () -> Unit = {}


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutLoadingBottomBinding.inflate(inflater, container, false)
        binding.dialogTitle.text = "Adding collection"
        val view = binding.root
        return view
    }

    fun setCollection(collection: PubKeyCollection) {
        this.collection = collection
    }

    fun setSuccessCheck(callback: () -> Unit = {}) {
        this.onSelect = callback
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        binding.broadcastProgress.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.Main) {
            try {
                ExportImportUtil().startImportCollections(arrayListOf(collection), false)

                delay(3000)

                onSelect()
            } catch (e: Exception) {
                println("Error: ${e.message}")
            }
        }
    }
}

class SuccessFragment : Fragment() {

    private var _binding: LayoutLoadingBottomBinding? = null
    private val binding get() = _binding!!
    private var note: String? = null

    fun setNote(note: String?) {
        this.note = note
        renderNote()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutLoadingBottomBinding.inflate(inflater, container, false)
        binding.dialogTitle.text = "New collection added"
        renderNote()
        val view = binding.root
        return view
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        binding.successCheck.visibility = View.VISIBLE
    }

    /** Skipped slots, or a newer Ashigaru version, under the check mark. */
    private fun renderNote() {
        val binding = _binding ?: return
        val container = binding.contentContainer
        container.removeAllViews()
        val text = note?.takeIf { it.isNotBlank() } ?: return
        val pad = (16 * resources.displayMetrics.density).toInt()
        container.addView(TextView(container.context).apply {
            this.text = text
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(binding.dialogTitle.currentTextColor)
            setPadding(pad, pad, pad, pad)
            textSize = 14f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })
    }
}