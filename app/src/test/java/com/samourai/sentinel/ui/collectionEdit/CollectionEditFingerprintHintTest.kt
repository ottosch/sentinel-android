package com.samourai.sentinel.ui.collectionEdit

import android.app.Application
import android.content.Intent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.samourai.sentinel.R
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.core.access.AccessFactory
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.db.SentinelCollectionStore
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.views.InputBottomSheet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * View Master Fingerprint: when the xpub has no fingerprint, the field
 * shows "Not included in this xpub, enter it from your signer". The
 * hint hides as soon as a value is entered, and Save stays as it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CollectionEditFingerprintHintTest {

    private lateinit var repository: CollectionRepository
    private var savedAdmitted = false
    private val hint = "Not included in this xpub, enter it from your signer"

    @Before
    fun setUp() {
        if (GlobalContext.getOrNull() != null) stopKoin()
        val app = ApplicationProvider.getApplicationContext<Application>()
        savedAdmitted = AccessFactory.getInstance(app).isSessionAdmitted
        val access = AccessFactory.getInstance(app)
        access.admitSession()
        val prefs = PrefsUtil(app)
        prefs.pinHash = ""
        prefs.pinKeyMaterial = ""
        prefs.pinEnabled = false
        repository = CollectionRepository()
        PubKeyRequests.clear()
        startKoin {
            androidContext(app)
            modules(
                module {
                    single { prefs }
                    single { access }
                    single { repository }
                    single { SentinelCollectionStore() }
                    single { DojoUtility() }
                    single { ApiService() }
                }
            )
        }
    }

    @After
    fun tearDown() {
        if (GlobalContext.getOrNull() != null) stopKoin()
        PubKeyRequests.clear()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val access = AccessFactory.getInstance(app)
        if (savedAdmitted) access.admitSession() else {
            val field = AccessFactory::class.java.getDeclaredField("sessionAdmitted")
            field.isAccessible = true
            field.setBoolean(null, false)
        }
    }

    @Test
    fun `an xpub with no fingerprint shows the hint, which hides as the user types`() {
        val activity = launch(
            PubKeyModel(pubKey = "zpub-1", label = "Savings", type = AddressTypes.BIP84, fingerPrint = null),
            PubKeyModel(pubKey = "zpub-2", label = "Travel", type = AddressTypes.BIP84, fingerPrint = ""),
        )
        val (layout, input, save) = openFingerprint(activity, 0)
        assertEquals(hint, activity.getString(R.string.pubkey_fingerprint_missing_hint))
        assertEquals(hint, layout.helperText?.toString())
        assertTrue(input.isFocusable)
        assertEquals(View.VISIBLE, save.visibility)
        assertEquals("Save", save.text.toString())

        input.setText("a1b2c3d4")
        ShadowLooper.idleMainLooper()
        assertTrue(layout.helperText.isNullOrBlank())

        input.setText("")
        ShadowLooper.idleMainLooper()
        assertEquals(hint, layout.helperText?.toString())
        assertEquals(View.VISIBLE, save.visibility)

        // A blank stored fingerprint shows the same hint.
        activity.supportFragmentManager.fragments.filterIsInstance<InputBottomSheet>()
            .forEach { it.dismissAllowingStateLoss() }
        ShadowLooper.idleMainLooper()
        val (blankLayout, _, _) = openFingerprint(activity, 1)
        assertEquals(hint, blankLayout.helperText?.toString())
    }

    @Test
    fun `an xpub that already has a fingerprint hides the hint and stays editable`() {
        val activity = launch(
            PubKeyModel(pubKey = "zpub-1", label = "Savings", type = AddressTypes.BIP84, fingerPrint = "a1b2c3d4"),
        )
        val (layout, input, save) = openFingerprint(activity, 0)
        assertEquals("a1b2c3d4", input.text.toString())
        assertTrue(layout.helperText.isNullOrBlank())
        assertTrue(input.isFocusable)
        assertEquals(View.VISIBLE, save.visibility)
    }

    @Test
    fun `a read-only fingerprint field on a wallet-imported collection hides the hint`() {
        val activity = launch(
            PubKeyModel(pubKey = "zpub-1", label = "Deposit", type = AddressTypes.BIP84, fingerPrint = null),
            isImportFromWallet = true,
        )
        val (layout, input, save) = openFingerprint(activity, 0)
        assertFalse(input.isFocusable)
        assertEquals(View.GONE, save.visibility)
        assertTrue(layout.helperText.isNullOrBlank())

        // Even if InputBottomSheet is opened directly with helperWhenBlank on a read-only field,
        // applyHelper() respects editable=false.
        val direct = InputBottomSheet.newInstance(
            label = "Master Fingerprint",
            buttonLabel = "Save",
            value = "",
            labelEditText = "Fingerprint",
            maxLen = 8,
            editable = false,
            requestKey = "direct_readonly_test",
            helperWhenBlank = hint,
        )
        direct.show(activity.supportFragmentManager, "direct_readonly")
        activity.supportFragmentManager.executePendingTransactions()
        ShadowLooper.idleMainLooper()
        val directLayout = direct.requireView().findViewById<TextInputLayout>(R.id.bottomSheetInputFieldLayout)
        assertTrue(directLayout.helperText.isNullOrBlank())
    }

    private fun launch(
        vararg keys: PubKeyModel,
        isImportFromWallet: Boolean = false,
    ): CollectionEditActivity {
        val collection = PubKeyCollection(
            collectionLabel = "Cold storage",
            pubs = ArrayList(keys.asList()),
            isImportFromWallet = isImportFromWallet,
        )
        repository.addNew(collection)
        val activity = Robolectric.buildActivity(
            CollectionEditActivity::class.java,
            Intent().putExtra("collection", collection.id),
        ).setup().get()
        ShadowLooper.idleMainLooper()
        assertFalse(activity.isFinishing)
        return activity
    }

    private fun openFingerprint(
        activity: CollectionEditActivity,
        index: Int,
    ): Triple<TextInputLayout, TextInputEditText, MaterialButton> {
        val pub = repository.collectionsSnapshot().single().pubs[index]
        activity.editFingerprint(pub, index)
        activity.supportFragmentManager.executePendingTransactions()
        ShadowLooper.idleMainLooper()
        val sheet = activity.supportFragmentManager.fragments
            .filterIsInstance<InputBottomSheet>()
            .single { it.isAdded && it.view != null }
        val view = sheet.requireView()
        return Triple(
            view.findViewById(R.id.bottomSheetInputFieldLayout),
            view.findViewById(R.id.bottomSheetInputField),
            view.findViewById(R.id.bottomSheetConfirmPositiveBtn),
        )
    }
}
