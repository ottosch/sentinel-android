package com.samourai.sentinel.ui.dojo

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.R
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.data.db.SentinelCollectionStore
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.ui.dojo.DojoBayTestVectors.ThrowawayPayNym
import com.samourai.sentinel.ui.utils.PrefsUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

/**
 * "View on PayNym" on the pre-pair dialog: a spinner while the name is
 * looked up over Tor, the link disabled against a second tap (with disabled
 * colour), synchronous completions (Tor off or cached +name) never leaving
 * the spinner stuck, Throwable/CancellationException handling in openSigner,
 * and dismissing the sheet/dialog cancelling any in-flight lookup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PayNymViewSpinnerTest {

    private val context = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext<Application>(), R.style.AppThemeV3,
    )
    private val qa = "PM8TJUJHkfdjkb5g5XaJuvnC63rUz7LXHnVqsnCd17dhDRupn9gW3QrgJJdNNGxsR4LSe4kFBMwarLbJVRQur9ZQfY4rtTHWWgdjLeE1svskeNoCEoum"

    private fun dialog(): View = LayoutInflater.from(context).inflate(R.layout.dialog_dojo_pairing_details, null)

    @After
    fun tearDownKoin() {
        GlobalContext.getOrNull()?.let { stopKoin() }
    }

    private fun startSheetKoin(): PrefsUtil {
        GlobalContext.getOrNull()?.let { stopKoin() }
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = PrefsUtil(app)
        prefs.clearAll()
        prefs.testnet = true
        startKoin {
            androidContext(app)
            modules(
                module {
                    single { prefs }
                    single { CollectionRepository() }
                    single { ApiService() }
                    single { SentinelCollectionStore() }
                    single { DojoUtility() }
                }
            )
        }
        return prefs
    }

    @Test
    fun `a tap shows a spinner with disabled color and a second tap is ignored until the lookup finishes`() {
        val view = dialog()
        val views = PrePairDialogViews(view)
        var opens = 0
        views.bindSigner(prePairSigner(qa, null, null)) {
            opens++
            views.beginPayNymLookup(Job())
        }
        val link = view.findViewById<TextView>(R.id.dojoPairingSignerLink)
        val spinner = view.findViewById<View>(R.id.dojoPairingSignerLinkProgress)
        val error = view.findViewById<TextView>(R.id.dojoPairingSignerLinkError)
        val enabledColor = ContextCompat.getColor(context, R.color.green_ui_2)
        val disabledColor = ContextCompat.getColor(context, R.color.disabled_grey)

        assertEquals(View.GONE, spinner.visibility)
        assertTrue(link.isEnabled)
        assertEquals(enabledColor, link.currentTextColor)
        assertEquals(View.GONE, error.visibility)

        link.performClick()
        assertEquals(1, opens)
        assertEquals(View.VISIBLE, spinner.visibility)
        assertFalse(link.isEnabled)
        assertEquals(disabledColor, link.currentTextColor)

        link.performClick()
        assertEquals(1, opens)

        views.endPayNymLookup(false)
        assertEquals(View.GONE, spinner.visibility)
        assertTrue(link.isEnabled)
        assertEquals(enabledColor, link.currentTextColor)
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
    fun `synchronous completion when Tor is off or name is cached does not drop end and leave spinner stuck`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val cacheDir = java.io.File(app.noBackupFilesDir, "paynym_sync_test_\${System.nanoTime()}").apply { mkdirs() }
        try {
            val cache = PayNymCache(cacheDir)
            val lookup = PayNymLookup(cache, torProxy = { null })
            val view = dialog()
            val views = PrePairDialogViews(view)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

            views.bindSigner(prePairSigner(qa, null, null)) { code ->
                val job = PayNymPage.openSigner(
                    context,
                    scope,
                    code,
                    lookup = lookup,
                    start = CoroutineStart.LAZY,
                ) { opened ->
                    views.endPayNymLookup(opened)
                }
                views.beginPayNymLookup(job)
                job.start()
            }

            val link = view.findViewById<TextView>(R.id.dojoPairingSignerLink)
            val spinner = view.findViewById<View>(R.id.dojoPairingSignerLinkProgress)
            val error = view.findViewById<TextView>(R.id.dojoPairingSignerLinkError)

            // Tor off -> synchronous failure without suspending.
            link.performClick()
            assertFalse(views.isPayNymLookupInProgress)
            assertEquals(View.GONE, spinner.visibility)
            assertTrue(link.isEnabled)
            assertEquals(View.VISIBLE, error.visibility)

            // Cached +name -> synchronous success without suspending.
            cache.putName(qa, "+cachednym")
            val openedIntents = mutableListOf<Intent>()
            val capturingContext = object : ContextWrapper(context) {
                override fun startActivity(intent: Intent) {
                    openedIntents += intent
                }
            }
            views.bindSigner(prePairSigner(qa, null, null)) { code ->
                val job = PayNymPage.openSigner(
                    capturingContext,
                    scope,
                    code,
                    lookup = lookup,
                    start = CoroutineStart.LAZY,
                ) { opened ->
                    views.endPayNymLookup(opened)
                }
                views.beginPayNymLookup(job)
                job.start()
            }
            link.performClick()
            assertEquals(1, openedIntents.size)
            assertFalse(views.isPayNymLookupInProgress)
            assertEquals(View.GONE, spinner.visibility)
            assertTrue(link.isEnabled)
            assertEquals(View.GONE, error.visibility)

            // Even if beginPayNymLookup receives an already-completed job, it stays idle.
            val completed = Job().apply { complete() }
            views.beginPayNymLookup(completed)
            assertFalse(views.isPayNymLookupInProgress)
            assertEquals(View.GONE, spinner.visibility)
            assertTrue(link.isEnabled)
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun `real pre-pair dialog on DojoConfigureBottomSheet does not stick spinner when Tor is off and dismisses on destroy`() {
        startSheetKoin()
        val owner = ThrowawayPayNym(52)
        val node = CommunityDojoNode(
            id = "spinner_test", network = "testnet", name = "Spinner Dojo", status = "active",
            paymentCode = owner.code, payload = DojoBayTestVectors.testPayload(),
            signed = owner.listing(DojoBayTestVectors.TEST_PAIRING_JSON),
        )
        val checked = CheckedDojo(node, DojoBaySignatures.checkNode(node)).also { assertNotNull(it.verified) }

        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.AppThemeV3)
        val activity = controller.setup().get()
        val sheet = DojoConfigureBottomSheet.newInstance("spinner_sheet_test")
        sheet.show(activity.supportFragmentManager, "dojo_configure")
        shadowOf(Looper.getMainLooper()).idle()

        // Open the pre-pair dialog with the verified signer code (same path as list selection).
        val signedPayload = checked.verified!!.signedPayloadJson
        assertTrue(sheet.onScanPayload(signedPayload, snapshot = null, signerCode = owner.code))
        shadowOf(Looper.getMainLooper()).idle()

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        val link = dialog.findViewById<TextView>(R.id.dojoPairingSignerLink)!!
        val spinner = dialog.findViewById<View>(R.id.dojoPairingSignerLinkProgress)!!
        val error = dialog.findViewById<TextView>(R.id.dojoPairingSignerLinkError)!!

        // Tor is off in test state: tapping View on PayNym finishes synchronously and must not leave spinner stuck.
        link.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.GONE, spinner.visibility)
        assertTrue(link.isEnabled)
        assertEquals(View.VISIBLE, error.visibility)

        // Destroying/dismissing the sheet dismisses the open pre-pair dialog.
        sheet.dismissAllowingStateLoss()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
    }

    @Test
    fun `cancelling when the dialog goes away stops an in-flight openSigner lookup and restores the link`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val cacheDir = java.io.File(app.noBackupFilesDir, "paynym_cancel_test_\${System.nanoTime()}").apply { mkdirs() }
        try {
            val gate = CompletableDeferred<PayNymReply?>()
            val hangingTransport = object : PayNymTransport {
                override suspend fun get(path: String, maxBytes: Int): PayNymReply? = null
                override suspend fun post(path: String, json: String, maxBytes: Int): PayNymReply? = gate.await()
            }
            val lookup = PayNymLookup(
                PayNymCache(cacheDir),
                torProxy = { java.net.Proxy.NO_PROXY },
                transportFor = { hangingTransport },
            )
            val view = dialog()
            val views = PrePairDialogViews(view)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            var callbackCalled = false
            lateinit var startedJob: Job

            views.bindSigner(prePairSigner(qa, null, null)) { code ->
                startedJob = PayNymPage.openSigner(
                    context,
                    scope,
                    code,
                    lookup = lookup,
                    start = CoroutineStart.LAZY,
                ) { opened ->
                    callbackCalled = true
                    views.endPayNymLookup(opened)
                }
                views.beginPayNymLookup(startedJob)
                startedJob.start()
            }

            view.findViewById<View>(R.id.dojoPairingSignerLink).performClick()
            assertTrue(views.isPayNymLookupInProgress)
            assertTrue(startedJob.isActive)

            views.cancelPayNymLookup()
            assertTrue(startedJob.isCancelled)
            assertFalse(views.isPayNymLookupInProgress)
            assertFalse(callbackCalled)
            assertEquals(View.GONE, view.findViewById<View>(R.id.dojoPairingSignerLinkProgress).visibility)
            assertTrue(view.findViewById<View>(R.id.dojoPairingSignerLink).isEnabled)
            assertEquals(View.GONE, view.findViewById<View>(R.id.dojoPairingSignerLinkError).visibility)
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun `openSigner rethrows CancellationException instead of swallowing it`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val cacheDir = java.io.File(app.noBackupFilesDir, "paynym_cancel_rethrow_\${System.nanoTime()}").apply { mkdirs() }
        try {
            val cancellingTransport = object : PayNymTransport {
                override suspend fun get(path: String, maxBytes: Int): PayNymReply? = null
                override suspend fun post(path: String, json: String, maxBytes: Int): PayNymReply? {
                    throw CancellationException("lookup cancelled")
                }
            }
            val lookup = PayNymLookup(
                PayNymCache(cacheDir),
                torProxy = { java.net.Proxy.NO_PROXY },
                transportFor = { cancellingTransport },
            )
            var callbackInvoked = false
            val job = PayNymPage.openSigner(
                app,
                CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                qa,
                lookup,
            ) {
                callbackInvoked = true
            }
            runBlocking { job.join() }
            assertTrue("CancellationException must cancel the job rather than being swallowed", job.isCancelled)
            assertFalse(callbackInvoked)
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun `openSigner catches non-cancellation Throwables without crashing scope and always ends lookup`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val cacheDir = java.io.File(app.noBackupFilesDir, "paynym_throwable_\${System.nanoTime()}").apply { mkdirs() }
        try {
            val uncaught = mutableListOf<Throwable>()
            val scope = CoroutineScope(
                SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, t -> uncaught += t }
            )

            // 1. OutOfMemoryError from transport.post
            val oomTransport = object : PayNymTransport {
                override suspend fun get(path: String, maxBytes: Int): PayNymReply? = null
                override suspend fun post(path: String, json: String, maxBytes: Int): PayNymReply? {
                    throw OutOfMemoryError("simulated OOM")
                }
            }
            var oomResult: Boolean? = null
            val oomJob = PayNymPage.openSigner(
                app,
                scope,
                qa,
                PayNymLookup(PayNymCache(cacheDir), torProxy = { java.net.Proxy.NO_PROXY }, transportFor = { oomTransport }),
            ) { oomResult = it }
            runBlocking { oomJob.join() }
            assertEquals(false, oomResult)
            assertTrue("scope must not receive uncaught Throwable", uncaught.isEmpty())

            // 2. RuntimeException from TorIsolatedSocks.fromProxy (invalid proxy type) or open(context, url)
            val cache = PayNymCache(cacheDir).apply { putName(qa, "+validnym") }
            val throwingContext = object : ContextWrapper(app) {
                override fun startActivity(intent: Intent) {
                    throw IllegalStateException("activity launch failed")
                }
            }
            var openResult: Boolean? = null
            val openJob = PayNymPage.openSigner(
                throwingContext,
                scope,
                qa,
                PayNymLookup(cache, torProxy = { null }),
            ) { openResult = it }
            runBlocking { openJob.join() }
            assertEquals(false, openResult)
            assertTrue("scope must not receive uncaught Throwable from open()", uncaught.isEmpty())
        } finally {
            cacheDir.deleteRecursively()
        }
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
