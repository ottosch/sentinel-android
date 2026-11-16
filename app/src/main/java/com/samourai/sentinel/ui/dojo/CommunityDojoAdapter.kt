package com.samourai.sentinel.ui.dojo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.samourai.sentinel.R

class CommunityDojoAdapter(
    private val onSelect: (CheckedDojo) -> Unit,
    private val onCopy: (CheckedDojo) -> Unit,
    /** "View on PayNym" in an expanded row: the profile URL built from the signed code. */
    private val onOpenPayNym: (String) -> Unit = {},
) : ListAdapter<CheckedDojo, CommunityDojoAdapter.ViewHolder>(DiffCallback) {

    /** Rows whose signer code is expanded ([rowKey]); cleared with the list. */
    private val expanded = HashSet<String>()

    /** Names used by more than one signer in the current list ([SignerNameCollisions]). */
    private var collisions: Set<String> = emptySet()

    override fun onCurrentListChanged(previousList: List<CheckedDojo>, currentList: List<CheckedDojo>) {
        val keys = currentList.mapNotNull { rowKey(it) }.toSet()
        expanded.retainAll(keys)
        val now = SignerNameCollisions.names(currentList)
        if (now != collisions) {
            collisions = now
            // DiffUtil skips rows whose own content did not change.
            notifyDataSetChanged()
        }
    }

    /** Rows ([rowKey]) that look like the paired Dojo under a new signer (DojoBayPairedCheck). */
    private var signerChanged: Set<String> = emptySet()

    fun setSignerChanged(items: List<CheckedDojo>) {
        val keys = items.mapNotNull { rowKey(it) }.toSet()
        if (keys != signerChanged) {
            signerChanged = keys
            notifyDataSetChanged()
        }
    }

    fun isSignerChanged(item: CheckedDojo): Boolean = rowKey(item)?.let { it in signerChanged } == true

    /** True when [item]'s name is used by another signer in the current list. */
    fun nameCollides(item: CheckedDojo): Boolean = SignerNameCollisions.collides(item, collisions)

    fun isExpanded(item: CheckedDojo): Boolean = rowKey(item)?.let { it in expanded } == true

    /** Expands or collapses [item]'s signer code; signed rows only. */
    fun toggleExpanded(item: CheckedDojo) {
        val key = rowKey(item) ?: return
        if (!expanded.remove(key)) expanded.add(key)
        val index = currentList.indexOf(item)
        if (index >= 0) notifyItemChanged(index)
    }

    private object DiffCallback : DiffUtil.ItemCallback<CheckedDojo>() {
        override fun areItemsTheSame(oldItem: CheckedDojo, newItem: CheckedDojo) =
            oldItem.node.id == newItem.node.id

        override fun areContentsTheSame(oldItem: CheckedDojo, newItem: CheckedDojo) =
            oldItem == newItem
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val statusDot: View = view.findViewById(R.id.communityDojoStatusDot)
        val flag: TextView = view.findViewById(R.id.communityDojoFlag)
        val name: TextView = view.findViewById(R.id.communityDojoName)
        val subtitle: TextView = view.findViewById(R.id.communityDojoSubtitle)
        val chip: TextView = view.findViewById(R.id.communityDojoUnverifiedChip)
        val signer: TextView = view.findViewById(R.id.communityDojoSigner)
        val signerCode: TextView = view.findViewById(R.id.communityDojoSignerCode)
        val signerLink: TextView = view.findViewById(R.id.communityDojoSignerLink)
        val warning: TextView = view.findViewById(R.id.communityDojoSignerWarning)
        val copyButton: ImageButton = view.findViewById(R.id.communityDojoCopyButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_community_dojo, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val node = item.node
        val context = holder.itemView.context

        val dotColorRes = if (node.isOnline) R.color.success_green else R.color.grey_accent
        val dotShape = ContextCompat.getDrawable(context, R.drawable.circle_shape)?.mutate()
        dotShape?.setTint(ContextCompat.getColor(context, dotColorRes))
        holder.statusDot.background = dotShape

        holder.name.text = node.name?.takeIf { it.isNotBlank() } ?: "Unnamed Dojo"

        val flag = node.flagEmoji
        if (flag != null) {
            holder.flag.text = flag
            holder.flag.visibility = View.VISIBLE
        } else {
            holder.flag.visibility = View.GONE
        }

        // Second line: why it can't be paired. Signed rows show who signed
        // the listing below it (the code the signature proves, not the
        // directory's label): collapsed to first 8 … last 8, expanded on tap
        // to the full grouped code and a PayNym link.
        val verified = item.verified
        holder.subtitle.text = if (verified != null) {
            communityDojoSubtitle(node)
        } else {
            val failure = (item.check as DojoBaySignatures.NodeCheck.Unverified).failure
            communityDojoSubtitle(node) + "\n" +
                context.getString(R.string.community_dojo_unverified, context.getString(failureText(failure)))
        }
        holder.chip.visibility = if (verified != null) View.VISIBLE else View.GONE
        bindSigner(holder, item)
        holder.itemView.alpha = if (verified != null) 1f else 0.5f
        holder.copyButton.isEnabled = verified != null

        holder.itemView.setOnClickListener { onSelect(item) }
        holder.copyButton.setOnClickListener { onCopy(item) }
    }

    private fun bindSigner(holder: ViewHolder, item: CheckedDojo) {
        val context = holder.itemView.context
        val code = item.verified?.paymentCode
        if (code == null) {
            holder.signer.visibility = View.GONE
            holder.signerCode.visibility = View.GONE
            holder.signerLink.visibility = View.GONE
            holder.warning.visibility = View.GONE
            holder.signer.setOnClickListener(null)
            return
        }
        val open = isExpanded(item)
        holder.signer.visibility = View.VISIBLE
        holder.signer.text = if (open) {
            context.getString(R.string.community_dojo_signed_by_expanded)
        } else {
            context.getString(R.string.community_dojo_signed_by, SignerCodeFormat.collapsedCode(code))
        }
        holder.signer.contentDescription =
            if (open) null else context.getString(R.string.community_dojo_signer_expand_description)
        holder.signerCode.visibility = if (open) View.VISIBLE else View.GONE
        holder.signerCode.text = if (open) SignerCodeFormat.displayCode(code) else null
        val link = PayNymLinks.profileUrl(code)
        holder.signerLink.visibility = if (open && link != null) View.VISIBLE else View.GONE
        holder.signerLink.paintFlags = holder.signerLink.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        holder.signerLink.setOnClickListener { link?.let(onOpenPayNym) }
        // The code lines toggle too, so the row folds back from where it was read.
        holder.signer.setOnClickListener { toggleExpanded(item) }
        holder.signerCode.setOnClickListener { toggleExpanded(item) }
        val warning = rowWarning(context, item)
        holder.warning.visibility = if (warning != null) View.VISIBLE else View.GONE
        holder.warning.text = warning
    }

    /** A signed row's note under the signer, or null. */
    private fun rowWarning(context: android.content.Context, item: CheckedDojo): String? = when {
        isSignerChanged(item) -> context.getString(R.string.community_dojo_signer_changed_row)
        nameCollides(item) -> context.getString(R.string.community_dojo_signer_collision)
        else -> null
    }

    companion object {
        /** Expansion key for a signed row: the listing and the code that signed it. */
        fun rowKey(item: CheckedDojo): String? =
            item.verified?.paymentCode?.let { (item.node.id ?: item.node.name.orEmpty()) + "|" + it }

        fun failureText(failure: DojoBaySignatures.Failure): Int = when (failure) {
            DojoBaySignatures.Failure.MISSING_SIGNATURE -> R.string.community_dojo_failure_missing
            DojoBaySignatures.Failure.UNREADABLE_BLOCK -> R.string.community_dojo_failure_unreadable
            DojoBaySignatures.Failure.BAD_SIGNATURE -> R.string.community_dojo_failure_bad_signature
            DojoBaySignatures.Failure.SIGNER_NOT_CODE -> R.string.community_dojo_failure_signer
            DojoBaySignatures.Failure.PAYLOAD_MISMATCH -> R.string.community_dojo_failure_mismatch
            DojoBaySignatures.Failure.NOT_A_PAIRING -> R.string.community_dojo_failure_not_pairing
        }
    }
}
