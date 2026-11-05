package com.samourai.sentinel.ui.adapters

import android.content.Context
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.samourai.sentinel.R
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.ui.adapters.CollectionsAdapter.CollectionHolder
import com.samourai.sentinel.ui.utils.Preferences
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.util.BalanceDisplayFormatter
import com.samourai.sentinel.util.BalanceDisplayMode
import com.samourai.sentinel.util.MonetaryUtil
import com.samourai.sentinel.util.figuresFor
import org.bitcoinj.core.Coin
import org.koin.java.KoinJavaComponent
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import java.math.BigInteger
import java.nio.ByteBuffer
import java.text.DecimalFormat
import java.util.UUID
import kotlin.reflect.KProperty


class
CollectionsAdapter : RecyclerView.Adapter<CollectionHolder>() {

    private var onClickListener: (PubKeyCollection) -> Unit = {};
    private var layoutType: LayoutType = LayoutType.ROW;
    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java)
    private val walletIconTint = ColorStateList.valueOf(0xFFE4E4E4.toInt())

    // #429: grey-out rendering constants. The dim rides the row's
    // CONTENT views (dimRowContent), not itemView.alpha - the item
    // animator owns that (SlideInItemAnimator zeroes and re-springs
    // it on adds; the change cross-fade animates it to 1f), so a
    // bind-time itemView dim did not survive a data pass (device
    // leg 2: pull-to-refresh whitened greyed rows). An informative
    // state, not a gate: rows stay clickable for view-only access.
    private val ALPHA_NORMAL = 1f
    private val ALPHA_GREYED = 0.38f

    /**
     * #429: live re-render on a network flip. AsyncListDiffer sees
     * identical items when only the testnet pref changed, so nothing
     * rebinds on its own (#235 class). Registered on attach and
     * removed on detach - the sheet recreates its adapter per
     * open (#216), so an init-registered listener would accumulate
     * on PrefsUtil. The delegate fires listeners BEFORE the value
     * lands, so the rebind posts to the main handler: by the time
     * it runs, the pref reads fresh.
     */
    private val testnetChangeListener = object : Preferences.SharedPrefsListener {
        override fun onSharedPrefChanged(property: KProperty<*>) {
            if (property.name != "testnet") return
            Handler(Looper.getMainLooper()).post { notifyDataSetChanged() }
        }
    }

    private val diffCallBack = object : DiffUtil.ItemCallback<PubKeyCollection>() {

        override fun areItemsTheSame(oldItem: PubKeyCollection, newItem: PubKeyCollection): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: PubKeyCollection, newItem: PubKeyCollection): Boolean {
            return oldItem == newItem
        }

    }
//
//    init {
//        this.setHasStableIds(true)
//    }


    private val mDiffer: AsyncListDiffer<PubKeyCollection> = AsyncListDiffer(this, diffCallBack)

    class CollectionHolder(var view: View) : RecyclerView.ViewHolder(view) {
        var title: TextView = view.findViewById(R.id.rvItemCollectionTitle)
        var balance: TextView = view.findViewById(R.id.rvItemCollectionBalance)
        var icon: ImageView = view.findViewById(R.id.rvItemCollectionIcon)
        var badge: TextView = view.findViewById(R.id.rvItemCollectionBadge)
    }

    fun setOnClickListener(callback: (PubKeyCollection) -> Unit) {
        this.onClickListener = callback
    }

    fun setLayoutType(layout: LayoutType) {
        this.layoutType = layout
    }

    // #429: symmetric register/remove - see testnetChangeListener.
    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        prefsUtil.addListener(testnetChangeListener)
        // #429 device leg 2: a change-flicker mitigation on hosts
        // that run a SimpleItemAnimator at attach (the
        // choose-collection sheet) - NOT the grey's protection.
        // Home falsified that claim: it assigns SlideInItemAnimator
        // AFTER the adapter attaches, so this armed on the replaced
        // stock animator and never applied there. The grey rides
        // the content views (dimRowContent), outside every
        // animator's write set.
        (recyclerView.itemAnimator as? SimpleItemAnimator)
            ?.supportsChangeAnimations = false
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        prefsUtil.removeListener(testnetChangeListener)
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CollectionHolder {
        return if (layoutType == LayoutType.ROW) {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.collection_item_row_layout, parent, false);
            CollectionHolder(view)

        } else {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.collection_item_stacked_layout, parent, false);
            CollectionHolder(view)
        }
    }

    override fun getItemCount(): (Int) {
        return mDiffer.currentList.size
    }

    fun getCollectionList(): MutableList<PubKeyCollection> {
        return mDiffer.currentList
    }
//
//    override fun getItemId(position: Int): Long {
//        return getLongIdFromUUID(this.mDiffer.currentList[position].id);
//    }

    override fun onBindViewHolder(holder: CollectionHolder, position: Int) {
        val df = DecimalFormat("#")
        df.minimumIntegerDigits = 1
        df.minimumFractionDigits = 8
        df.maximumFractionDigits = 8

        val collection = mDiffer.currentList[position];
        holder.title.text = collection.collectionLabel;
        val rowMode =
            if (prefsUtil.streetMode == true)
                BalanceDisplayMode.MASKED
            else
                BalanceDisplayMode.fromString(prefsUtil.balanceDisplayMode)
        holder.balance.text =
            BalanceDisplayFormatter.format(setBalance(position), rowMode)
        holder.view.setOnClickListener {
            onClickListener(collection)
        }
        if (collection.isImportFromWallet) {
            holder.icon.imageTintList = null
            holder.icon.setImageResource(R.drawable.ic_ashigaru_logo)
        } else {
            holder.icon.imageTintList = walletIconTint
            holder.icon.setImageResource(R.drawable.ic_baseline_wallet_24)
        }

        // #429: render state vs the active network. Both arms set
        // content dim AND badge unconditionally - a recycled row can
        // never inherit a previous collection grey.
        //
        // The pref is the truth source here, NOT SentinelState: the
        // delegate fires listeners before the value lands, so
        // SentinelState.networkParams lags a flip until the next
        // pref write heals it. Rows must not paint that lag.
        val activeIsTestnet = prefsUtil.testnet == true
        when (
            collectionRowNetworkState(
                collection,
                activeIsTestnet = activeIsTestnet,
                dojoEnabled = SentinelState.isDojoEnabled()
            )
        ) {
            is CollectionRowNetworkState.Normal -> {
                dimRowContent(holder, dimmed = false)
                holder.badge.visibility = View.GONE
            }
            is CollectionRowNetworkState.GreyedPaired -> {
                dimRowContent(holder, dimmed = true)
                holder.badge.visibility = View.VISIBLE
                holder.badge.text = networkBadgeText(
                    holder.view.context,
                    activeIsTestnet,
                    paired = true
                )
            }
            is CollectionRowNetworkState.GreyedUnpaired -> {
                dimRowContent(holder, dimmed = true)
                holder.badge.visibility = View.VISIBLE
                holder.badge.text = networkBadgeText(
                    holder.view.context,
                    activeIsTestnet,
                    paired = false
                )
            }
        }
    }

    /**
     * #429: the dim lives on the row's CONTENT views, never on
     * itemView.alpha. Home's SlideInItemAnimator owns itemView.alpha
     * on every path - adds zero it and spring it back to 1f, the
     * inherited change cross-fade animates it to 1f, and the cancel
     * reset (clearAnimatedValues) hard-sets 1f - so the bind-time
     * itemView dim of #430/#435 was one data pass away from being
     * whitened (device leg 2: pull-to-refresh whitened greyed rows
     * while their badges stayed). Child-view alpha is outside every
     * animator's write set, so the grey survives adds, changes,
     * cancels and animator swaps by construction. Accepted cost:
     * the ripple background no longer dims; rows stay clickable
     * either way - grey is informative, not a gate.
     */
    private fun dimRowContent(holder: CollectionHolder, dimmed: Boolean) {
        val alpha = if (dimmed) ALPHA_GREYED else ALPHA_NORMAL
        holder.title.alpha = alpha
        holder.balance.alpha = alpha
        holder.icon.alpha = alpha
        holder.badge.alpha = alpha
    }

    /**
     * #301: the row's spendable figure from the one derivation
     * (SpendableBalance.kt) - previously this file's own inline
     * subtraction, one of five copies that disagreed at the edges.
     */
    fun setBalance(position: Int): Long {
        return figuresFor(mDiffer.currentList[position]).spendable
    }

    /**
     * #429: the off-network badge text. Only reached in a greyed
     * state, where the collection network is by definition the
     * opposite of the active one - no re-derivation needed.
     * Network names reuse the #250 labels (network_testnet /
     * network_mainnet) resolved at bind time; copy is decision 2,
     * frozen on the issue. Template pair picked by the Dojo flag.
     */
    private fun networkBadgeText(
        context: Context,
        activeIsTestnet: Boolean,
        paired: Boolean
    ): String {
        val collectionNetwork = context.getString(
            if (!activeIsTestnet) R.string.network_testnet
            else R.string.network_mainnet
        )
        val activeNetwork = context.getString(
            if (activeIsTestnet) R.string.network_testnet
            else R.string.network_mainnet
        )
        return context.getString(
            if (paired) R.string.collection_network_badge_paired
            else R.string.collection_network_badge_unpaired,
            collectionNetwork,
            activeNetwork
        )
    }

    fun update(newItems: ArrayList<PubKeyCollection>) {
        try {
            val list: ArrayList<PubKeyCollection> = arrayListOf<PubKeyCollection>()
            // Diff util will perform a shallow compare with updated list,
            // since we're using same list with updated items. we need to make a new copy
            // this will make shallow compare false
            newItems.forEach { list.add(it.copy()) }
            mDiffer.submitList(list)
        } catch (e: Exception) {
            Timber.e(e)
        }

    }

    companion object {
        fun getLongIdFromUUID(value: String): Long {
            var id = 1L;
            val uid = UUID.fromString(value)
            val buffer: ByteBuffer = ByteBuffer.wrap(ByteArray(16))
            buffer.putLong(uid.leastSignificantBits)
            buffer.putLong(uid.mostSignificantBits)
            val bi = BigInteger(buffer.array())
            id = bi.toLong() and Long.MAX_VALUE
            return id
        }

        enum class LayoutType {
            STACKED,
            ROW
        }
    }


    private fun getBTCDisplayAmount(value: Long): String? {
        return Coin.valueOf(value).toPlainString()
    }

}
