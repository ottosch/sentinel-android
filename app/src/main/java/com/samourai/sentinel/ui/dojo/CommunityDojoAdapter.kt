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
) : ListAdapter<CheckedDojo, CommunityDojoAdapter.ViewHolder>(DiffCallback) {

    /** Names used by more than one signer in the current list ([SignerNameCollisions]). */
    private var collisions: Set<String> = emptySet()

    override fun onCurrentListChanged(previousList: List<CheckedDojo>, currentList: List<CheckedDojo>) {
        val now = SignerNameCollisions.names(currentList)
        if (now != collisions) {
            collisions = now
            // DiffUtil skips rows whose own content did not change.
            notifyDataSetChanged()
        }
    }

    /** True when [item]'s name is used by another signer in the current list. */
    fun nameCollides(item: CheckedDojo): Boolean = SignerNameCollisions.collides(item, collisions)

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
        val signer: TextView = view.findViewById(R.id.communityDojoSigner)
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
        // directory's label), collapsed to first 8 … last 8 as plain text.
        // A tap anywhere on the row opens the pre-pair dialog, which shows
        // the full code, View on PayNym and DON'T PAIR.
        val verified = item.verified
        holder.subtitle.text = if (verified != null) {
            communityDojoSubtitle(node)
        } else {
            val failure = (item.check as DojoBaySignatures.NodeCheck.Unverified).failure
            communityDojoSubtitle(node) + "\n" +
                context.getString(R.string.community_dojo_unverified, context.getString(failureText(failure)))
        }
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
            holder.warning.visibility = View.GONE
            return
        }
        holder.signer.visibility = View.VISIBLE
        holder.signer.text = context.getString(
            R.string.community_dojo_signed_by, SignerCodeFormat.collapsedCode(code),
        )
        val warning = rowWarning(context, item)
        holder.warning.visibility = if (warning != null) View.VISIBLE else View.GONE
        holder.warning.text = warning
    }

    /** A signed row's note under the signer, or null. */
    private fun rowWarning(context: android.content.Context, item: CheckedDojo): String? =
        if (nameCollides(item)) context.getString(R.string.community_dojo_signer_collision) else null

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
