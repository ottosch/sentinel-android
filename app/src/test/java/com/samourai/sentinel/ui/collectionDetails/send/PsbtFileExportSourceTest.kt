package com.samourai.sentinel.ui.collectionDetails.send

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #566: file exports read the snapshot taken at Compose. They must
 * not grow a path back to the live composer, which resume and every
 * coin update rebuild.
 */
class PsbtFileExportSourceTest {

    @Test
    fun `file exports never call the live composer`() {
        val send = source("app/src/main/java/com/samourai/sentinel/ui/collectionDetails/send/SendFragment.kt")
        val vm = source("app/src/main/java/com/samourai/sentinel/ui/collectionDetails/send/SendViewModel.kt")
        val helper = source("app/src/main/java/com/samourai/sentinel/ui/collectionDetails/send/PsbtFileExport.kt")

        assertFalse(send.contains("getPSBT("))
        assertFalse(send.contains("psbtBytes!!"))
        assertFalse(send.contains(".writeBytes("))
        assertFalse(send.contains("fun getPsbtBytes()"))
        assertEquals(2, occurrences(send, "PsbtFileExport.write("))

        val share = send.substringAfter("private fun shareReviewedPsbt() {")
            .substringBefore("private fun sharePSBTFile()")
        assertTrue(share.contains("PsbtFileExport.write("))
        assertTrue(share.contains("documentAlreadyExists = false"))
        assertTrue(share.contains("PSBT_transaction.psbt"))
        assertTrue(share.contains("catch (t: Throwable)"))
        assertFalse(share.contains("!!"))
        assertFalse(share.contains("getPSBT"))
        assertFalse(share.contains("makeTx"))

        val save = send.substringAfter("if (requestCode == REQ_CODE_SAVE_PSBT")
            .substringBefore("else if (requestCode == REQ_CODE_SAVE_TXT")
        assertTrue(save.contains("PsbtFileExport.write("))
        assertTrue(save.contains("documentAlreadyExists = true"))
        assertFalse(save.contains("makeTx"))
        assertFalse(save.contains("getPSBT"))
        assertFalse(save.contains("prepareSpend"))
        assertFalse(save.contains("compose("))
        val savedAt = save.indexOf("File saved to")
        val okAt = save.indexOf("PsbtFileExport.Outcome.Saved")
        assertTrue("File saved must follow a successful write", okAt >= 0 && savedAt > okAt)

        val getter = vm.substringAfter("fun getPsbtBytes(): ByteArray?").substringBefore("\n")
        assertTrue(getter.contains("reviewedPsbt"))
        assertFalse(getter.contains("transactionComposer"))
        assertFalse(getter.contains("getPSBT"))
        assertEquals(1, occurrences(vm, "getPSBT()"))

        val make = vm.substringAfter("fun makeTx(): Boolean {").substringBefore("fun getPsbtBytes()")
        assertTrue(make.contains("transactionComposer.getPSBT()"))
        assertTrue(make.contains("_psbt.value = Hex.toHexString(raw)"))
        assertTrue(make.contains("PsbtFileExport.refusal"))
        assertTrue(make.contains("job?.isActive == true"))
        assertFalse(make.contains("postValue"))
        assertFalse(make.contains("toString()"))

        val setPub = vm.substringAfter("fun setPublicKey(").substringBefore("private fun getPubKeyModelByLabel")
        assertTrue(setPub.contains("clearReviewedPsbt()"))
        val apply = vm.substringAfter("private fun applySelectableUtxos")
            .substringBefore("fun setDestinationAddress")
        assertTrue(apply.contains("noteReviewedInputs(raw) { UtxoMetaUtil.has(it) }"))
        assertTrue(apply.contains("prepareSpend()"))
        val note = vm.substringAfter("internal fun noteReviewedInputs(")
            .substringBefore("fun recheckReviewedInputs()")
        assertTrue(note.contains("CoinSelection.resolve"))
        assertTrue(note.contains("Resolution.Missing"))
        assertTrue(note.contains("Resolution.Blocked"))

        val close = send.substringAfter("private fun closeUnsignedScreen() {")
            .substringBefore("private fun containerTransform")
        assertTrue(close.contains("viewModel.clearReviewedPsbt()"))
        assertFalse(close.contains("PSBT_transaction"))
        assertFalse(close.contains(".delete("))
        val clear = vm.substringAfter("fun clearReviewedPsbt() {").substringBefore("fun consumeFreshReview()")
        assertFalse(clear.contains("cacheDir"))
        assertFalse(clear.contains(".delete("))

        assertTrue(send.contains("viewModel.freshReview.observe"))
        assertTrue(send.contains("viewModel.recheckReviewedInputs()"))
        assertTrue(send.contains("UtxoMetaUtil.blockedChanges.observe"))
        assertTrue(helper.contains(".use {"))
        assertTrue(helper.contains("catch (t: Throwable)"))

        val strings = source("app/src/main/res/values/strings.xml")
        assertTrue(strings.contains("The transaction is still updating. Try again."))
        assertTrue(strings.contains("This transaction could not be built. Try again."))
        assertTrue(strings.contains("A coin in this transaction was spent or frozen. Review it again."))
        assertTrue(strings.contains("This transaction is no longer here. Export it again."))
        assertTrue(strings.contains("Could not save the file."))
        assertTrue(strings.contains("Could not share the file."))
    }

    private fun occurrences(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1

    private fun source(relative: String): String {
        val file = sequenceOf(
            File(relative),
            File(relative.removePrefix("app/")),
            File("../$relative"),
        ).firstOrNull { it.isFile }
            ?: error("missing $relative from ${File(".").absolutePath}")
        return file.readText()
    }
}
