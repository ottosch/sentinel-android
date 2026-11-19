package com.samourai.sentinel.ui.dojo

import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.samourai.sentinel.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The pre-pair dialog on a short window with every section shown (header,
 * caption, all rows, the signer with avatar, +name, code and link, and the
 * same-name warning): Pair and Don't pair stay on screen, the body scrolls,
 * and Back is Don't pair. With master's setMessage + setView shape, the same
 * body at this height put the Pair button's bottom at 1278 px on a 1200 px
 * screen: two middle panels make AlertDialogLayout fall back to a plain
 * LinearLayout measure, the body is clipped without scrolling and then the
 * buttons are pushed off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h400dp-xxhdpi")
class PrePairDialogLayoutTest {

    private val code = "PM8TJUJHkfdjkb5g5XaJuvnC63rUz7LXHnVqsnCd17dhDRupn9gW3QrgJJdNNGxsR4LSe4kFBMwarLbJVRQur9ZQfY4rtTHWWgdjLeE1svskeNoCEoum"

    private fun fullBody(activity: FragmentActivity): View {
        val view = activity.layoutInflater.inflate(R.layout.dialog_dojo_pairing_details, null)
        val views = PrePairDialogViews(view)
        views.bindHeader(DojoDirectoryHeader("🇸🇬", "max"), "Unnamed")
        views.bindSigner(prePairSigner(code, DojoDisplaySnapshot("u", "max", null, null, nameCollision = true), "u")) {}
        views.showIdentity(android.graphics.Bitmap.createBitmap(40, 40, android.graphics.Bitmap.Config.ARGB_8888), "+max")
        listOf(
            R.id.dojoPairingJurisdictionRow to R.id.dojoPairingJurisdictionValue,
            R.id.dojoPairingNetworkRow to R.id.dojoPairingNetworkValue,
            R.id.dojoPairingUrlRow to R.id.dojoPairingUrlValue,
            R.id.dojoPairingVersionRow to R.id.dojoPairingVersionValue,
            R.id.dojoPairingExplorerRow to R.id.dojoPairingExplorerValue,
        ).forEach { (row, value) ->
            view.findViewById<TextView>(value).text = "http://" + "a".repeat(56) + ".onion/v2"
            view.findViewById<View>(row).visibility = View.VISIBLE
        }
        return view
    }

    private fun activity(): FragmentActivity =
        Robolectric.buildActivity(PrePairHostActivity::class.java).setup().get()

    private fun bottomOnScreen(dialog: AlertDialog, which: Int): Pair<Int, Int> {
        shadowOf(Looper.getMainLooper()).idle()
        val button = dialog.getButton(which)
        val loc = IntArray(2)
        button.getLocationOnScreen(loc)
        val screen = dialog.context.resources.displayMetrics.heightPixels
        return (loc[1] + button.height) to screen
    }

    @Test
    fun `with the message in the scrolling body, Pair and Don't pair stay on screen`() {
        val activity = activity()
        val body = fullBody(activity)
        assertTrue(body is NestedScrollView)
        body.findViewById<TextView>(R.id.dojoPairingMessage).setText(R.string.dojo_pairing_confirm_message)
        var declined = 0
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.dojo_pairing_confirm_title)
            .setView(body)
            .setPositiveButton(R.string.dojo_pairing_confirm_accept, null)
            .setNegativeButton(R.string.dojo_pairing_confirm_decline, null)
            .setOnCancelListener { declined++ }
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        for (which in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE)) {
            val (bottom, screen) = bottomOnScreen(dialog, which)
            val button = dialog.getButton(which)
            assertTrue("bottom=$bottom screen=$screen height=${button.height}", button.height > 0 && bottom <= screen)
            assertTrue(button.isShown)
        }
        // The body is taller than the space left, so it scrolls.
        val scroll = body as NestedScrollView
        assertTrue(scroll.getChildAt(0).height > scroll.height)

        // Back is Don't pair.
        dialog.onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
        assertEquals(1, declined)
    }
}

class PrePairHostActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppThemeV3)
        super.onCreate(savedInstanceState)
    }
}
