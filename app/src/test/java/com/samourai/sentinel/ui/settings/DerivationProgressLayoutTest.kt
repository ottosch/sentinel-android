package com.samourai.sentinel.ui.settings

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.View.MeasureSpec
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #488: the derivation-path counter keeps its own line at the widths and
 * font scales a phone actually uses, and the restore-or-new prompt is
 * centered with the rest of that sheet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DerivationProgressLayoutTest {

    @Test
    fun theCounterStaysOnItsOwnLineAcrossWidthsAndFontScales() {
        val widths = intArrayOf(320, 360, 411, 480)
        val scales = floatArrayOf(1.0f, 1.15f, 1.3f, 1.5f, 2.0f)
        for (widthDp in widths) {
            for (scale in scales) {
                val row = inflateDojoRow(widthDp, scale)
                val status = row.findViewById<TextView>(R.id.network_dojo_status)
                val title = textWith(row, "Dojo Full Node")
                // Both buttons are showing, which is what used to squeeze
                // the counter onto a leftover row. The status now spans
                // the column under them.
                assertTrue(
                    "status should extend past the title column at ${widthDp}dp x$scale " +
                        "(status=${status.width} title=${title.width})",
                    status.width > title.width
                )
                val narrow = linesOf(row, status, KeyRegistrationBurst.progressText(1, 9))
                val wide = linesOf(row, status, KeyRegistrationBurst.progressText(14, 19))
                assertEquals(
                    "a wider count must not reflow the label at ${widthDp}dp x$scale",
                    narrow.dropLast(1),
                    wide.dropLast(1)
                )
                assertEquals("14 of 19", wide.last())
                assertTrue(wide.dropLast(1).none { it.contains(" of ") })
                val label = "Registering derivation paths with Dojo\u2026"
                val labelPx = status.paint.measureText(label)
                val inner = status.width - status.paddingLeft - status.paddingRight
                if (inner >= labelPx) {
                    assertEquals(
                        "label fits, so it should be one line at ${widthDp}dp x$scale " +
                            "(inner=$inner labelPx=$labelPx lines=$wide)",
                        listOf(label, "14 of 19"),
                        wide
                    )
                }
            }
        }
    }

    @Test
    fun theRestorePromptIsCenteredWithTheSheetTitle() {
        val scales = floatArrayOf(1.0f, 1.3f, 2.0f)
        val widths = intArrayOf(320, 360, 411)
        for (widthDp in widths) {
            for (scale in scales) {
                val themed = themedContext(widthDp, scale)
                val inflater = android.view.LayoutInflater.from(themed)
                val sheet = inflater.inflate(R.layout.layout_bottom_sheet, null)
                val content = inflater.inflate(R.layout.content_bottom_sheet_confirm, null)
                sheet.findViewById<android.widget.FrameLayout>(R.id.contentContainer).addView(content)
                val title = sheet.findViewById<TextView>(R.id.dialogTitle)
                val message = sheet.findViewById<TextView>(R.id.bottomSheetConfirmMessage)
                title.text = "Set up Sentinel"
                message.text = "Restore from a backup, or start fresh on this device?"
                val px = (widthDp * themed.resources.displayMetrics.density).toInt()
                sheet.measure(
                    MeasureSpec.makeMeasureSpec(px, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                )
                sheet.layout(0, 0, sheet.measuredWidth, sheet.measuredHeight)
                assertEquals(
                    Gravity.CENTER_HORIZONTAL,
                    message.gravity and Gravity.HORIZONTAL_GRAVITY_MASK
                )
                assertEquals(
                    Gravity.CENTER_HORIZONTAL,
                    title.gravity and Gravity.HORIZONTAL_GRAVITY_MASK
                )
                val centered = message.textAlignment == View.TEXT_ALIGNMENT_CENTER ||
                    message.textAlignment == View.TEXT_ALIGNMENT_GRAVITY
                assertTrue(
                    "alignment=${message.textAlignment} gravity=${message.gravity}",
                    centered
                )
                val layout = message.layout
                assertTrue("message should have laid out at ${widthDp}dp x$scale", layout != null && layout.lineCount > 0)
                val contentWidth = message.width - message.paddingLeft - message.paddingRight
                for (i in 0 until layout.lineCount) {
                    val left = layout.getLineLeft(i)
                    val right = layout.getLineRight(i)
                    val slack = (contentWidth - (right - left)) / 2f
                    assertEquals(
                        "line $i should be centered at ${widthDp}dp x$scale",
                        slack,
                        left,
                        2f
                    )
                }
            }
        }
    }

    private fun inflateDojoRow(widthDp: Int, fontScale: Float): View {
        val themed = themedContext(widthDp, fontScale)
        val root = android.view.LayoutInflater.from(themed)
            .inflate(R.layout.activity_network, null)
        val row = root.findViewById<View>(R.id.network_dojo_layout)
        row.findViewById<View>(R.id.networking_dojo_details_btn).visibility = View.VISIBLE
        // The burst's labels take the whole column (NetworkActivity does
        // this for progress and residue).
        DojoRowLayout.placeStatus(row, wide = true)
        val px = (widthDp * themed.resources.displayMetrics.density).toInt()
        row.measure(
            MeasureSpec.makeMeasureSpec(px, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        row.layout(0, 0, row.measuredWidth, row.measuredHeight)
        return row
    }

    private fun linesOf(row: View, status: TextView, text: String): List<String> {
        status.text = text
        val px = row.width
        row.measure(
            MeasureSpec.makeMeasureSpec(px, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        row.layout(0, 0, row.measuredWidth, row.measuredHeight)
        val layout = status.layout
        return (0 until layout.lineCount).map { i ->
            text.subSequence(layout.getLineStart(i), layout.getLineEnd(i)).toString().trim()
        }
    }

    private fun textWith(root: View, value: String): TextView {
        findText(root, value)?.let { return it }
        throw AssertionError("missing text view: $value")
    }

    private fun findText(root: View, value: String): TextView? {
        if (root is TextView && root.text?.toString() == value) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                findText(root.getChildAt(i), value)?.let { return it }
            }
        }
        return null
    }

    private fun themedContext(widthDp: Int, fontScale: Float): ContextThemeWrapper {
        val base = ApplicationProvider.getApplicationContext<Application>()
        val config = android.content.res.Configuration(base.resources.configuration)
        config.fontScale = fontScale
        config.screenWidthDp = widthDp
        val scaled = base.createConfigurationContext(config)
        return ContextThemeWrapper(scaled, R.style.AppThemeV3)
    }
}
