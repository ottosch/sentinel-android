package com.samourai.sentinel.ui.collectionDetails.send

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException

/**
 * #566: the save picker hands back a DocumentsProvider URI.
 * [android.provider.DocumentsProvider.delete] is final and throws, so
 * [android.content.ContentResolver.delete] leaves the file behind.
 * [PsbtFileExport.deleteExportedDocument] is the call the screen makes,
 * and it has to go through [DocumentsContract.deleteDocument].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PsbtDocumentDeleteTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var provider: PickerDocumentsProvider

    @Before
    fun setUp() {
        provider = installPickerDocumentsProvider(context)
    }

    @Test
    fun contentResolverDeleteLeavesTheDocumentAndDeleteDocumentRemovesIt() {
        val uri = provider.put("empty", ByteArray(0))
        assertTrue(DocumentsContract.isDocumentUri(context, uri))
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (expected: UnsupportedOperationException) {
        }
        assertTrue("ContentResolver.delete must leave the document", provider.live.containsKey("empty"))
        assertTrue(provider.deletedIds.isEmpty())

        assertTrue(PsbtFileExport.deleteExportedDocument(context, uri))
        assertFalse(provider.live.containsKey("empty"))
        assertEquals(listOf("empty"), provider.deletedIds)
    }

    @Test
    fun processDeathDeletesTheEmptyPickerDocument() {
        val uri = provider.put("killed", ByteArray(0))
        var opened = false
        val outcome = PsbtFileExport.write(
            bytes = null,
            params = null,
            documentAlreadyExists = true,
            open = {
                opened = true
                ByteArrayOutputStream()
            },
            delete = { PsbtFileExport.deleteExportedDocument(context, uri) },
        )
        assertFalse(opened)
        assertTrue(outcome is PsbtFileExport.Outcome.Refused)
        assertTrue((outcome as PsbtFileExport.Outcome.Refused).documentRemoved)
        assertEquals(PsbtFileExport.Refusal.MISSING, outcome.reason)
        assertFalse(provider.live.containsKey("killed"))
        assertEquals(listOf("killed"), provider.deletedIds)
        assertEquals(R.string.send_export_again, PsbtFileExport.messageRes(outcome, sharing = false))
    }

    @Test
    fun aFailedDeleteTellsTheUserTheFileMayBeIncomplete() {
        val uri = provider.put("stuck", byteArrayOf(1, 2, 3))
        provider.failDelete = true
        val outcome = PsbtFileExport.write(
            bytes = null,
            params = null,
            documentAlreadyExists = true,
            open = { ByteArrayOutputStream() },
            delete = { PsbtFileExport.deleteExportedDocument(context, uri) },
        )
        assertTrue(outcome is PsbtFileExport.Outcome.Refused)
        assertFalse((outcome as PsbtFileExport.Outcome.Refused).documentRemoved)
        assertTrue(provider.live.containsKey("stuck"))
        assertEquals(
            "The file may be incomplete. Remove it.",
            context.getString(PsbtFileExport.messageRes(outcome, sharing = false)),
        )
    }

    @Test
    fun clearedTextExportDeletesThePickerDocument() {
        val uri = provider.put("txt", ByteArray(0))
        var opened = false
        val outcome = PsbtFileExport.writeText(
            text = "",
            params = null,
            documentAlreadyExists = true,
            open = {
                opened = true
                ByteArrayOutputStream()
            },
            delete = { PsbtFileExport.deleteExportedDocument(context, uri) },
        )
        assertFalse(opened)
        assertTrue(outcome is PsbtFileExport.Outcome.Refused)
        assertEquals(PsbtFileExport.Refusal.MISSING, (outcome as PsbtFileExport.Outcome.Refused).reason)
        assertFalse(provider.live.containsKey("txt"))
        assertEquals(listOf("txt"), provider.deletedIds)
    }

}

internal const val PICKER_AUTHORITY = "com.samourai.sentinel.psbt.test.documents"

internal fun installPickerDocumentsProvider(context: android.content.Context): PickerDocumentsProvider {
    val provider = PickerDocumentsProvider()
    val info = ProviderInfo().apply {
        authority = PICKER_AUTHORITY
        packageName = context.packageName
        name = PickerDocumentsProvider::class.java.name
        exported = true
        grantUriPermissions = true
        readPermission = android.Manifest.permission.MANAGE_DOCUMENTS
        writePermission = android.Manifest.permission.MANAGE_DOCUMENTS
    }
    provider.attachInfo(context, info)
    org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(PICKER_AUTHORITY, provider)
    val component = ComponentName(context.packageName, PickerDocumentsProvider::class.java.name)
    shadowOf(context.packageManager).addOrUpdateProvider(info)
    shadowOf(context.packageManager).addIntentFilterForProvider(
        component,
        android.content.IntentFilter("android.content.action.DOCUMENTS_PROVIDER"),
    )
    val resolve = ResolveInfo()
    resolve.providerInfo = info
    shadowOf(context.packageManager).addResolveInfoForIntent(
        Intent("android.content.action.DOCUMENTS_PROVIDER"),
        resolve,
    )
    return provider
}

internal class PickerDocumentsProvider : DocumentsProvider() {
    val live = LinkedHashMap<String, ByteArray>()
    val deletedIds = ArrayList<String>()
    var failDelete = false

    override fun onCreate(): Boolean = true

    fun put(id: String, bytes: ByteArray): Uri {
        live[id] = bytes
        return DocumentsContract.buildDocumentUri(PICKER_AUTHORITY, id)
    }

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        if (!live.containsKey(documentId)) throw FileNotFoundException(documentId)
        return MatrixCursor(arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)).apply {
            addRow(arrayOf(documentId))
        }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor = MatrixCursor(arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID))

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor = throw UnsupportedOperationException("open")

    override fun deleteDocument(documentId: String) {
        if (failDelete) throw IOException("provider refused")
        if (live.remove(documentId) == null) throw FileNotFoundException(documentId)
        deletedIds.add(documentId)
    }
}
