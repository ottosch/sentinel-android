package com.samourai.sentinel.ui.settings

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.R
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.core.access.AccessFactory
import com.samourai.sentinel.data.db.SentinelCollectionStore
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.ui.dojo.DojoPairing
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.webview.ExplorerRepository
import com.samourai.sentinel.helpers.fromJSON
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * #488 through NetworkActivity's own wiring, not DojoRowLayout called by
 * hand (#598 review L1): KeyRegistrationBurst events and setDojoStatus on
 * the real activity move the Dojo status line under the buttons for a
 * burst label and back under the title at rest. Forcing either
 * placeDojoStatus call in the activity to wide = false fails this test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NetworkActivityDojoRowWiringTest {

    private lateinit var prefs: PrefsUtil
    private lateinit var dojo: DojoUtility
    private var controller: ActivityController<NetworkActivity>? = null
    private var savedAdmitted = false

    @Before
    fun setUp() {
        if (GlobalContext.getOrNull() != null) stopKoin()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val access = AccessFactory.getInstance(app)
        savedAdmitted = access.isSessionAdmitted
        access.admitSession()
        prefs = PrefsUtil(app)
        prefs.pinHash = ""
        prefs.pinKeyMaterial = ""
        prefs.pinEnabled = false
        KeyRegistrationBurst.markFinished()
        startKoin {
            androidContext(app)
            modules(
                module {
                    single { prefs }
                    single { access }
                    single { CollectionRepository() }
                    single { SentinelCollectionStore() }
                    single { DojoUtility() }
                    single { ApiService() }
                    single { ExplorerRepository() }
                }
            )
        }
        dojo = org.koin.java.KoinJavaComponent.get(DojoUtility::class.java)
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        KeyRegistrationBurst.markFinished()
        setPairing(null)
        if (GlobalContext.getOrNull() != null) stopKoin()
        val access = AccessFactory.getInstance(ApplicationProvider.getApplicationContext<Application>())
        if (savedAdmitted) access.admitSession() else {
            val field = AccessFactory::class.java.getDeclaredField("sessionAdmitted")
            field.isAccessible = true
            field.setBoolean(null, false)
        }
    }

    private fun setPairing(pairing: DojoPairing?) {
        val field = DojoUtility::class.java.getDeclaredField("dojoPayload")
        field.isAccessible = true
        field.set(dojo, pairing)
    }

    private fun launch(): NetworkActivity {
        controller = Robolectric.buildActivity(NetworkActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        return controller!!.get()
    }

    private fun NetworkActivity.status(): TextView = findViewById(R.id.network_dojo_status)
    private fun NetworkActivity.parentOfStatus(): View = status().parent as View
    private fun NetworkActivity.column(): View = findViewById(R.id.network_dojo_column)
    private fun NetworkActivity.titleBlock(): View = findViewById(R.id.network_dojo_title_block)

    @Test
    fun `burst progress moves the status across the column and Finished puts it back under the title`() {
        val activity = launch()
        assertSame(activity.titleBlock(), activity.parentOfStatus())

        // The burst publishes from its worker; the activity renders on the main thread.
        KeyRegistrationBurst.markStarted()
        KeyRegistrationBurst.begin(3)
        KeyRegistrationBurst.onItemStarted(1)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(activity.column(), activity.parentOfStatus())
        assertEquals(KeyRegistrationBurst.progressText(1, 3), activity.status().text.toString())

        // A full pass, no Dojo paired: the rest label, back under the title.
        KeyRegistrationBurst.complete(3, 0, 3)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(activity.titleBlock(), activity.parentOfStatus())
        assertEquals(activity.getString(R.string.disabled), activity.status().text.toString())
        // Exactly one status view, still the one the activity writes to.
        assertSame(activity.dojoConnectionStatus, activity.status())
    }

    @Test
    fun `a paired Dojo's residue label goes across the column, its rest label under the title`() {
        val activity = launch()
        setPairing(fromJSON<DojoPairing>("""{"pairing":{"type":"dojo.api","version":"1.27.0","apikey":"k","url":"http://dojo.example/v2"}}""")!!)

        // A partial round leaves its residue on the row: setDojoStatus (via
        // Finished) renders it full width.
        KeyRegistrationBurst.markStarted()
        KeyRegistrationBurst.begin(3)
        KeyRegistrationBurst.complete(1, 2, 3)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(KeyRegistrationBurst.residueText(1, 3), activity.status().text.toString())
        assertSame(activity.column(), activity.parentOfStatus())

        // Residue cleared: setDojoStatus's rest label sits under the title.
        KeyRegistrationBurst.markFinished()
        activity.setDojoStatus()
        assertSame(activity.titleBlock(), activity.parentOfStatus())
    }
}
