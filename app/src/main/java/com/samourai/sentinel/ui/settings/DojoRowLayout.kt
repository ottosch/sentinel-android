package com.samourai.sentinel.ui.settings

import android.view.View
import android.view.ViewGroup
import com.samourai.sentinel.R

/**
 * Where the Dojo row's status line sits. At rest it is right under the
 * "Dojo Full Node" title, beside Details and Disable, the way the Tor row
 * reads. #488: the burst's progress and residue labels are long, so they
 * move below the buttons and get the whole column. Stacking the status
 * under the buttons at all times put a blank line between the title and
 * the status, because the title row is as tall as the two-line
 * "Change / Disable" button.
 */
internal object DojoRowLayout {

    fun placeStatus(row: View, wide: Boolean) {
        val status = row.findViewById<View>(R.id.network_dojo_status) ?: return
        val target = row.findViewById<ViewGroup>(
            if (wide) R.id.network_dojo_column else R.id.network_dojo_title_block
        ) ?: return
        if (status.parent === target) return
        (status.parent as? ViewGroup)?.removeView(status)
        // Both parents are vertical LinearLayouts, so the status keeps its
        // own LayoutParams (match_parent width, 4dp end margin).
        target.addView(status)
    }
}
