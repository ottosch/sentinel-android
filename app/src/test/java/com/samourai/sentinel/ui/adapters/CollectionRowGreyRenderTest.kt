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
 * badge slot in both layouts.
 */
class CollectionRowGreyRenderTest {

    @Test
    fun `bind sets and resets grey and badge in every arm - recycle cannot inherit a stale grey`() {
        val src = source("app/src/main/java/com/samourai/sentinel/ui/adapters/CollectionsAdapter.kt")
        val bind = src.substringAfter("override fun onBindViewHolder")
        // reset arm: a recycled row returns to normal
        assertTrue(bind.contains("holder.view.alpha = ALPHA_NORMAL"))
        assertTrue(bind.contains("holder.badge.visibility = View.GONE"))
        // grey arms: whole-row dim + badge visible, text resolved
        assertTrue(bind.contains("holder.view.alpha = ALPHA_GREYED"))
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

    private fun source(relative: String): String {
        val file = sequenceOf(File(relative), File(relative.removePrefix("app/"))).firstOrNull { it.isFile }
            ?: error("missing $relative from ${File(".").absolutePath}")
        return file.readText()
    }
}
