package com.samourai.sentinel.ui.collectionDetails.send

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.samourai.sentinel.R
import com.samourai.sentinel.data.Utxo
import com.samourai.sentinel.data.repository.LabelRepository
import com.samourai.sentinel.ui.views.GenericBottomSheet
import com.samourai.sentinel.util.MonetaryUtil
import com.samourai.sentinel.util.UtxoMetaUtil
import org.koin.java.KoinJavaComponent.inject

/**
 * #190: manual coin control - pick exactly which coins a spend uses.
 *
 * Shown by [SendFragment] on its childFragmentManager, so it shares the
 * send screen's [SendViewModel]: the candidate rows are that ViewModel's
 * pool (the selected account's coins from the utxoDao LiveData), and the
 * answer goes straight back to it as a pinned outpoint set.
 *
 * Each row carries its utxo label as a chip (LabelRepository's live map,
 * the same source the UTXO list and the #55 details chips use), so bad-bank
 * lineage (#46/#54) is visible before anything is composed. Mixing Bad Bank
 * coins with other coins raises a co-spend warning in the sheet.
 *
 * Coins marked do-not-spend render disabled and can't be selected: a tap
 * on one says why (mirrors Samourai's UTXOSActivity refusal). The
 * ViewModel re-validates on confirm and before every compose, so a coin
 * frozen while the sheet was open still never reaches compose.
 *
 * Nothing here composes, signs or broadcasts: the sheet only decides which
 * outpoints enter the existing compose call.
 */
class CoinPickerBottomSheet : GenericBottomSheet() {

    private val viewModel: SendViewModel by viewModels(ownerProducer = { requireParentFragment() })
    private val labelRepository: LabelRepository by inject(LabelRepository::class.java)

    private val selected = linkedSetOf<String>()
    private var pool: List<Utxo> = emptyList()
    private var labels: Map<String, String> = emptyMap()
    private val adapter = CoinAdapter()

    private lateinit var summary: TextView
    private lateinit var coSpendWarning: TextView
    private lateinit var empty: TextView
    private lateinit var confirmBtn: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val restored = savedInstanceState?.getStringArrayList(STATE_SELECTED)
        if (restored != null) {
            selected.addAll(restored)
        } else {
            selected.addAll(viewModel.pinnedOutpoints.value ?: emptySet())
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.layout_bottom_sheet, container, false)
        view.findViewById<TextView>(R.id.dialogTitle).text = getString(R.string.coin_picker_title)
        val content = inflater.inflate(R.layout.content_bottom_sheet_coin_picker, null)
        view.findViewById<FrameLayout>(R.id.contentContainer).addView(content)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }

        summary = view.findViewById(R.id.coinPickerSummary)
        coSpendWarning = view.findViewById(R.id.coinPickerCoSpendWarning)
        empty = view.findViewById(R.id.coinPickerEmpty)
        confirmBtn = view.findViewById(R.id.coinPickerConfirmBtn)

        val list = view.findViewById<RecyclerView>(R.id.coinPickerList)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter

        confirmBtn.setOnClickListener {
            if (selected.isEmpty()) {
                toast(R.string.coin_picker_none_selected)
                return@setOnClickListener
            }
            if (viewModel.setPinnedSelection(selected.toSet())) {
                dismiss()
            } else {
                // The pool moved under the sheet (a coin was spent or frozen).
                prune()
                render()
                toast(R.string.coin_picker_selection_refused)
            }
        }
        view.findViewById<MaterialButton>(R.id.coinPickerAutoBtn).setOnClickListener {
            viewModel.clearPinnedSelection()
            dismiss()
        }

        viewModel.utxoPool.observe(viewLifecycleOwner) {
            pool = it.sortedByDescending { utxo -> utxo.value ?: 0L }
            prune()
            render()
        }
        labelRepository.observeLabelMap().observe(viewLifecycleOwner) {
            labels = it
            render()
        }
        // #301 signal: a do-not-spend mark changed somewhere - re-check rows.
        UtxoMetaUtil.blockedChanges.observe(viewLifecycleOwner) {
            prune()
            render()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_SELECTED, ArrayList(selected))
    }

    /** Drop selections that are no longer spendable coins in the pool. */
    private fun prune() {
        val spendable = pool.filterNot { UtxoMetaUtil.has(it) }
            .map { CoinSelection.keyOf(it) }
            .toSet()
        selected.retainAll(spendable)
    }

    private fun render() {
        if (!isAdded || view == null) return
        val chosen = pool.filter { selected.contains(CoinSelection.keyOf(it)) }
        val total = chosen.sumOf { it.value ?: 0L }
        summary.text = if (chosen.isEmpty()) {
            getString(R.string.coin_picker_mode_auto)
        } else {
            getString(
                R.string.coin_picker_mode_pinned,
                chosen.size,
                MonetaryUtil.getInstance().getBTCDecimalFormat(total)
            )
        }
        coSpendWarning.visibility =
            if (CoinSelection.mixesBadBank(chosen, labels)) View.VISIBLE else View.GONE
        empty.visibility = if (pool.isEmpty()) View.VISIBLE else View.GONE
        confirmBtn.text = getString(R.string.coin_picker_confirm, chosen.size)
        confirmBtn.isEnabled = chosen.isNotEmpty()
        confirmBtn.alpha = if (chosen.isNotEmpty()) 1f else 0.6f
        adapter.notifyDataSetChanged()
    }

    private fun toggle(utxo: Utxo) {
        if (UtxoMetaUtil.has(utxo)) {
            // Fail loudly: a frozen coin is never silently included or skipped.
            toast(R.string.coin_picker_frozen_refused)
            return
        }
        val key = CoinSelection.keyOf(utxo)
        if (!selected.remove(key)) selected.add(key)
        render()
    }

    private fun toast(res: Int) {
        Toast.makeText(requireContext(), getString(res), Toast.LENGTH_LONG).show()
    }

    private inner class CoinAdapter : RecyclerView.Adapter<CoinAdapter.Holder>() {

        inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val check: CheckBox = itemView.findViewById(R.id.coinPickerCheck)
            val amount: TextView = itemView.findViewById(R.id.coinPickerAmount)
            val address: TextView = itemView.findViewById(R.id.coinPickerAddress)
            val chips: ChipGroup = itemView.findViewById(R.id.coinPickerChips)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_coin_picker, parent, false))

        override fun getItemCount(): Int = pool.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val utxo = pool[position]
            val blocked = UtxoMetaUtil.has(utxo)
            holder.amount.text =
                "${MonetaryUtil.getInstance().getBTCDecimalFormat(utxo.value ?: 0L)} BTC"
            holder.address.text = utxo.addr ?: ""
            holder.check.isChecked = !blocked && selected.contains(CoinSelection.keyOf(utxo))
            holder.check.isEnabled = !blocked
            holder.itemView.alpha = if (blocked) 0.45f else 1f

            holder.chips.removeAllViews()
            if (blocked) {
                holder.chips.addView(chip(holder.chips, getString(R.string.coin_picker_do_not_spend), R.color.md_red_A700))
            }
            CoinSelection.labelFor(utxo, labels)?.takeIf { it.isNotBlank() }?.let { label ->
                val tint = if (CoinSelection.isBadBank(utxo, labels)) R.color.md_amber_700 else null
                holder.chips.addView(chip(holder.chips, label, tint))
            }
            holder.chips.visibility = if (holder.chips.childCount > 0) View.VISIBLE else View.GONE

            holder.itemView.setOnClickListener { toggle(utxo) }
        }

        private fun chip(group: ChipGroup, text: String, tint: Int?): Chip =
            Chip(group.context).apply {
                this.text = text
                isClickable = false
                isCheckable = false
                if (tint != null) {
                    chipBackgroundColor = ContextCompat.getColorStateList(group.context, tint)
                }
            }
    }

    companion object {
        const val TAG = "coin_picker"
        private const val STATE_SELECTED = "coin_picker_selected"
    }
}
