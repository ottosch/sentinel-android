package com.samourai.sentinel.ui.adapters

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #429, JVM side: the grey-out wiring, pinned in the
 * StaleBalanceRenderTest / CachedTotalPaintTest idiom - source
 * shape, not view binds. A Robolectric bind test would need a
 * Koin harness (PrefsUtil, DojoUtility, UtxoMetaUtil's store) and
 * would hit the theme-attribute inflation gap BottomSheetRecreationTest
 * documents; the pixel-level render rides the device leg.
 *
 * What these legs pin: grey applied on mismatch and reset on
 * recycle, the bind truth source, the live-rebind wiring, and the
 * badge slot in both layouts, the animator boundary (the dim
 * rides content views because item animators own itemView.alpha),
 * and the change-animation guard as a flicker mitigation - the
 * device leg falsified its grey-protection claim on Home.
 */
class CollectionRowGreyRenderTest {

    @Test
    fun `bind sets and resets content dim and badge in every arm - recycle cannot inherit a stale grey`() {
        val src = source("app/src/main/java/com/samourai/sentinel/ui/adapters/CollectionsAdapter.kt")
        val bind = src.substringAfter("override fun onBindViewHolder")
        // reset arm: a recycled row returns to normal
        assertTrue(bind.contains("dimRowContent(holder, dimmed = false)"))
        assertTrue(bind.contains("holder.badge.visibility = View.GONE"))
        // grey arms: content dim + badge visible, text resolved
        assertTrue(bind.contains("dimRowContent(holder, dimmed = true)"))
        assertTrue(bind.contains("holder.badge.visibility = View.VISIBLE"))
        assertTrue(bind.contains("holder.badge.text = networkBadgeText("))
        // the state comes from the pure table, not ad-hoc logic
        assertTrue(bind.contains("collectionRowNetworkState("))
    }

    @Test
    fun `bind truth is the pref, not the lagging SentinelState`() {
        val src = source("app/src/main/java/com/samourai/sentinel/ui/adapters/CollectionsAdapter.kt")
        assertTrue(src.contains("val activeIsTestnet = prefsUtil.testnet == true"))
        // GenericPrefDelegate.setValue fires listeners BEFORE the value
        // lands, so SentinelState.networkParams lags a flip by one
        // write - the bind must never paint that lag.
        assertFalse(src.contains("SentinelState.isTestNet()"))
        // isDojoEnabled stays: only the badge template pick, no lag risk
        assertTrue(src.contains("SentinelState.isDojoEnabled()"))
    }

    @Test
    fun `network flip rebinds live - posted, filtered, attach-detach symmetric`() {
        val src = source("app/src/main/java/com/samourai/sentinel/ui/adapters/CollectionsAdapter.kt")
        // only the testnet pref fires the rebind
        assertTrue(src.contains("if (property.name != \"testnet\") return"))
        // posted to the main handler: the delegate fires before the
        // pref lands, so a synchronous rebind would paint the old network
        assertTrue(src.contains("Handler(Looper.getMainLooper()).post { notifyDataSetChanged() }"))
        // symmetric registration - the sheet recreates its adapter per
        // open (#216), so init-registration would leak listeners
        val attach = src.substringAfter("override fun onAttachedToRecyclerView")
            .substringBefore("override fun onDetachedFromRecyclerView")
        assertTrue(attach.contains("prefsUtil.addListener(testnetChangeListener)"))
        val detach = src.substringAfter("override fun onDetachedFromRecyclerView")
        assertTrue(detach.contains("prefsUtil.removeListener(testnetChangeListener)"))
    }

    @Test
    fun `both layouts carry the badge slot and both templates resolve`() {
        val row = source("app/src/main/res/layout/collection_item_row_layout.xml")
        val stacked = source("app/src/main/res/layout/collection_item_stacked_layout.xml")
        listOf(row, stacked).forEach { layout ->
            assertTrue(layout.contains("rvItemCollectionBadge"))
            // decision 2: wrap or ellipsize, never clip
            assertTrue(layout.contains("android:maxLines=\"2\""))
            assertTrue(layout.contains("android:ellipsize=\"end\""))
        }
        val strings = source("app/src/main/res/values/strings.xml")
        assertTrue(strings.contains("collection_network_badge_paired"))
        assertTrue(strings.contains("collection_network_badge_unpaired"))
        val adapter = source("app/src/main/java/com/samourai/sentinel/ui/adapters/CollectionsAdapter.kt")
        assertTrue(adapter.contains("R.string.collection_network_badge_paired"))
        assertTrue(adapter.contains("R.string.collection_network_badge_unpaired"))
    }

    @Test
    fun `change animations cannot stomp the grey - attach hook disables the cross-fade`() {
        // Device leg, post-#430: DefaultItemAnimator's change
        // cross-fade animates a rebound view's alpha 0 -> 1,
        // overwriting the bind-time grey while the badge stays -
        // a white row carrying the off-network advisory. The
        // attach hook must disable change animations so the
        // bind-time alpha survives content-change rebinds.
        val src = source("app/src/main/java/com/samourai/sentinel/ui/adapters/CollectionsAdapter.kt")
        val attach = src.substringAfter("override fun onAttachedToRecyclerView")
            .substringBefore("override fun onDetachedFromRecyclerView")
        assertTrue(attach.contains("supportsChangeAnimations = false"))
    }

    @Test
    fun `the dim rides the content views, never itemView alpha - item animators own that`() {
        // Device leg 2, post-#435: Home's SlideInItemAnimator owns
        // itemView.alpha on every path - animateAdd zeroes it, the
        // add spring returns it to 1f, the change cross-fade
        // animates it to 1f, clearAnimatedValues hard-resets 1f -
        // so the bind-time itemView dim was one data pass away
        // from being whitened while the badge stayed. The dim must
        // ride the content views, outside every animator's set.
        val src = source("app/src/main/java/com/samourai/sentinel/ui/adapters/CollectionsAdapter.kt")
        // no itemView alpha writes remain anywhere in the adapter
        assertFalse(src.contains("holder.view.alpha"))
        val dim = src.substringAfter("private fun dimRowContent")
        assertTrue(dim.contains("holder.title.alpha = alpha"))
        assertTrue(dim.contains("holder.balance.alpha = alpha"))
        assertTrue(dim.contains("holder.icon.alpha = alpha"))
        assertTrue(dim.contains("holder.badge.alpha = alpha"))
    }

    private fun source(relative: String): String {
        val file = sequenceOf(File(relative), File(relative.removePrefix("app/"))).firstOrNull { it.isFile }
            ?: error("missing $relative from ${File(".").absolutePath}")
        return file.readText()
    }
}
