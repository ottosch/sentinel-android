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
    private val onCopy: (CheckedDojo) -> Unit
) : ListAdapter<CheckedDojo, CommunityDojoAdapter.ViewHolder>(DiffCallback) {

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

        // That Dojo's block explorer onion only. A clearnet
        // explorer shows no address. Same formatter as the
        // Network Block Explorer row.
        // Second line: who signed this listing (the code the signature
        // proves, not the directory's label), or why it can't be paired.
        val verified = item.verified
        val signature = if (verified != null) {
            context.getString(R.string.community_dojo_signed_by, DojoBaySignatures.shortCode(verified.paymentCode))
        } else {
            val failure = (item.check as DojoBaySignatures.NodeCheck.Unverified).failure
            context.getString(R.string.community_dojo_unverified, context.getString(failureText(failure)))
        }
        holder.subtitle.text = communityDojoSubtitle(node) + "\n" + signature
        holder.itemView.alpha = if (verified != null) 1f else 0.5f
        holder.copyButton.isEnabled = verified != null

        holder.itemView.setOnClickListener { onSelect(item) }
        holder.copyButton.setOnClickListener { onCopy(item) }
    }

    companion object {
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
