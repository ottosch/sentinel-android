package com.samourai.sentinel.ui.settings

import android.app.Application
import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import android.os.Looper
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.preference.Preference
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.R
import com.samourai.sentinel.core.access.AccessFactory
import com.samourai.sentinel.core.access.PinVault
import com.samourai.sentinel.data.db.SentinelCollectionStore
import com.samourai.sentinel.data.db.SentinelRoomDb
import com.samourai.sentinel.data.repository.Bip329Exporter
import com.samourai.sentinel.data.repository.Bip329Importer
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.data.repository.ExchangeRateRepository
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.views.ConfirmBottomSheet
import com.samourai.sentinel.ui.views.LockScreenDialog
import com.samourai.sentinel.ui.webview.ExplorerRepository
import com.samourai.sentinel.util.Hash
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.security.MessageDigest
import java.util.ArrayDeque
import java.nio.charset.StandardCharsets

/**
 * #288 / #331: Export and Erase must ask for the PIN when one is
 * configured. After #317 the PIN lives in PinVault key material and
 * pinHash is blank, so the gate has to follow isPinConfigured() and
 * PinVault.unlock, not pinHash alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsPinGateTest {

    private lateinit var prefs: PrefsUtil
    private lateinit var access: AccessFactory
    private lateinit var db: SentinelRoomDb
    private var controller: ActivityController<SettingsActivity>? = null

    @Before
    fun setUp() {
        if (GlobalContext.getOrNull() != null) stopKoin()
        val app = ApplicationProvider.getApplicationContext<Application>()
        access = AccessFactory.getInstance(app)
        access.reset()
        access.clearDataKey()
        access.pin = null
        access.setPinProtected(false)
        access.setIsLoggedIn(false)
        access.admitSession()
        access.updatePin()
        prefs = PrefsUtil(app)
        prefs.clearAll()
        prefs.pinHash = ""
        prefs.pinKeyMaterial = ""
        prefs.pinEnabled = false
        db = Room.inMemoryDatabaseBuilder(app, SentinelRoomDb::class.java)
            .allowMainThreadQueries()
            .build()
        startKoin {
            androidContext(app)
            modules(
                module {
                    single { prefs }
                    single { access }
                    single { db }
                    single { CollectionRepository() }
                    single { SentinelCollectionStore() }
                    single { DojoUtility() }
                    single { ExchangeRateRepository() }
                    single { ExplorerRepository() }
                    single { Bip329Importer(db) }
                    single { Bip329Exporter(db.utxoLabelDao(), db.labelEntryDao()) }
                }
            )
        }
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        if (this::db.isInitialized) db.close()
        if (this::prefs.isInitialized) {
            prefs.pinHash = ""
            prefs.pinKeyMaterial = ""
            prefs.pinEnabled = false
        }
        access.reset()
        access.clearDataKey()
        access.pin = null
        access.setPinProtected(false)
        access.setIsLoggedIn(false)
        if (GlobalContext.getOrNull() != null) stopKoin()
    }

    @Test
    fun `export and erase prompt when the PIN is only in PinVault storage`() {
        storePinInVault()
        assertTrue(prefs.pinHash.isNullOrBlank())
        assertFalse(prefs.pinKeyMaterial.isNullOrBlank())

        val settings = openSettings()
        clickPref(settings, "export")
        assertTrue(gatePadVisible(settings))
        assertFalse(exportChooserVisible(settings))

        closeActivity()
        val again = openSettings()
        clickPref(again, "clear")
        assertTrue(gatePadVisible(again))
        assertFalse(eraseConfirmVisible(again))
    }

    @Test
    fun `the right PIN runs export once and a wrong PIN shows the error without running it`() {
        storePinInVault()
        val settings = openSettings()
        clickPref(settings, "export")
        assertTrue(gatePadVisible(settings))

        enterGatePin(settings, WRONG_PIN)
        waitUntil("incorrect PIN is shown") { incorrectPinShown(settings) }
        assertTrue(gatePadVisible(settings))
        assertFalse(exportChooserVisible(settings))
        assertFalse(eraseConfirmVisible(settings))

        enterGatePin(settings, RIGHT_PIN)
        waitUntil("export options appear") { exportChooserVisible(settings) }
        assertFalse(gatePadVisible(settings))

        val shown = showingDialogCount()
        enterGatePin(settings, RIGHT_PIN)
        idle()
        assertEquals("replay must not run export again", shown, showingDialogCount())
        assertTrue(exportChooserVisible(settings))
    }

    @Test
    fun `the right PIN runs erase once and a wrong PIN does not`() {
        storePinInVault()
        val settings = openSettings()
        clickPref(settings, "clear")
        assertTrue(gatePadVisible(settings))

        enterGatePin(settings, WRONG_PIN)
        waitUntil("incorrect PIN is shown") { incorrectPinShown(settings) }
        assertFalse(eraseConfirmVisible(settings))

        enterGatePin(settings, RIGHT_PIN)
        waitUntil("erase confirmation appears") { eraseConfirmVisible(settings) }
        assertFalse(gatePadVisible(settings))

        val shown = confirmSheetCount(settings)
        enterGatePin(settings, RIGHT_PIN)
        idle()
        assertEquals("replay must not run erase again", shown, confirmSheetCount(settings))
        assertTrue(eraseConfirmVisible(settings))
    }

    @Test
    fun `with no PIN configured export and erase run directly`() {
        assertFalse(prefs.isPinConfigured())
        val settings = openSettings()
        clickPref(settings, "export")
        assertFalse(gatePadVisible(settings))
        assertTrue(exportChooserVisible(settings))

        closeActivity()
        ShadowDialog.reset()
        val again = openSettings()
        clickPref(again, "clear")
        assertFalse(gatePadVisible(again))
        assertTrue(eraseConfirmVisible(again))
    }

    @Test
    fun `a legacy pinHash still prompts`() {
        storeLegacyPinHash()
        assertTrue(prefs.pinKeyMaterial.isNullOrBlank())
        assertFalse(prefs.pinHash.isNullOrBlank())

        val settings = openSettings()
        clickPref(settings, "export")
        assertTrue(gatePadVisible(settings))
        assertFalse(exportChooserVisible(settings))

        enterGatePin(settings, RIGHT_PIN)
        waitUntil("export options appear") { exportChooserVisible(settings) }
        assertFalse(gatePadVisible(settings))
    }

    private fun storePinInVault() {
        PinVault(prefs).enable(RIGHT_PIN)
        prefs.pinHash = ""
        assertTrue(prefs.isPinConfigured())
        access.updatePin()
    }

    private fun storeLegacyPinHash() {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashed = Hash(digest.digest(RIGHT_PIN.toByteArray(StandardCharsets.UTF_8)))
        prefs.pinHash = hashed.toString()
        prefs.pinKeyMaterial = ""
        prefs.pinEnabled = true
        access.updatePin()
    }

    private fun openSettings(): SettingsActivity {
        closeActivity()
        val built = Robolectric.buildActivity(SettingsActivity::class.java)
        built.get().setTheme(R.style.AppThemeV3)
        controller = built.setup()
        idle()
        val activity = controller!!.get()
        assertFalse(activity.isFinishing)
        return activity
    }

    private fun closeActivity() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        idle()
    }

    private fun settingsFragment(activity: SettingsActivity): MainSettingsFragment {
        return activity.supportFragmentManager.fragments
            .filterIsInstance<MainSettingsFragment>()
            .single()
    }

    private fun clickPref(activity: SettingsActivity, key: String) {
        val pref = settingsFragment(activity).findPreference<Preference>(key)
        assertNotNull("missing preference $key", pref)
        pref!!.performClick()
        idle()
    }

    private fun gatePad(activity: SettingsActivity): LockScreenDialog? {
        return activity.supportFragmentManager
            .findFragmentByTag(MainSettingsFragment.TAG_PIN_GATE) as? LockScreenDialog
    }

    private fun gatePadVisible(activity: SettingsActivity): Boolean {
        val pad = gatePad(activity)
        return pad != null && pad.isAdded && pad.view != null
    }

    private fun enterGatePin(activity: SettingsActivity, pin: String) {
        activity.supportFragmentManager.setFragmentResult(
            MainSettingsFragment.REQUEST_KEY_PIN_GATE,
            bundleOf(LockScreenDialog.RESULT_PIN to pin),
        )
        idle()
    }

    private fun incorrectPinShown(activity: SettingsActivity): Boolean {
        val pad = gatePad(activity) ?: return false
        val prompt = pad.view?.findViewById<TextView>(R.id.lockScreenText) ?: return false
        return prompt.text.toString() == activity.getString(R.string.pin_incorrect)
    }

    private fun exportChooserVisible(activity: SettingsActivity): Boolean {
        val expected = activity.getString(R.string.choose_export_opt)
        return showingDialogs().any { expected in dialogText(it) || "Save to file" in dialogText(it) }
    }

    private fun eraseConfirmVisible(activity: SettingsActivity): Boolean {
        val sheet = confirmSheet(activity)
        if (sheet?.dialog?.isShowing == true) {
            val text = sheet.view?.let { dialogText(it) }.orEmpty()
            if (text.contains("All collections will be erased") || text.contains("Are you sure?")) {
                return true
            }
        }
        return showingDialogs().any { text ->
            val body = dialogText(text)
            body.contains("All collections will be erased") || body.contains("Are you sure?")
        }
    }

    private fun confirmSheet(activity: SettingsActivity): ConfirmBottomSheet? {
        return activity.supportFragmentManager.fragments.filterIsInstance<ConfirmBottomSheet>().lastOrNull()
    }

    private fun confirmSheetCount(activity: SettingsActivity): Int =
        activity.supportFragmentManager.fragments.count { it is ConfirmBottomSheet && it.isAdded }

    private fun showingDialogs(): List<Dialog> =
        ShadowDialog.getShownDialogs().filter { it.isShowing }

    private fun showingDialogCount(): Int = showingDialogs().size

    private fun dialogText(root: Any): String {
        val view: View? = when (root) {
            is Dialog -> root.window?.decorView
            is View -> root
            else -> null
        }
        if (view == null) return ""
        val parts = ArrayList<String>()
        val pending = ArrayDeque<View>()
        pending.add(view)
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (next is TextView) {
                val text = next.text?.toString().orEmpty()
                if (text.isNotEmpty()) parts.add(text)
            }
            if (next is ViewGroup) {
                for (i in 0 until next.childCount) pending.add(next.getChildAt(i))
            }
        }
        return parts.joinToString("\n")
    }

    private fun waitUntil(desc: String, timeoutMs: Long = 20_000, pred: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!pred()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $desc" }
            idle()
            Thread.sleep(20)
        }
        idle()
    }

    private fun idle() {
        controller?.get()?.supportFragmentManager?.executePendingTransactions()
        val looper = shadowOf(Looper.getMainLooper())
        looper.idle()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        looper.runToEndOfTasks()
    }

    companion object {
        // Obviously fake test values, not a real user PIN.
        private const val RIGHT_PIN = "24680"
        private const val WRONG_PIN = "13579"
    }
}
