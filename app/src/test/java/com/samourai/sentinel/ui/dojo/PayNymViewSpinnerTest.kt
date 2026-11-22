package com.samourai.sentinel.ui.dojo

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * "View on PayNym" on the pre-pair dialog: a spinner while the name is
 * looked up over Tor, the link disabled against a second tap, the
 * failure shown inline, and the lookup cancelled when the dialog goes away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PayNymViewSpinnerTest {

    private val context = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext<Application>(), R.style.AppThemeV3,
    )
    private val qa = "PM8TJUJHkfdjkb5g5XaJuvnC63rUz7LXHnVqsnCd17dhDRupn9gW3QrgJJdNNGxsR4LSe4kFBMwarLbJVRQur9ZQfY4rtTHWWgdjLeE1svskeNoCEoum"

    private fun dialog(): View = LayoutInflater.from(context).inflate(R.layout.dialog_dojo_pairing_details, null)

    @Test
    fun `a tap shows a spinner and a second tap is ignored until the lookup finishes`() {
        val view = dialog()
        val views = PrePairDialogViews(view)
        var opens = 0
        views.bindSigner(prePairSigner(qa, null, null)) {
            opens++
            views.beginPayNymLookup(Job())
        }
        val link = view.findViewById<View>(R.id.dojoPairingSignerLink)
        val spinner = view.findViewById<View>(R.id.dojoPairingSignerLinkProgress)
        val error = view.findViewById<TextView>(R.id.dojoPairingSignerLinkError)

        assertEquals(View.GONE, spinner.visibility)
        assertTrue(link.isEnabled)
        assertEquals(View.GONE, error.visibility)

        link.performClick()
        assertEquals(1, opens)
        assertEquals(View.VISIBLE, spinner.visibility)
        assertFalse(link.isEnabled)

        link.performClick()
        assertEquals(1, opens)

        views.endPayNymLookup(false)
        assertEquals(View.GONE, spinner.visibility)
        assertTrue(link.isEnabled)
        assertEquals(View.VISIBLE, error.visibility)
        assertEquals(
            context.getString(R.string.paynym_page_not_found),
            error.text.toString(),
        )
        assertEquals(0, ShadowToast.shownToastCount())
    }

    @Test
    fun `a successful lookup restores the link with no error`() {
        val view = dialog()
        val views = PrePairDialogViews(view)
        views.bindSigner(prePairSigner(qa, null, null)) {
            views.beginPayNymLookup(Job())
        }
        view.findViewById<View>(R.id.dojoPairingSignerLink).performClick()
        views.endPayNymLookup(true)
        assertEquals(View.GONE, view.findViewById<View>(R.id.dojoPairingSignerLinkProgress).visibility)
        assertTrue(view.findViewById<View>(R.id.dojoPairingSignerLink).isEnabled)
        assertEquals(View.GONE, view.findViewById<View>(R.id.dojoPairingSignerLinkError).visibility)
    }

    @Test
    fun `cancelling when the dialog goes away stops the lookup and restores the link`() {
        val view = dialog()
        val views = PrePairDialogViews(view)
        val hanging = Job()
        views.bindSigner(prePairSigner(qa, null, null)) {
            views.beginPayNymLookup(hanging)
        }
        view.findViewById<View>(R.id.dojoPairingSignerLink).performClick()
        assertTrue(views.isPayNymLookupInProgress)
        views.cancelPayNymLookup()
        assertTrue(hanging.isCancelled)
        assertFalse(views.isPayNymLookupInProgress)
        assertEquals(View.GONE, view.findViewById<View>(R.id.dojoPairingSignerLinkProgress).visibility)
        assertTrue(view.findViewById<View>(R.id.dojoPairingSignerLink).isEnabled)
        assertEquals(View.GONE, view.findViewById<View>(R.id.dojoPairingSignerLinkError).visibility)
    }

    @Test
    fun `openSigner with a callback reports a missed page and does not toast`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val lookup = PayNymLookup(PayNymCache.forDirectory(app.noBackupFilesDir), torProxy = { null })
        var opened: Boolean? = null
        ShadowToast.reset()
        val job = PayNymPage.openSigner(
            app,
            CoroutineScope(Dispatchers.Unconfined),
            qa,
            lookup,
        ) { opened = it }
        runBlocking { job.join() }
        assertEquals(false, opened)
        assertEquals(0, ShadowToast.shownToastCount())
    }

    @Test
    fun `openSigner without a callback still toasts when there is no page`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val lookup = PayNymLookup(PayNymCache.forDirectory(app.noBackupFilesDir), torProxy = { null })
        ShadowToast.reset()
        val job = PayNymPage.openSigner(app, CoroutineScope(Dispatchers.Unconfined), qa, lookup)
        runBlocking { job.join() }
        assertEquals(1, ShadowToast.shownToastCount())
        assertEquals(app.getString(R.string.paynym_page_not_found), ShadowToast.getTextOfLatestToast())
    }
}
