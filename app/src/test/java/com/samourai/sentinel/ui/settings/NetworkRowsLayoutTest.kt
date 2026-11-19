package com.samourai.sentinel.ui.settings

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Network screen's Dojo and Dojo Bay rows:
 * - At rest the Dojo status sits right under "Dojo Full Node", beside the
 *   buttons, with no blank line between them, paired ("Change / Disable"
 *   on two lines) or not. A burst label moves under the buttons, across
 *   the column (#488), and back again.
 * - The Dojo Bay row shows Dojo Bay's logo in the status column the other
 *   rows use, where it used to have an empty 64dp gap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class NetworkRowsLayoutTest {

    private val themed = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext<Application>(), R.style.AppThemeV3
    )

    private fun inflate(): View {
        val root = LayoutInflater.from(themed).inflate(R.layout.activity_network, null)
        return root
    }

    private fun layout(root: View) {
        val d = themed.resources.displayMetrics.density
        root.measure(
            MeasureSpec.makeMeasureSpec((411 * d).toInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec((891 * d).toInt(), MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }

    private fun yOnScreen(v: View): Int {
        var y = v.top
        var p = v.parent
        while (p is View) { y += p.top; p = p.parent }
        return y
    }

    @Test
    fun `the Dojo status sits right under the title at rest, and a burst label spans the column`() {
        for (paired in listOf(false, true)) {
            val root = inflate()
            val row = root.findViewById<ViewGroup>(R.id.network_dojo_layout)
            val details = row.findViewById<Button>(R.id.networking_dojo_details_btn)
            val button = row.findViewById<Button>(R.id.networking_dojo_btn)
            val status = row.findViewById<TextView>(R.id.network_dojo_status)
            val block = row.findViewById<ViewGroup>(R.id.network_dojo_title_block)
            val title = block.getChildAt(0) as TextView
            assertEquals("Dojo Full Node", title.text.toString())
            if (paired) {
                details.visibility = View.VISIBLE
                button.text = themed.getString(R.string.disable_dojo)
                status.text = DojoStatusRow.CONNECTED
            } else {
                button.text = themed.getString(R.string.enable)
                status.text = themed.getString(R.string.disabled)
            }
            DojoRowLayout.placeStatus(row, wide = false)
            layout(root)
            assertSame(block, status.parent)
            assertEquals("paired=$paired: no gap under the title", yOnScreen(title) + title.height, yOnScreen(status))
            // The status is not pushed under the buttons.
            assertTrue("paired=$paired", yOnScreen(status) < yOnScreen(button) + button.height)

            // #488: a burst label goes under the buttons, across the column.
            DojoRowLayout.placeStatus(row, wide = true)
            status.text = KeyRegistrationBurst.progressText(3, 9)
            layout(root)
            val column = row.findViewById<ViewGroup>(R.id.network_dojo_column)
            assertSame(column, status.parent)
            assertTrue(status.width > block.width)
            assertTrue(yOnScreen(status) >= yOnScreen(button) + button.height)
            assertEquals(LinearLayout.LayoutParams.MATCH_PARENT, status.layoutParams.width)

            // And back at rest.
            DojoRowLayout.placeStatus(row, wide = false)
            DojoRowLayout.placeStatus(row, wide = false)
            layout(root)
            assertSame(block, status.parent)
            assertEquals(1, (0 until block.childCount).count { block.getChildAt(it) === status })
        }
    }

    @Test
    fun `the Dojo Bay row shows Dojo Bay's logo in the status column`() {
        val root = inflate()
        layout(root)
        val icon = root.findViewById<ImageView>(R.id.network_directory_icon)
        assertNotNull(icon.drawable)
        assertEquals("Dojo Bay", icon.contentDescription)
        val d = themed.resources.displayMetrics.density
        assertEquals((64 * d).toInt(), icon.width)
        val torIcon = root.findViewById<View>(R.id.network_tor_status_icon)
        assertEquals(torIcon.left, icon.left)
        assertEquals(torIcon.width, icon.width)
        val dirRow = root.findViewById<ViewGroup>(R.id.network_directory_layout)
        assertEquals(0, dirRow.paddingStart)
        assertNotNull(ContextCompat.getDrawable(themed, R.drawable.ic_dojobay_logo))
    }
}
